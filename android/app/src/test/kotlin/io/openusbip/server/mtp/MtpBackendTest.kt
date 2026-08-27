package io.openusbip.server.mtp

import io.openusbip.server.protocol.BasicHeader
import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transport-level tests: descriptors, and whole PTP transactions driven as
 * URBs the way a client issues them.
 *
 * The layers below are individually tested; what this covers is the ordering
 * the transport has to get right -- data container before response, a data
 * phase reassembled across several URBs -- which is exactly where the
 * mass-storage path went wrong and only real hardware caught it.
 */
class MtpBackendTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var backend: MtpBackend
    private var seq = 0
    private var txn = 0

    @Before
    fun setUp() {
        root = temp.newFolder("shared")
        File(root, "notes.txt").writeText("hello world")
        File(root, "Pics").mkdirs()
        File(root, "Pics/cat.jpg").writeText("jpeg")
        backend = MtpBackend(root, busid = "99-2", deviceName = "TestPhone")
    }

    // --- URB helpers ------------------------------------------------------

    private fun control(
        bmRequestType: Int, bRequest: Int, wValue: Int = 0, wIndex: Int = 0, wLength: Int = 0,
    ): CmdSubmit {
        val setup = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(bmRequestType.toByte()); put(bRequest.toByte())
            putShort(wValue.toShort()); putShort(wIndex.toShort()); putShort(wLength.toShort())
        }.array()
        return CmdSubmit(
            BasicHeader(
                Protocol.CMD_SUBMIT, ++seq, 0,
                if (bmRequestType and 0x80 != 0) Protocol.DIR_IN else Protocol.DIR_OUT, 0,
            ),
            0, wLength, 0, 0, 0, setup,
        )
    }

    private fun bulkOut(length: Int) = CmdSubmit(
        BasicHeader(Protocol.CMD_SUBMIT, ++seq, 0, Protocol.DIR_OUT, MtpDescriptors.EP_BULK_OUT),
        0, length, 0, 0, 0, ByteArray(8),
    )

    private fun bulkIn(length: Int) = CmdSubmit(
        BasicHeader(Protocol.CMD_SUBMIT, ++seq, 0, Protocol.DIR_IN, MtpDescriptors.EP_BULK_IN),
        0, length, 0, 0, 0, ByteArray(8),
    )

    /** Send a command and collect the data container and response. */
    private fun transact(op: Int, vararg params: Int): Pair<ByteArray, PtpContainer> {
        val command = PtpContainer(MtpConstants.CONTAINER_COMMAND, op, ++txn, params.toList())
        assertEquals(0, backend.submit(bulkOut(command.encode().size), command.encode()).status)

        var data = ByteArray(0)
        var response: PtpContainer? = null

        // Read until the response arrives; a data container may precede it.
        repeat(20) {
            val chunk = backend.submit(bulkIn(4096), null).data ?: ByteArray(0)
            if (chunk.isEmpty()) return@repeat
            val c = PtpContainer.decode(chunk)
            when (c?.type) {
                MtpConstants.CONTAINER_DATA -> data += c.payload
                MtpConstants.CONTAINER_RESPONSE -> { response = c; return data to c }
                else -> Unit
            }
        }
        return data to (response ?: error("no response for op 0x%04x".format(op)))
    }

    private fun openSession() {
        assertEquals(MtpConstants.RESP_OK, transact(MtpConstants.OP_OPEN_SESSION, 1).second.code)
    }

    // --- descriptors ------------------------------------------------------

    @Test
    fun `device descriptor is well formed`() {
        val d = backend.submit(control(0x80, 0x06, 0x0100, wLength = 18), null).data!!
        assertEquals(18, d.size)
        assertEquals(18, d[0].toInt())
        assertEquals(0x01, d[1].toInt())
    }

    @Test
    fun `interface is declared as still image, which is PTP`() {
        val d = backend.submit(control(0x80, 0x06, 0x0200, wLength = 64), null).data!!
        // 9 config + 9 interface + 3 x 7 endpoints
        assertEquals(9 + 9 + 21, d.size)
        val total = (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)
        assertEquals(d.size, total)

        assertEquals(3, d[9 + 4].toInt())                                   // bNumEndpoints
        assertEquals(MtpDescriptors.CLASS_STILL_IMAGE, d[9 + 5].toInt())
        assertEquals(MtpDescriptors.SUBCLASS_STILL_IMAGE, d[9 + 6].toInt())
        assertEquals(MtpDescriptors.PROTOCOL_PIMA_15740, d[9 + 7].toInt())
    }

    @Test
    fun `three endpoints are declared including an interrupt for events`() {
        val d = backend.submit(control(0x80, 0x06, 0x0200, wLength = 64), null).data!!
        assertEquals(0x81, d[18 + 2].toInt() and 0xFF)   // bulk IN
        assertEquals(0x02, d[18 + 3].toInt() and 0xFF)
        assertEquals(0x02, d[25 + 2].toInt() and 0xFF)   // bulk OUT
        assertEquals(0x83, d[32 + 2].toInt() and 0xFF)   // interrupt IN
        assertEquals(0x03, d[32 + 3].toInt() and 0xFF)   // interrupt type
    }

    @Test
    fun `the microsoft OS string descriptor is served at index 0xEE`() {
        // This is what makes a host bind MTP rather than generic PTP.
        val d = backend.submit(
            control(0x80, 0x06, 0x0300 or MtpDescriptors.OS_STRING_INDEX, wLength = 18), null,
        ).data!!
        assertEquals(0x03, d[1].toInt())
        val signature = StringBuilder()
        for (i in 2 until 16 step 2) signature.append((d[i].toInt() and 0xFF).toChar())
        assertEquals("MSFT100", signature.toString())
        assertEquals(MtpDescriptors.OS_VENDOR_CODE, d[16].toInt())
    }

    @Test
    fun `the extended compat id names MTP`() {
        val d = backend.submit(
            control(0xC0, MtpDescriptors.OS_VENDOR_CODE, 0, 0x0004, 40), null,
        ).data!!
        assertEquals(40, d.size)
        val compatId = String(d, 18, 3, Charsets.US_ASCII)
        assertEquals("MTP", compatId)
    }

    // --- transactions -----------------------------------------------------

    @Test
    fun `a client can open a session and read device info`() {
        openSession()
        val (data, response) = transact(MtpConstants.OP_GET_DEVICE_INFO)
        assertEquals(MtpConstants.RESP_OK, response.code)

        val r = PtpCodec.Reader(data)
        assertEquals(100, r.u16())
        assertEquals(6, r.u32())          // MTP vendor extension
    }

    @Test
    fun `data container arrives before the response`() {
        // Sending the response first makes a client discard the payload.
        openSession()
        val command = PtpContainer(
            MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_GET_STORAGE_IDS, ++txn,
        )
        backend.submit(bulkOut(command.encode().size), command.encode())

        val first = PtpContainer.decode(backend.submit(bulkIn(4096), null).data!!)!!
        assertEquals(MtpConstants.CONTAINER_DATA, first.type)

        val second = PtpContainer.decode(backend.submit(bulkIn(4096), null).data!!)!!
        assertEquals(MtpConstants.CONTAINER_RESPONSE, second.type)
        assertEquals(command.transactionId, second.transactionId)
    }

    @Test
    fun `the whole browse sequence works as a client performs it`() {
        openSession()
        assertEquals(MtpConstants.RESP_OK, transact(MtpConstants.OP_GET_STORAGE_IDS).second.code)
        assertEquals(
            MtpConstants.RESP_OK,
            transact(MtpConstants.OP_GET_STORAGE_INFO, MtpConstants.STORAGE_ID).second.code,
        )

        val handles = PtpCodec.Reader(
            transact(
                MtpConstants.OP_GET_OBJECT_HANDLES,
                MtpConstants.STORAGE_ID, 0, MtpConstants.HANDLE_ROOT,
            ).first,
        ).u32Array()
        assertEquals(2, handles.size)      // notes.txt and Pics

        // Names come from ObjectInfo, at the spec offset of 52.
        val names = handles.map { h ->
            val r = PtpCodec.Reader(transact(MtpConstants.OP_GET_OBJECT_INFO, h).first)
            r.skip(52); r.string()
        }
        assertTrue(names.contains("notes.txt"))
        assertTrue(names.contains("Pics"))
    }

    @Test
    fun `a file can be read back through GetObject`() {
        openSession()
        val handles = PtpCodec.Reader(
            transact(
                MtpConstants.OP_GET_OBJECT_HANDLES,
                MtpConstants.STORAGE_ID, 0, MtpConstants.HANDLE_ROOT,
            ).first,
        ).u32Array()

        val notes = handles.first { h ->
            val r = PtpCodec.Reader(transact(MtpConstants.OP_GET_OBJECT_INFO, h).first)
            r.skip(52); r.string() == "notes.txt"
        }

        val (data, response) = transact(MtpConstants.OP_GET_OBJECT, notes)
        assertEquals(MtpConstants.RESP_OK, response.code)
        assertArrayEquals("hello world".toByteArray(), data)
    }

    @Test
    fun `a large file is reassembled across several host reads`() {
        // 300 KB forces the data container to span many bulk IN transfers.
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        File(root, "big.bin").writeBytes(payload)

        openSession()
        val handles = PtpCodec.Reader(
            transact(
                MtpConstants.OP_GET_OBJECT_HANDLES,
                MtpConstants.STORAGE_ID, 0, MtpConstants.HANDLE_ROOT,
            ).first,
        ).u32Array()
        val big = handles.first { h ->
            val r = PtpCodec.Reader(transact(MtpConstants.OP_GET_OBJECT_INFO, h).first)
            r.skip(52); r.string() == "big.bin"
        }

        // Read in 512-byte chunks, as a real host would.
        val command = PtpContainer(MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_GET_OBJECT, ++txn, listOf(big))
        backend.submit(bulkOut(command.encode().size), command.encode())

        var assembled = ByteArray(0)
        var response: PtpContainer? = null
        var guard = 0
        while (response == null && guard++ < 5000) {
            val chunk = backend.submit(bulkIn(512), null).data ?: break
            if (chunk.isEmpty()) break
            if (assembled.isEmpty() && chunk.size >= MtpConstants.CONTAINER_HEADER_SIZE) {
                val c = PtpContainer.decode(chunk)
                if (c?.type == MtpConstants.CONTAINER_RESPONSE) { response = c; break }
            }
            assembled += chunk
        }
        // Strip the data container header from the front.
        val body = assembled.copyOfRange(MtpConstants.CONTAINER_HEADER_SIZE, assembled.size)
        assertArrayEquals(payload, body.copyOf(payload.size))
    }

    @Test
    fun `a client can create a file and read it back`() {
        openSession()

        // SendObjectInfo carries its dataset in a data container.
        val info = PtpContainer(
            MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_SEND_OBJECT_INFO, ++txn,
            listOf(MtpConstants.STORAGE_ID, MtpConstants.HANDLE_ROOT),
        )
        backend.submit(bulkOut(info.encode().size), info.encode())

        val dataset = PtpCodec.Writer(128).apply {
            u32(MtpConstants.STORAGE_ID); u16(MtpConstants.FORMAT_TEXT); u16(0); u32(5)
            u16(0); repeat(6) { u32(0) }
            u32(0); u16(0); u32(0); u32(0)
            string("fromclient.txt"); string(""); string(""); string("")
        }.toByteArray()
        val dataContainer = PtpContainer.data(MtpConstants.OP_SEND_OBJECT_INFO, info.transactionId, dataset)
        backend.submit(bulkOut(dataContainer.encode().size), dataContainer.encode())

        val infoResponse = PtpContainer.decode(backend.submit(bulkIn(4096), null).data!!)!!
        assertEquals(MtpConstants.RESP_OK, infoResponse.code)

        // Then SendObject with the contents.
        val send = PtpContainer(MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_SEND_OBJECT, ++txn)
        backend.submit(bulkOut(send.encode().size), send.encode())
        val body = PtpContainer.data(MtpConstants.OP_SEND_OBJECT, send.transactionId, "12345".toByteArray())
        backend.submit(bulkOut(body.encode().size), body.encode())

        val sendResponse = PtpContainer.decode(backend.submit(bulkIn(4096), null).data!!)!!
        assertEquals(MtpConstants.RESP_OK, sendResponse.code)
        assertEquals("12345", File(root, "fromclient.txt").readText())
    }

    // --- robustness -------------------------------------------------------

    @Test
    fun `an interrupt read returns nothing rather than stalling`() {
        // We raise no events. Stalling makes hosts retry in a tight loop.
        val cmd = CmdSubmit(
            BasicHeader(Protocol.CMD_SUBMIT, ++seq, 0, Protocol.DIR_IN, MtpDescriptors.EP_INTERRUPT_IN),
            0, 28, 0, 0, 0, ByteArray(8),
        )
        val result = backend.submit(cmd, null)
        assertEquals(0, result.status)
        assertEquals(0, result.actualLength)
    }

    @Test
    fun `a class cancel abandons the transaction`() {
        openSession()
        val command = PtpContainer(MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_GET_DEVICE_INFO, ++txn)
        backend.submit(bulkOut(command.encode().size), command.encode())

        // Cancel before reading, then a fresh transaction must still work.
        assertEquals(0, backend.submit(control(0x21, 0x64), null).status)
        assertEquals(MtpConstants.RESP_OK, transact(MtpConstants.OP_GET_STORAGE_IDS).second.code)
    }

    @Test
    fun `device status reports OK`() {
        val d = backend.submit(control(0xA1, 0x67, wLength = 4), null).data!!
        val r = PtpCodec.Reader(d)
        assertEquals(4, r.u16())
        assertEquals(MtpConstants.RESP_OK, r.u16())
    }

    @Test
    fun `garbage on the bulk endpoint stalls rather than corrupting state`() {
        assertTrue(backend.submit(bulkOut(3), byteArrayOf(1, 2, 3)).status != 0)
    }

    @Test
    fun `writing to an unexpected endpoint stalls`() {
        val cmd = CmdSubmit(
            BasicHeader(Protocol.CMD_SUBMIT, ++seq, 0, Protocol.DIR_OUT, 9),
            0, 12, 0, 0, 0, ByteArray(8),
        )
        assertTrue(backend.submit(cmd, ByteArray(12)).status != 0)
    }

    @Test
    fun `the advertised device looks like a still image class device`() {
        assertEquals("99-2", backend.busid)
        assertEquals(
            MtpDescriptors.CLASS_STILL_IMAGE,
            backend.device.interfaces[0].interfaceClass,
        )
    }
}
