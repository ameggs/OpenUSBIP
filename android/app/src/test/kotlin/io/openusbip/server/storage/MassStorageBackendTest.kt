package io.openusbip.server.storage

import io.openusbip.server.protocol.BasicHeader
import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for the synthesised mass-storage device, including a walk through the
 * exact sequence Linux performs when it brings a USB stick up and reads it.
 *
 * That sequence test is the one that matters. Each layer can be individually
 * correct while the composition still fails -- a CSW read arriving one URB
 * earlier than expected, a descriptor length that does not match its own
 * wTotalLength -- and none of those show up until a real client tries to
 * enumerate.
 */
class MassStorageBackendTest {

    private val blocks = 2048L      // 1 MiB
    private lateinit var device: MemoryBlockDevice
    private lateinit var backend: MassStorageBackend

    private fun setUp(readOnly: Boolean = false) {
        device = MemoryBlockDevice(blockCount = blocks, readOnly = readOnly)
        backend = MassStorageBackend(device, busid = "99-1")
    }

    private var seq = 0

    // --- URB builders -----------------------------------------------------

    private fun control(
        bmRequestType: Int,
        bRequest: Int,
        wValue: Int = 0,
        wIndex: Int = 0,
        wLength: Int = 0,
    ): CmdSubmit {
        val setup = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(bmRequestType.toByte())
            put(bRequest.toByte())
            putShort(wValue.toShort())
            putShort(wIndex.toShort())
            putShort(wLength.toShort())
        }.array()

