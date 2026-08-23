package io.openusbip.server.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact tests for the USB/IP wire format.
 *
 * These matter more than usual: a single wrong offset does not throw, it
 * silently corrupts every transfer, and the failure surfaces as unexplained
 * USB errors on a client machine. Field positions are asserted against the
 * layout of `struct usbip_usb_device` and friends rather than against
 * whatever this implementation happens to produce.
 */
class ProtocolTest {

    private fun sampleDevice(
        busid: String = "1-1",
        interfaces: List<UsbipInterface> = emptyList(),
    ) = UsbipDevice(
        path = "/sys/devices/platform/usb/1-1",
        busid = busid,
        busnum = 1,
        devnum = 4,
        speed = Protocol.SPEED_HIGH,
        idVendor = 0x0781,
        idProduct = 0x5575,
        bcdDevice = 0x0100,
        deviceClass = 0x00,
        deviceSubClass = 0x00,
        deviceProtocol = 0x00,
        configurationValue = 1,
        numConfigurations = 1,
        numInterfaces = 1,
        interfaces = interfaces,
    )

    // --- structure sizes -------------------------------------------------

    @Test
    fun `struct sizes match the kernel layout`() {
        assertEquals(8, Protocol.OP_HEADER_SIZE)
        assertEquals(312, Protocol.USB_DEVICE_SIZE)
        assertEquals(4, Protocol.USB_INTERFACE_SIZE)
        assertEquals(20, Protocol.BASIC_HEADER_SIZE)
        assertEquals(48, Protocol.URB_HEADER_SIZE)
    }

    @Test
    fun `encoded device is exactly 312 bytes`() {
        assertEquals(Protocol.USB_DEVICE_SIZE, sampleDevice().encode().size)
    }

    // --- byte order ------------------------------------------------------

    @Test
    fun `op header is big endian`() {
        val bytes = OpHeader(Protocol.VERSION, Protocol.OP_REP_DEVLIST, Protocol.ST_OK).encode()

        // version 0x0111 -> 0x01 0x11, not 0x11 0x01
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
        assertEquals(0x11, bytes[1].toInt() and 0xFF)
        // code 0x0005
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(0x05, bytes[3].toInt() and 0xFF)
        // status 0
        assertEquals(0, bytes[4].toInt() or bytes[5].toInt() or bytes[6].toInt() or bytes[7].toInt())
    }

    @Test
    fun `request opcodes have the high bit set`() {
        assertEquals(0x8005, Protocol.OP_REQ_DEVLIST)
        assertEquals(0x8003, Protocol.OP_REQ_IMPORT)
        // Replies clear it.
        assertEquals(0x0005, Protocol.OP_REP_DEVLIST)
        assertEquals(0x0003, Protocol.OP_REP_IMPORT)
    }

    // --- device field offsets --------------------------------------------

    @Test
    fun `device fields land at the documented offsets`() {
        val bytes = sampleDevice().encode()
        val buf = Wire.reader(bytes)

        // path 0..255, busid 256..287, busnum 288, devnum 292, speed 296,
        // idVendor 300, idProduct 302, bcdDevice 304, then six u8s.
        buf.position(288)
        assertEquals(1, buf.int)          // busnum
        assertEquals(4, buf.int)          // devnum
        assertEquals(Protocol.SPEED_HIGH, buf.int)

        assertEquals(0x0781, buf.short.toInt() and 0xFFFF)   // idVendor @300
        assertEquals(0x5575, buf.short.toInt() and 0xFFFF)   // idProduct @302
        assertEquals(0x0100, buf.short.toInt() and 0xFFFF)   // bcdDevice @304

        assertEquals(0x00, bytes[306].toInt() and 0xFF)      // bDeviceClass
        assertEquals(0x00, bytes[307].toInt() and 0xFF)      // bDeviceSubClass
        assertEquals(0x00, bytes[308].toInt() and 0xFF)      // bDeviceProtocol
        assertEquals(1, bytes[309].toInt() and 0xFF)         // bConfigurationValue
        assertEquals(1, bytes[310].toInt() and 0xFF)         // bNumConfigurations
        assertEquals(1, bytes[311].toInt() and 0xFF)         // bNumInterfaces
    }

    @Test
    fun `device round trips`() {
        val original = sampleDevice()
        val decoded = UsbipDevice.decodeFrom(Wire.reader(original.encode()))

        assertEquals(original.path, decoded.path)
        assertEquals(original.busid, decoded.busid)
        assertEquals(original.busnum, decoded.busnum)
        assertEquals(original.devnum, decoded.devnum)
        assertEquals(original.idVendor, decoded.idVendor)
        assertEquals(original.idProduct, decoded.idProduct)
        assertEquals(original.numInterfaces, decoded.numInterfaces)
    }

    // --- fixed-width strings ---------------------------------------------

