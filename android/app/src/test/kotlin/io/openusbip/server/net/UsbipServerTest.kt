package io.openusbip.server.net

import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.OpHeader
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.protocol.UsbipDevice
import io.openusbip.server.protocol.UsbipInterface
import io.openusbip.server.protocol.Wire
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests for the server, driven over a real loopback socket with
 * hand-built USB/IP messages.
 *
 * This exercises the parts unit tests on the codecs cannot reach: framing
 * across message boundaries, whether an OUT payload is consumed even when the
 * transfer is refused, and the exclusivity rule. Getting framing wrong does
 * not throw -- it silently shifts every subsequent message by a few bytes.
 */
class UsbipServerTest {

    /** Records what it was asked to do and returns canned answers. */
    private class FakeBackend(
        busid: String = "1-4",
        private val reply: (CmdSubmit, ByteArray?) -> UrbResult = { _, _ -> UrbResult.OK },
    ) : UsbBackend {
        val submissions = mutableListOf<Pair<CmdSubmit, ByteArray?>>()
        var opened = 0
        var closed = 0

        override val device = UsbipDevice(
            path = "/dev/bus/usb/001/004",
            busid = busid,
            busnum = 1,
            devnum = busid.substringAfter('-').toInt(),
            speed = Protocol.SPEED_HIGH,
            idVendor = 0x0781,
            idProduct = 0x5575,
            bcdDevice = 0x0200,
            deviceClass = 0,
            deviceSubClass = 0,
            deviceProtocol = 0,
            configurationValue = 1,
            numConfigurations = 1,
            numInterfaces = 1,
            interfaces = listOf(UsbipInterface(0x08, 0x06, 0x50)),
        )

        override fun submit(cmd: CmdSubmit, outData: ByteArray?): UrbResult {
            submissions += cmd to outData
            return reply(cmd, outData)
        }

        override fun open() { opened++ }
        override fun close() { closed++ }
    }

    private lateinit var registry: DeviceRegistry
    private lateinit var server: UsbipServer

    @Before
    fun setUp() {
        registry = DeviceRegistry()
        server = UsbipServer(registry, port = 0)   // 0 = pick a free port
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // --- tiny test client -------------------------------------------------

    private fun connect(): Socket = Socket("127.0.0.1", server.boundPort).apply {
        soTimeout = 5000
        tcpNoDelay = true
    }

    private fun sendOpRequest(out: DataOutputStream, code: Int) {
        out.write(OpHeader(Protocol.VERSION, code, 0).encode())
        out.flush()
    }

    private fun sendImport(out: DataOutputStream, busid: String) {
        sendOpRequest(out, Protocol.OP_REQ_IMPORT)
        val buf = Wire.buffer(Protocol.BUSID_LEN)
        val raw = busid.toByteArray(Charsets.US_ASCII)
        buf.put(raw)
        repeat(Protocol.BUSID_LEN - raw.size) { buf.put(0) }
        out.write(buf.array())
        out.flush()
    }

    private fun submitHeader(
        seqnum: Int,
        direction: Int,
        ep: Int,
        length: Int,
        packets: Int = 0,
    ): ByteArray {
        val buf = Wire.buffer(Protocol.URB_HEADER_SIZE)
        buf.putInt(Protocol.CMD_SUBMIT)
        buf.putInt(seqnum)
        buf.putInt(Protocol.devid(1, 4))
        buf.putInt(direction)
        buf.putInt(ep)
        buf.putInt(0)          // transfer_flags
        buf.putInt(length)
        buf.putInt(0)          // start_frame
        buf.putInt(packets)
        buf.putInt(0)          // interval
        buf.put(ByteArray(8))  // setup
        return buf.array()
    }

    private fun readRetSubmit(input: InputStream): Triple<Int, Int, Int> {
        val buf = Wire.reader(Wire.readExactly(input, Protocol.URB_HEADER_SIZE))
        val command = buf.int
        val seqnum = buf.int
        buf.int      // devid
        buf.int      // direction
        buf.int      // ep
        val status = buf.int
        val actual = buf.int
        assertEquals(Protocol.RET_SUBMIT, command)
        return Triple(seqnum, status, actual)
    }

    // --- DEVLIST ---------------------------------------------------------

    @Test
    fun `devlist reports an offered device`() {
        registry.add(FakeBackend())
        server.start()

        connect().use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            sendOpRequest(out, Protocol.OP_REQ_DEVLIST)

            val input = socket.getInputStream()
            val header = OpHeader.decode(Wire.readExactly(input, Protocol.OP_HEADER_SIZE))
            assertEquals(Protocol.OP_REP_DEVLIST, header.code)
            assertEquals(Protocol.ST_OK, header.status)

            val count = Wire.reader(Wire.readExactly(input, 4)).int
            assertEquals(1, count)

            val device = UsbipDevice.decodeFrom(
                Wire.reader(Wire.readExactly(input, Protocol.USB_DEVICE_SIZE)),
            )
            assertEquals("1-4", device.busid)
            assertEquals(0x0781, device.idVendor)
        }
    }

