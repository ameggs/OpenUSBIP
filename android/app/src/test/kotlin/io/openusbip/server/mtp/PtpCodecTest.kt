package io.openusbip.server.mtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTP encoding tests.
 *
 * The string format is the trap worth guarding: the length byte counts UTF-16
 * code units *including* the terminator, and an empty string is a lone zero
 * byte with no terminator. Getting either wrong shifts every field after it,
 * which surfaces as a client showing garbled filenames or no files at all.
 */
class PtpCodecTest {

    // --- endianness -------------------------------------------------------

    @Test
    fun `integers are little endian, unlike the usbip headers carrying them`() {
        val bytes = PtpCodec.Writer().u32(0x12345678).toByteArray()
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), bytes)
    }

    @Test
    fun `u16 is little endian`() {
        assertArrayEquals(byteArrayOf(0x01, 0x20), PtpCodec.Writer().u16(0x2001).toByteArray())
    }

    // --- strings ----------------------------------------------------------

    @Test
    fun `a string counts utf16 units including the terminator`() {
        val bytes = PtpCodec.Writer().string("Hi").toByteArray()

        // 3 units: 'H', 'i', NUL
        assertEquals(3, bytes[0].toInt())
        assertEquals('H'.code, bytes[1].toInt()); assertEquals(0, bytes[2].toInt())
        assertEquals('i'.code, bytes[3].toInt()); assertEquals(0, bytes[4].toInt())
        assertEquals(0, bytes[5].toInt()); assertEquals(0, bytes[6].toInt())
        assertEquals(7, bytes.size)
    }

    @Test
    fun `an empty string is a single zero byte with no terminator`() {
        // Encoding it as 0x01 0x00 0x00 is the classic mistake and desyncs
        // every field that follows.
        assertArrayEquals(byteArrayOf(0), PtpCodec.Writer().string("").toByteArray())
        assertArrayEquals(byteArrayOf(0), PtpCodec.Writer().string(null).toByteArray())
    }

    @Test
    fun `strings round trip`() {
        for (value in listOf("", "a", "hello.txt", "Photo 2026.jpg", "ünïcødé")) {
            val bytes = PtpCodec.Writer().string(value).toByteArray()
            assertEquals(value, PtpCodec.Reader(bytes).string())
        }
    }

    @Test
    fun `a reader does not run off the end of a truncated string`() {
        // Claims 10 units but supplies two: must return what it has, not throw.
        val bytes = byteArrayOf(10, 'a'.code.toByte(), 0, 'b'.code.toByte(), 0)
        assertEquals("ab", PtpCodec.Reader(bytes).string())
    }

    // --- arrays -----------------------------------------------------------

    @Test
    fun `u32 array is a count then elements`() {
        val bytes = PtpCodec.Writer().u32Array(intArrayOf(1, 2, 3)).toByteArray()
        assertEquals(4 + 12, bytes.size)
        val r = PtpCodec.Reader(bytes)
        assertEquals(3, r.u32())
        assertEquals(1, r.u32()); assertEquals(2, r.u32()); assertEquals(3, r.u32())
    }

    @Test
    fun `u32 array round trips`() {
        val values = intArrayOf(0x1001, 0x1002, 0x9805)
        val bytes = PtpCodec.Writer().u32Array(values).toByteArray()
        assertArrayEquals(values, PtpCodec.Reader(bytes).u32Array())
    }

    @Test
    fun `a corrupt array count cannot allocate unbounded memory`() {
        // Declares 2 billion elements with 8 bytes of data behind it.
        val bytes = PtpCodec.Writer().u32(Int.MAX_VALUE).u32(1).u32(2).toByteArray()
        assertEquals(2, PtpCodec.Reader(bytes).u32Array().size)
    }

    @Test
    fun `u16 array uses u32 count with u16 elements`() {
        val bytes = PtpCodec.Writer().u16Array(intArrayOf(0x3001, 0x3801)).toByteArray()
        assertEquals(4 + 4, bytes.size)
        val r = PtpCodec.Reader(bytes)
        assertEquals(2, r.u32())
        assertEquals(0x3001, r.u16())
        assertEquals(0x3801, r.u16())
    }

    // --- writer growth ----------------------------------------------------

    @Test
    fun `the writer grows past its initial capacity`() {
        val w = PtpCodec.Writer(4)
        repeat(500) { w.u32(it) }
        assertEquals(2000, w.toByteArray().size)
    }

    // --- timestamps -------------------------------------------------------

    @Test
    fun `timestamps use the PTP date form`() {
        val ts = PtpCodec.formatTimestamp(System.currentTimeMillis())
        assertEquals(15, ts.length)
        assertEquals('T', ts[8])
        assertTrue(ts.take(8).all { it.isDigit() })
        assertTrue(ts.drop(9).all { it.isDigit() })
    }

    // --- containers -------------------------------------------------------

    @Test
    fun `a response container encodes length, type, code and transaction`() {
        val bytes = PtpContainer.response(MtpConstants.RESP_OK, 7).encode()
        assertEquals(MtpConstants.CONTAINER_HEADER_SIZE, bytes.size)

        val r = PtpCodec.Reader(bytes)
        assertEquals(12, r.u32())
        assertEquals(MtpConstants.CONTAINER_RESPONSE, r.u16())
        assertEquals(MtpConstants.RESP_OK, r.u16())
        assertEquals(7, r.u32())
    }

    @Test
    fun `response parameters extend the declared length`() {
        val bytes = PtpContainer.response(MtpConstants.RESP_OK, 1, 0xAA, 0xBB).encode()
        assertEquals(12 + 8, bytes.size)
        assertEquals(20, PtpCodec.Reader(bytes).u32())
    }

    @Test
    fun `a command container round trips with its parameters`() {
        val original = PtpContainer(
            MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_GET_OBJECT_HANDLES, 42,
            listOf(MtpConstants.STORAGE_ID, 0, MtpConstants.HANDLE_ROOT),
        )
        val decoded = PtpContainer.decode(original.encode())!!

        assertEquals(MtpConstants.CONTAINER_COMMAND, decoded.type)
        assertEquals(MtpConstants.OP_GET_OBJECT_HANDLES, decoded.code)
        assertEquals(42, decoded.transactionId)
        assertEquals(3, decoded.parameters.size)
        assertEquals(MtpConstants.HANDLE_ROOT, decoded.param(2))
    }

    @Test
    fun `a command with no parameters is legal`() {
        val decoded = PtpContainer.decode(
            PtpContainer(MtpConstants.CONTAINER_COMMAND, MtpConstants.OP_GET_DEVICE_INFO, 1).encode(),
        )!!
        assertTrue(decoded.parameters.isEmpty())
        // absent parameters read as zero
        assertEquals(0, decoded.param(0))
    }

    @Test
    fun `a data container carries its payload verbatim`() {
        val payload = ByteArray(64) { it.toByte() }
        val decoded = PtpContainer.decode(
            PtpContainer.data(MtpConstants.OP_GET_OBJECT, 5, payload).encode(),
        )!!
        assertEquals(MtpConstants.CONTAINER_DATA, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `a runt container is rejected rather than misread`() {
        assertNull(PtpContainer.decode(ByteArray(0)))
        assertNull(PtpContainer.decode(ByteArray(11)))
    }

    @Test
    fun `a container claiming more than it carries is clamped`() {
        // Declared length 1000, actual 12 bytes: must not read past the end.
        val w = PtpCodec.Writer()
        w.u32(1000).u16(MtpConstants.CONTAINER_COMMAND).u16(MtpConstants.OP_OPEN_SESSION).u32(1)
        val decoded = PtpContainer.decode(w.toByteArray())!!
        assertEquals(MtpConstants.OP_OPEN_SESSION, decoded.code)
        assertTrue(decoded.parameters.isEmpty())
    }

    @Test
    fun `parameters are capped at the five the spec allows`() {
        val w = PtpCodec.Writer()
        val length = 12 + 8 * 4
        w.u32(length).u16(MtpConstants.CONTAINER_COMMAND).u16(0x1001).u32(1)
        repeat(8) { w.u32(it) }
        assertEquals(5, PtpContainer.decode(w.toByteArray())!!.parameters.size)
    }
}