    @Test
    fun `busid is NUL padded to its full width`() {
        val bytes = sampleDevice(busid = "1-1").encode()
        assertEquals('1'.code, bytes[256].toInt())
        assertEquals('-'.code, bytes[257].toInt())
        assertEquals('1'.code, bytes[258].toInt())
        // everything after the value must be zero, through to byte 287
        for (i in 259..287) {
            assertEquals("byte $i should be NUL", 0, bytes[i].toInt())
        }
    }

    @Test
    fun `an oversized string is truncated and still NUL terminated`() {
        val long = "x".repeat(100)
        val bytes = sampleDevice(busid = long).encode()

        // 31 characters plus a terminator, never spilling into busnum at 288.
        assertEquals('x'.code, bytes[286].toInt())
        assertEquals(0, bytes[287].toInt())
        assertEquals(1, Wire.reader(bytes).also { it.position(288) }.int)
    }

    // --- DEVLIST ---------------------------------------------------------

    @Test
    fun `empty devlist is header plus a zero count`() {
        val bytes = OpReply.devlist(emptyList())
        assertEquals(Protocol.OP_HEADER_SIZE + 4, bytes.size)

        val buf = Wire.reader(bytes)
        buf.position(Protocol.OP_HEADER_SIZE)
        assertEquals(0, buf.int)
    }

    @Test
    fun `devlist reports the device count and includes each device`() {
        val bytes = OpReply.devlist(listOf(sampleDevice("1-1"), sampleDevice("1-2")))

        assertEquals(Protocol.OP_HEADER_SIZE + 4 + 2 * Protocol.USB_DEVICE_SIZE, bytes.size)

        val buf = Wire.reader(bytes)
        assertEquals(Protocol.VERSION, buf.short.toInt() and 0xFFFF)
        assertEquals(Protocol.OP_REP_DEVLIST, buf.short.toInt() and 0xFFFF)
        assertEquals(Protocol.ST_OK, buf.int)
        assertEquals(2, buf.int)

        assertEquals("1-1", UsbipDevice.decodeFrom(buf).busid)
        assertEquals("1-2", UsbipDevice.decodeFrom(buf).busid)
    }

    @Test
    fun `devlist appends interface descriptors after each device`() {
        val ifaces = listOf(
            UsbipInterface(0x08, 0x06, 0x50),   // mass storage / SCSI / BBB
            UsbipInterface(0x03, 0x01, 0x02),   // HID / boot / mouse
        )
        val bytes = OpReply.devlist(listOf(sampleDevice(interfaces = ifaces)))

        assertEquals(
            Protocol.OP_HEADER_SIZE + 4 + Protocol.USB_DEVICE_SIZE + 2 * Protocol.USB_INTERFACE_SIZE,
            bytes.size,
        )

        val buf = Wire.reader(bytes)
        buf.position(Protocol.OP_HEADER_SIZE + 4 + Protocol.USB_DEVICE_SIZE)
        assertEquals(ifaces[0], UsbipInterface.decodeFrom(buf))
        assertEquals(ifaces[1], UsbipInterface.decodeFrom(buf))
    }

    // --- IMPORT ----------------------------------------------------------

    @Test
    fun `import success carries the device record`() {
        val bytes = OpReply.importSuccess(sampleDevice())
        assertEquals(Protocol.OP_HEADER_SIZE + Protocol.USB_DEVICE_SIZE, bytes.size)

        val header = OpHeader.decode(bytes.copyOfRange(0, Protocol.OP_HEADER_SIZE))
        assertEquals(Protocol.OP_REP_IMPORT, header.code)
        assertEquals(Protocol.ST_OK, header.status)
    }

    @Test
    fun `import failure is the bare header with no device record`() {
        // The client only reads a device record when status is OK. Padding a
        // failure with 312 zero bytes desynchronises it.
        val bytes = OpReply.importFailure()
        assertEquals(Protocol.OP_HEADER_SIZE, bytes.size)

        val header = OpHeader.decode(bytes)
        assertEquals(Protocol.OP_REP_IMPORT, header.code)
        assertNotEquals(Protocol.ST_OK, header.status)
    }

    // --- devid packing ---------------------------------------------------

    @Test
    fun `devid packs bus and device number and round trips`() {
        val devid = Protocol.devid(busnum = 1, devnum = 4)
        assertEquals(0x00010004, devid)
        assertEquals(1, Protocol.busnumOf(devid))
        assertEquals(4, Protocol.devnumOf(devid))
    }

    @Test
    fun `devid survives a high bus number`() {
        val devid = Protocol.devid(busnum = 0x1234, devnum = 0x5678)
        assertEquals(0x1234, Protocol.busnumOf(devid))
        assertEquals(0x5678, Protocol.devnumOf(devid))
    }

    // --- CMD_SUBMIT ------------------------------------------------------