    @Test
    fun `devlist is empty when nothing is offered`() {
        server.start()
        connect().use { socket ->
            sendOpRequest(DataOutputStream(socket.getOutputStream()), Protocol.OP_REQ_DEVLIST)
            val input = socket.getInputStream()
            Wire.readExactly(input, Protocol.OP_HEADER_SIZE)
            assertEquals(0, Wire.reader(Wire.readExactly(input, 4)).int)
        }
    }

    // --- IMPORT and exclusivity ------------------------------------------

    @Test
    fun `import succeeds and opens the backend`() {
        val backend = FakeBackend()
        registry.add(backend)
        server.start()

        connect().use { socket ->
            sendImport(DataOutputStream(socket.getOutputStream()), "1-4")

            val input = socket.getInputStream()
            val header = OpHeader.decode(Wire.readExactly(input, Protocol.OP_HEADER_SIZE))
            assertEquals(Protocol.OP_REP_IMPORT, header.code)
            assertEquals(Protocol.ST_OK, header.status)

            val device = UsbipDevice.decodeFrom(
                Wire.reader(Wire.readExactly(input, Protocol.USB_DEVICE_SIZE)),
            )
            assertEquals("1-4", device.busid)
            assertEquals(1, backend.opened)
        }
    }

    @Test
    fun `importing an unknown busid fails with only a header`() {
        registry.add(FakeBackend(busid = "1-4"))
        server.start()

        connect().use { socket ->
            sendImport(DataOutputStream(socket.getOutputStream()), "9-9")

            val input = socket.getInputStream()
            val header = OpHeader.decode(Wire.readExactly(input, Protocol.OP_HEADER_SIZE))
            assertEquals(Protocol.OP_REP_IMPORT, header.code)
            assertTrue(header.status != Protocol.ST_OK)
            // No device record must follow a failure.
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `a device already held cannot be imported twice`() {
        registry.add(FakeBackend())
        server.start()

        val first = connect()
        sendImport(DataOutputStream(first.getOutputStream()), "1-4")
        val firstHeader = OpHeader.decode(
            Wire.readExactly(first.getInputStream(), Protocol.OP_HEADER_SIZE),
        )
        assertEquals(Protocol.ST_OK, firstHeader.status)
        Wire.readExactly(first.getInputStream(), Protocol.USB_DEVICE_SIZE)

        // Second client: same device, must be refused.
        connect().use { second ->
            sendImport(DataOutputStream(second.getOutputStream()), "1-4")
            val header = OpHeader.decode(
                Wire.readExactly(second.getInputStream(), Protocol.OP_HEADER_SIZE),
            )
            assertTrue("second import must fail", header.status != Protocol.ST_OK)
        }

        first.close()
    }

    @Test
    fun `a held device disappears from devlist`() {
        registry.add(FakeBackend())
        server.start()

        val holder = connect()
        sendImport(DataOutputStream(holder.getOutputStream()), "1-4")
        Wire.readExactly(holder.getInputStream(), Protocol.OP_HEADER_SIZE)
        Wire.readExactly(holder.getInputStream(), Protocol.USB_DEVICE_SIZE)

        connect().use { browser ->
            sendOpRequest(DataOutputStream(browser.getOutputStream()), Protocol.OP_REQ_DEVLIST)
            val input = browser.getInputStream()
            Wire.readExactly(input, Protocol.OP_HEADER_SIZE)
            // usbipd hides an in-use device rather than flagging it busy.
            assertEquals(0, Wire.reader(Wire.readExactly(input, 4)).int)
        }

        holder.close()
    }

    @Test
    fun `dropping the connection releases the device`() {
        val backend = FakeBackend()
        registry.add(backend)
        server.start()

        val released = CountDownLatch(1)
        val watched = UsbipServer(
            registry, port = 0,
            listener = object : UsbipServer.Listener {
                override fun onDeviceReleased(client: String, busid: String) = released.countDown()
            },
        )
        watched.start()

        Socket("127.0.0.1", watched.boundPort).use { socket ->
            sendImport(DataOutputStream(socket.getOutputStream()), "1-4")
            Wire.readExactly(socket.getInputStream(), Protocol.OP_HEADER_SIZE)
            Wire.readExactly(socket.getInputStream(), Protocol.USB_DEVICE_SIZE)
        }

        assertTrue(
            "device should be released when the client goes away",
            released.await(5, TimeUnit.SECONDS),
        )
        assertNull(registry.holderOf("1-4"))
        watched.stop()
    }

    // --- URB phase -------------------------------------------------------

    private fun importAndGetStreams(socket: Socket) {
        sendImport(DataOutputStream(socket.getOutputStream()), "1-4")
        Wire.readExactly(socket.getInputStream(), Protocol.OP_HEADER_SIZE)
        Wire.readExactly(socket.getInputStream(), Protocol.USB_DEVICE_SIZE)
    }

    @Test
    fun `an IN transfer returns the backend payload`() {
        val payload = "hello usb".toByteArray()
        registry.add(FakeBackend { _, _ -> UrbResult.ok(payload) })
        server.start()

        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()
            out.write(submitHeader(seqnum = 1, direction = Protocol.DIR_IN, ep = 1, length = 64))
            out.flush()

            val (seq, status, actual) = readRetSubmit(socket.getInputStream())
            assertEquals(1, seq)
            assertEquals(0, status)
            assertEquals(payload.size, actual)
            assertArrayEquals(payload, Wire.readExactly(socket.getInputStream(), actual))
        }
    }

    @Test
    fun `an IN transfer is truncated to what the client asked for`() {
        // Returning more than transfer_buffer_length would overrun the
        // client's buffer and desynchronise the stream.
        registry.add(FakeBackend { _, _ -> UrbResult.ok(ByteArray(100) { it.toByte() }) })
        server.start()

        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()
            out.write(submitHeader(seqnum = 2, direction = Protocol.DIR_IN, ep = 1, length = 8))
            out.flush()

            val (_, _, actual) = readRetSubmit(socket.getInputStream())
            assertEquals(8, actual)
            assertEquals(8, Wire.readExactly(socket.getInputStream(), actual).size)
        }
    }

