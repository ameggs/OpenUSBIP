package io.openusbip.server.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Big-endian encoding helpers for the USB/IP wire format.
 *
 * Kotlin has no unsigned primitives below `UInt`, and the protocol mixes u8,
 * u16 and u32 freely. Everything here takes and returns `Int`, masking on the
 * way out, so callers never have to think about sign extension. u32 fields
 * whose value exceeds 2^31 (sequence numbers on a long-lived connection) are
 * carried as raw bits in a signed Int -- they are only ever echoed back, never
 * compared as magnitudes.
 */
object Wire {

    fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

    fun reader(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    // --- writing ---------------------------------------------------------

    fun ByteBuffer.putU8(value: Int): ByteBuffer = put((value and 0xFF).toByte())

    fun ByteBuffer.putU16(value: Int): ByteBuffer = putShort((value and 0xFFFF).toShort())

    fun ByteBuffer.putU32(value: Int): ByteBuffer = putInt(value)

    /**
     * Write a NUL-padded fixed-width string, as the protocol's char arrays
     * expect. Oversized input is truncated to leave room for a terminator
     * rather than overflowing into the next field.
     */
    fun ByteBuffer.putFixedString(value: String, width: Int): ByteBuffer {
        val raw = value.toByteArray(Charsets.US_ASCII)
        val len = minOf(raw.size, width - 1)
        put(raw, 0, len)
        repeat(width - len) { put(0) }
        return this
    }

    fun ByteBuffer.putPadding(count: Int): ByteBuffer {
        repeat(count) { put(0) }
        return this
    }

    // --- reading ---------------------------------------------------------

    fun ByteBuffer.getU8(): Int = get().toInt() and 0xFF

    fun ByteBuffer.getU16(): Int = getShort().toInt() and 0xFFFF

    fun ByteBuffer.getU32(): Int = getInt()

    fun ByteBuffer.getFixedString(width: Int): String {
        val raw = ByteArray(width)
        get(raw)
        val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it < 0) width else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }

    fun ByteBuffer.skip(count: Int): ByteBuffer {
        position(position() + count)
        return this
    }

    // --- stream helpers --------------------------------------------------

    /**
     * Read exactly [count] bytes or throw.
     *
     * A short read on a USB/IP socket means the peer went away mid-message;
     * carrying on with a partial header would silently desynchronise the
     * stream, so this fails loudly instead.
     */
    fun readExactly(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n < 0) {
                throw EOFException("stream closed after $read of $count bytes")
            }
            read += n
        }
        return buf
    }

    fun readExactly(input: DataInputStream, count: Int): ByteArray =
        readExactly(input as InputStream, count)
}