    private fun cmdSubmitBytes(
        seqnum: Int = 1,
        devid: Int = 0x00010004,
        direction: Int = Protocol.DIR_IN,
        ep: Int = 0,
        transferFlags: Int = 0,
        length: Int = 18,
        packets: Int = 0,
        interval: Int = 0,
        setup: ByteArray = byteArrayOf(0x80.toByte(), 0x06, 0x00, 0x01, 0x00, 0x00, 0x12, 0x00),
    ): ByteArray {
        val buf = Wire.buffer(Protocol.URB_HEADER_SIZE)
        buf.putInt(Protocol.CMD_SUBMIT)
        buf.putInt(seqnum)
        buf.putInt(devid)
        buf.putInt(direction)
        buf.putInt(ep)
        buf.putInt(transferFlags)
        buf.putInt(length)
        buf.putInt(0)          // start_frame
        buf.putInt(packets)
        buf.putInt(interval)
        buf.put(setup)
        return buf.array()
    }

    @Test
    fun `cmd submit decodes every field`() {
        val cmd = CmdSubmit.decode(cmdSubmitBytes(seqnum = 7, ep = 0, length = 18))

        assertEquals(Protocol.CMD_SUBMIT, cmd.header.command)
        assertEquals(7, cmd.header.seqnum)
        assertEquals(0x00010004, cmd.header.devid)
        assertEquals(Protocol.DIR_IN, cmd.header.direction)
        assertTrue(cmd.header.isIn)
        assertEquals(0, cmd.header.ep)
        assertEquals(18, cmd.transferBufferLength)
        assertTrue(cmd.isControl)
        // GET_DESCRIPTOR(device) setup packet
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x06, 0x00, 0x01, 0x00, 0x00, 0x12, 0x00),
            cmd.setup,
        )
    }

    @Test
    fun `a short header is rejected rather than read past the end`() {
        try {
            CmdSubmit.decode(ByteArray(20))
            throw AssertionError("expected a failure on a truncated header")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("48"))
        }
    }

    @Test
    fun `endpoint zero is recognised as control`() {
        assertTrue(CmdSubmit.decode(cmdSubmitBytes(ep = 0)).isControl)
        assertTrue(!CmdSubmit.decode(cmdSubmitBytes(ep = 1)).isControl)
    }

    @Test
    fun `isochronous urbs are detected so they can be refused`() {
        // Android's USB API has no isochronous support, so these must be
        // identified and answered with an error rather than mishandled.
        assertTrue(CmdSubmit.decode(cmdSubmitBytes(packets = 8)).isIsochronous)
        assertTrue(!CmdSubmit.decode(cmdSubmitBytes(packets = 0)).isIsochronous)
        assertTrue(!CmdSubmit.decode(cmdSubmitBytes(packets = -1)).isIsochronous)
    }

    // --- RET_SUBMIT ------------------------------------------------------

    @Test
    fun `ret submit header is 48 bytes and echoes the request identity`() {
        val cmd = CmdSubmit.decode(cmdSubmitBytes(seqnum = 42, ep = 3, direction = Protocol.DIR_IN))
        val bytes = RetSubmit.success(cmd, actualLength = 512).encode()

        assertEquals(Protocol.URB_HEADER_SIZE, bytes.size)

        val buf = Wire.reader(bytes)
        assertEquals(Protocol.RET_SUBMIT, buf.int)
        assertEquals(42, buf.int)                 // seqnum echoed
        assertEquals(0x00010004, buf.int)         // devid echoed
        assertEquals(Protocol.DIR_IN, buf.int)
        assertEquals(3, buf.int)                  // ep echoed
        assertEquals(0, buf.int)                  // status: success
        assertEquals(512, buf.int)                // actual_length
    }

    @Test
    fun `ret submit error reports status and no payload`() {
        val cmd = CmdSubmit.decode(cmdSubmitBytes(seqnum = 9))
        val ret = RetSubmit.error(cmd, status = -32)   // -EPIPE, a stall

        assertEquals(9, ret.seqnum)
        assertEquals(-32, ret.status)
        assertEquals(0, ret.actualLength)

        val buf = Wire.reader(ret.encode())
        buf.position(20)
        assertEquals(-32, buf.int)
    }

    // --- UNLINK ----------------------------------------------------------

    @Test
    fun `cmd unlink carries the sequence number being cancelled`() {
        val buf = Wire.buffer(Protocol.URB_HEADER_SIZE)
        buf.putInt(Protocol.CMD_UNLINK)
        buf.putInt(100)          // this message's seqnum
        buf.putInt(0x00010004)
        buf.putInt(Protocol.DIR_OUT)
        buf.putInt(0)
        buf.putInt(55)           // the seqnum to unlink
        val cmd = CmdUnlink.decode(buf.array())

        assertEquals(Protocol.CMD_UNLINK, cmd.header.command)
        assertEquals(100, cmd.header.seqnum)
        assertEquals(55, cmd.unlinkSeqnum)
    }

    @Test
    fun `ret unlink is 48 bytes`() {
        val bytes = RetUnlink(seqnum = 100, devid = 0x00010004, status = 0).encode()
        assertEquals(Protocol.URB_HEADER_SIZE, bytes.size)

        val buf = Wire.reader(bytes)
        assertEquals(Protocol.RET_UNLINK, buf.int)
        assertEquals(100, buf.int)
    }
}