        val isIn = (bmRequestType and 0x80) != 0
        return CmdSubmit(
            header = BasicHeader(
                Protocol.CMD_SUBMIT, ++seq, 0,
                if (isIn) Protocol.DIR_IN else Protocol.DIR_OUT, 0,
            ),
            transferFlags = 0,
            transferBufferLength = wLength,
            startFrame = 0,
            numberOfPackets = 0,
            interval = 0,
            setup = setup,
        )
    }

    private fun bulkOut(length: Int) = CmdSubmit(
        header = BasicHeader(
            Protocol.CMD_SUBMIT, ++seq, 0, Protocol.DIR_OUT, UsbDescriptors.EP_BULK_OUT,
        ),
        transferFlags = 0, transferBufferLength = length,
        startFrame = 0, numberOfPackets = 0, interval = 0, setup = ByteArray(8),
    )

    private fun bulkIn(length: Int) = CmdSubmit(
        header = BasicHeader(
            Protocol.CMD_SUBMIT, ++seq, 0, Protocol.DIR_IN, UsbDescriptors.EP_BULK_IN,
        ),
        transferFlags = 0, transferBufferLength = length,
        startFrame = 0, numberOfPackets = 0, interval = 0, setup = ByteArray(8),
    )

    /** Run one whole BOT command and return (data, cswStatus). */
    private fun scsiCommand(
        cdb: ByteArray,
        dataLength: Int,
        deviceToHost: Boolean,
        outData: ByteArray? = null,
    ): Pair<ByteArray, Int> {
        val cbw = BulkOnlyTransport.buildCbw(++seq, dataLength, deviceToHost, cdb)
        assertEquals(0, backend.submit(bulkOut(cbw.size), cbw).status)

        var data = ByteArray(0)
        if (deviceToHost && dataLength > 0) {
            val r = backend.submit(bulkIn(dataLength), null)
            if (r.status == 0) {
                data = r.data ?: ByteArray(0)
            } else {
                // The device stalled the data phase, meaning the command
                // failed. A real host clears the halt and reads the status,
                // so do the same here.
                backend.submit(control(0x02, 0x01, wValue = 0, wIndex = 0x81), null)
            }
        } else if (!deviceToHost && dataLength > 0) {
            assertEquals(0, backend.submit(bulkOut(dataLength), outData).status)
        }

        val csw = backend.submit(bulkIn(BulkOnlyTransport.CSW_SIZE), null)
        assertEquals(0, csw.status)
        val cswBytes = csw.data!!
        assertEquals(BulkOnlyTransport.CSW_SIZE, cswBytes.size)

        val buf = ByteBuffer.wrap(cswBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(
            "CSW signature",
            BulkOnlyTransport.CSW_SIGNATURE, buf.int,
        )
        buf.int   // tag
        buf.int   // residue
        return data to (buf.get().toInt() and 0xFF)
    }

    // --- enumeration ------------------------------------------------------

    @Test
    fun `device descriptor is well formed`() {
        setUp()
        val r = backend.submit(control(0x80, 0x06, wValue = 0x0100, wLength = 18), null)

        assertEquals(0, r.status)
        val d = r.data!!
        assertEquals(18, d.size)
        assertEquals(18, d[0].toInt())                       // bLength
        assertEquals(UsbDescriptors.TYPE_DEVICE, d[1].toInt())
        // bcdUSB 0x0200, little-endian on the wire
        assertEquals(0x00, d[2].toInt() and 0xFF)
        assertEquals(0x02, d[3].toInt() and 0xFF)
        assertEquals(1, d[17].toInt())                       // bNumConfigurations
    }

    @Test
    fun `configuration descriptor total length covers interface and endpoints`() {
        setUp()
        val d = backend.submit(control(0x80, 0x06, wValue = 0x0200, wLength = 64), null).data!!

        // 9 config + 9 interface + 7 + 7 endpoints
        assertEquals(32, d.size)
        val total = (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)
        assertEquals("wTotalLength must describe the whole block", 32, total)
        assertEquals(1, d[4].toInt())                        // bNumInterfaces

        // interface descriptor identifies mass storage / SCSI / bulk-only
        assertEquals(UsbDescriptors.CLASS_MASS_STORAGE, d[9 + 5].toInt() and 0xFF)
        assertEquals(UsbDescriptors.SUBCLASS_SCSI, d[9 + 6].toInt() and 0xFF)
        assertEquals(UsbDescriptors.PROTOCOL_BULK_ONLY, d[9 + 7].toInt() and 0xFF)

        // two bulk endpoints, IN then OUT
        assertEquals(0x81, d[18 + 2].toInt() and 0xFF)
        assertEquals(0x02, d[18 + 3].toInt() and 0xFF)       // bulk
        assertEquals(0x02, d[25 + 2].toInt() and 0xFF)
    }

    @Test
    fun `a short descriptor read is honoured so the host can probe the length first`() {
        setUp()
        // Linux reads 9 bytes of the config descriptor, learns wTotalLength,
        // then re-reads the whole thing.
        val short = backend.submit(control(0x80, 0x06, wValue = 0x0200, wLength = 9), null).data!!
        assertEquals(9, short.size)
        val total = (short[2].toInt() and 0xFF) or ((short[3].toInt() and 0xFF) shl 8)
        assertEquals(32, total)
    }

    @Test
    fun `string descriptors are utf16le`() {
        setUp()
        val lang = backend.submit(control(0x80, 0x06, wValue = 0x0300, wLength = 4), null).data!!
        assertEquals(0x09, lang[2].toInt() and 0xFF)         // en-US, LE
        assertEquals(0x04, lang[3].toInt() and 0xFF)

        val product = backend.submit(control(0x80, 0x06, wValue = 0x0302, wLength = 64), null).data!!
        assertEquals(UsbDescriptors.TYPE_STRING, product[1].toInt())
        // 'V' followed by a zero high byte
        assertEquals('V'.code, product[2].toInt())
        assertEquals(0, product[3].toInt())
    }

    @Test
    fun `set configuration is accepted so enumeration completes`() {
        setUp()
        assertEquals(0, backend.submit(control(0x00, 0x09, wValue = 1), null).status)
        assertEquals(1, backend.submit(control(0x80, 0x08, wLength = 1), null).data!![0].toInt())
    }

    @Test
    fun `get max lun reports a single logical unit`() {
        setUp()
        val r = backend.submit(control(0xA1, 0xFE, wLength = 1), null)
        assertEquals(0, r.status)
        assertEquals(0, r.data!![0].toInt())
    }

    @Test
    fun `an unsupported descriptor stalls rather than returning junk`() {
        setUp()
        // Device qualifier: we are not dual-speed, so this must stall.
        val r = backend.submit(control(0x80, 0x06, wValue = 0x0600, wLength = 10), null)
        assertTrue(r.status != 0)
    }

    // --- the full mount sequence -----------------------------------------

    @Test
    fun `a client can enumerate, identify and read the volume`() {
        setUp()

        // 1. enumeration
        assertEquals(18, backend.submit(control(0x80, 0x06, wValue = 0x0100, wLength = 18), null).data!!.size)
        assertEquals(32, backend.submit(control(0x80, 0x06, wValue = 0x0200, wLength = 64), null).data!!.size)
        assertEquals(0, backend.submit(control(0x00, 0x09, wValue = 1), null).status)
        assertEquals(0, backend.submit(control(0xA1, 0xFE, wLength = 1), null).status)

        // 2. INQUIRY
        val (inquiry, inqStatus) = scsiCommand(
            byteArrayOf(ScsiTarget.OP_INQUIRY.toByte(), 0, 0, 0, 36, 0), 36, true,
        )
        assertEquals(BulkOnlyTransport.STATUS_GOOD, inqStatus)
        assertEquals(36, inquiry.size)
        assertEquals(0x00, inquiry[0].toInt())              // direct-access block device

        // 3. TEST UNIT READY
        val (_, readyStatus) = scsiCommand(ByteArray(6), 0, false)
        assertEquals(BulkOnlyTransport.STATUS_GOOD, readyStatus)

        // 4. READ CAPACITY
        val (capacity, capStatus) = scsiCommand(
            ByteArray(10).also { it[0] = ScsiTarget.OP_READ_CAPACITY_10.toByte() }, 8, true,
        )
        assertEquals(BulkOnlyTransport.STATUS_GOOD, capStatus)
        assertEquals((blocks - 1).toInt(), ScsiTarget.be32(capacity, 0))
        assertEquals(512, ScsiTarget.be32(capacity, 4))

        // 5. READ block 0
        val (sector, readStatus) = scsiCommand(read10(0, 1), 512, true)
        assertEquals(BulkOnlyTransport.STATUS_GOOD, readStatus)
        assertEquals(512, sector.size)
    }

    @Test
    fun `a client can write and read the data back`() {
        setUp()
        val payload = ByteArray(512) { (it % 97).toByte() }

        val (_, writeStatus) = scsiCommand(write10(42, 1), 512, false, payload)
        assertEquals(BulkOnlyTransport.STATUS_GOOD, writeStatus)

        val (readBack, readStatus) = scsiCommand(read10(42, 1), 512, true)
        assertEquals(BulkOnlyTransport.STATUS_GOOD, readStatus)
        assertArrayEquals(payload, readBack)
    }

    @Test
    fun `a multi block write survives the round trip`() {
        setUp()
        val payload = ByteArray(512 * 8) { (it % 251).toByte() }

        assertEquals(BulkOnlyTransport.STATUS_GOOD, scsiCommand(write10(100, 8), payload.size, false, payload).second)
        assertArrayEquals(payload, scsiCommand(read10(100, 8), payload.size, true).first)
    }

    @Test
    fun `many commands in sequence keep the phase machine in step`() {
        // The BOT layer is stateful, so the risk is drifting a phase and having
        // every later command answered with the wrong thing.
        setUp()
        for (lba in 0 until 40) {
            val payload = ByteArray(512) { lba.toByte() }
            assertEquals(
                "write at $lba",
                BulkOnlyTransport.STATUS_GOOD,
                scsiCommand(write10(lba, 1), 512, false, payload).second,
            )
            val (data, status) = scsiCommand(read10(lba, 1), 512, true)
            assertEquals("read at $lba", BulkOnlyTransport.STATUS_GOOD, status)
            assertEquals("content at $lba", lba.toByte(), data[0])
        }
        assertEquals(BulkOnlyTransport.Phase.IDLE, bot().phase)
    }

    @Test
    fun `a failed command reports CSW failure and the sense explains it`() {
        setUp()
        // Read past the end.
        val (_, status) = scsiCommand(read10(blocks.toInt(), 1), 512, true)
        assertEquals(BulkOnlyTransport.STATUS_FAILED, status)

        val (sense, senseStatus) = scsiCommand(
            byteArrayOf(ScsiTarget.OP_REQUEST_SENSE.toByte(), 0, 0, 0, 18, 0), 18, true,
        )
        assertEquals(BulkOnlyTransport.STATUS_GOOD, senseStatus)
        assertEquals(ScsiTarget.SENSE_ILLEGAL_REQUEST, sense[2].toInt() and 0x0F)
        assertEquals(ScsiTarget.ASC_LBA_OUT_OF_RANGE, sense[12].toInt() and 0xFF)
    }

    @Test
    fun `a read only volume reports write protect through the whole stack`() {
        setUp(readOnly = true)
        val (_, status) = scsiCommand(write10(0, 1), 512, false, ByteArray(512))
        assertEquals(BulkOnlyTransport.STATUS_FAILED, status)
    }

    @Test
    fun `a bulk only reset abandons an in-flight command`() {
        setUp()
        // Start a command and leave it mid-phase.
        val cbw = BulkOnlyTransport.buildCbw(1, 512, true, read10(0, 1))
        backend.submit(bulkOut(cbw.size), cbw)

        // Class reset, then the next CBW must be accepted from IDLE.
        assertEquals(0, backend.submit(control(0x21, 0xFF), null).status)

        val (_, status) = scsiCommand(read10(1, 1), 512, true)
        assertEquals(BulkOnlyTransport.STATUS_GOOD, status)
    }

    @Test
    fun `writing to the wrong endpoint stalls`() {
        setUp()
        val onWrongEp = CmdSubmit(
            header = BasicHeader(Protocol.CMD_SUBMIT, 1, 0, Protocol.DIR_OUT, 7),
            transferFlags = 0, transferBufferLength = 31,
            startFrame = 0, numberOfPackets = 0, interval = 0, setup = ByteArray(8),
        )
        assertTrue(backend.submit(onWrongEp, ByteArray(31)).status != 0)
    }

    @Test
    fun `the advertised device looks like mass storage to the op phase`() {
        setUp()
        assertNotNull(backend.device)
        assertEquals("99-1", backend.busid)
        assertEquals(1, backend.device.numInterfaces)
        assertEquals(
            UsbDescriptors.CLASS_MASS_STORAGE,
            backend.device.interfaces[0].interfaceClass,
        )
        assertEquals(blocks * 512, backend.capacityBytes)
    }

    // --- helpers ----------------------------------------------------------

    private fun read10(lba: Int, count: Int) = byteArrayOf(
        ScsiTarget.OP_READ_10.toByte(), 0,
        ((lba ushr 24) and 0xFF).toByte(), ((lba ushr 16) and 0xFF).toByte(),
        ((lba ushr 8) and 0xFF).toByte(), (lba and 0xFF).toByte(),
        0, ((count ushr 8) and 0xFF).toByte(), (count and 0xFF).toByte(), 0,
    )

    private fun write10(lba: Int, count: Int) = byteArrayOf(
        ScsiTarget.OP_WRITE_10.toByte(), 0,
        ((lba ushr 24) and 0xFF).toByte(), ((lba ushr 16) and 0xFF).toByte(),
        ((lba ushr 8) and 0xFF).toByte(), (lba and 0xFF).toByte(),
        0, ((count ushr 8) and 0xFF).toByte(), (count and 0xFF).toByte(), 0,
    )

    /** Reach the transport for phase assertions. */
    private fun bot(): BulkOnlyTransport {
        val field = MassStorageBackend::class.java.getDeclaredField("bot")
        field.isAccessible = true
        return field.get(backend) as BulkOnlyTransport
    }
}