    @Test
    fun `an OUT payload reaches the backend intact`() {
        val backend = FakeBackend()
        registry.add(backend)
        server.start()

        val payload = "written to device".toByteArray()
        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()
            out.write(
                submitHeader(
                    seqnum = 3, direction = Protocol.DIR_OUT, ep = 2, length = payload.size,
                ),
            )
            out.write(payload)
            out.flush()

            readRetSubmit(socket.getInputStream())
            assertEquals(1, backend.submissions.size)
            assertArrayEquals(payload, backend.submissions[0].second)
        }
    }

    @Test
    fun `framing survives several urbs in a row`() {
        // The real risk with fixed-size headers plus variable payloads is
        // drifting by a few bytes, which only shows up after the first message.
        registry.add(FakeBackend { cmd, _ -> UrbResult.ok(ByteArray(cmd.transferBufferLength) { 0x41 }) })
        server.start()

        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            for (seq in 1..25) {
                val length = seq * 3
                out.write(submitHeader(seq, Protocol.DIR_IN, ep = 1, length = length))
                out.flush()

                val (gotSeq, status, actual) = readRetSubmit(input)
                assertEquals("seqnum drift at $seq", seq, gotSeq)
                assertEquals(0, status)
                assertEquals(length, actual)
                Wire.readExactly(input, actual)
            }
        }
    }

    @Test
    fun `an isochronous urb is refused and its payload still consumed`() {
        // Android cannot do isochronous. The reply must be an error, and the
        // OUT payload must still be drained or the stream desynchronises.
        val backend = FakeBackend()
        registry.add(backend)
        server.start()

        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()
            out.write(
                submitHeader(
                    seqnum = 7, direction = Protocol.DIR_OUT, ep = 3, length = 16, packets = 4,
                ),
            )
            out.write(ByteArray(16))
            out.flush()

            val (seq, status, _) = readRetSubmit(socket.getInputStream())
            assertEquals(7, seq)
            assertTrue("isochronous must fail", status != 0)
            assertTrue("backend must not see an iso urb", backend.submissions.isEmpty())

            // The stream must still be usable.
            out.write(submitHeader(seqnum = 8, direction = Protocol.DIR_IN, ep = 1, length = 4))
            out.flush()
            assertEquals(8, readRetSubmit(socket.getInputStream()).first)
        }
    }

    @Test
    fun `a backend exception becomes a stall rather than killing the session`() {
        registry.add(FakeBackend { _, _ -> throw IllegalStateException("device exploded") })
        server.start()

        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()
            out.write(submitHeader(seqnum = 11, direction = Protocol.DIR_IN, ep = 1, length = 8))
            out.flush()

            val (seq, status, _) = readRetSubmit(socket.getInputStream())
            assertEquals(11, seq)
            assertEquals(-32, status)   // -EPIPE, a stall the driver can handle
        }
    }

    @Test
    fun `unlink is answered so the client is not left waiting`() {
        registry.add(FakeBackend())
        server.start()

        connect().use { socket ->
            importAndGetStreams(socket)
            val out = socket.getOutputStream()

            val buf = Wire.buffer(Protocol.URB_HEADER_SIZE)
            buf.putInt(Protocol.CMD_UNLINK)
            buf.putInt(50)                        // this message's seqnum
            buf.putInt(Protocol.devid(1, 4))
            buf.putInt(Protocol.DIR_OUT)
            buf.putInt(0)
            buf.putInt(42)                        // seqnum to unlink
            out.write(buf.array())
            out.flush()

            val reply = Wire.reader(
                Wire.readExactly(socket.getInputStream(), Protocol.URB_HEADER_SIZE),
            )
            assertEquals(Protocol.RET_UNLINK, reply.int)
            assertEquals(50, reply.int)
        }
    }
}
