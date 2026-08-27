package io.openusbip.server.mtp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP data encoding.
 *
 * Two things here bite reliably if you get them wrong:
 *
 *  - Everything is LITTLE-endian, the opposite of the USB/IP headers that carry
 *    it. Same trap as the mass-storage path.
 *  - A PTP string is not a C string. It is one byte holding the number of
 *    UTF-16 code units *including* the NUL terminator, then that many UTF-16LE
 *    units. An empty string is a single 0 byte with no terminator at all --
 *    encode it as `0x01 0x00 0x00` and clients mis-parse everything after it.
 */
object PtpCodec {

    fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun reader(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // --- writing ----------------------------------------------------------

    /** Grows as needed; PTP payload sizes are rarely known up front. */
    class Writer(initial: Int = 256) {
        private var buf = buffer(initial)

        private fun ensure(extra: Int) {
            if (buf.remaining() >= extra) return
            val bigger = buffer(maxOf(buf.capacity() * 2, buf.position() + extra))
            buf.flip()
            bigger.put(buf)
            buf = bigger
        }

        fun u8(value: Int): Writer { ensure(1); buf.put((value and 0xFF).toByte()); return this }

        fun u16(value: Int): Writer { ensure(2); buf.putShort((value and 0xFFFF).toShort()); return this }

        fun u32(value: Int): Writer { ensure(4); buf.putInt(value); return this }

        fun u64(value: Long): Writer { ensure(8); buf.putLong(value); return this }

        /** A 128-bit value, used for PersistentUID. */
        fun u128(low: Long, high: Long): Writer {
            ensure(16); buf.putLong(low); buf.putLong(high); return this
        }

        fun bytes(value: ByteArray): Writer { ensure(value.size); buf.put(value); return this }

        /**
         * PTP string: length in UTF-16 units including the terminator, then
         * UTF-16LE. An empty string is the single byte 0.
         */
        fun string(value: String?): Writer {
            if (value.isNullOrEmpty()) return u8(0)
            val chars = value.toCharArray()
            u8(chars.size + 1)
            ensure(chars.size * 2 + 2)
            chars.forEach { buf.putShort(it.code.toShort()) }
            buf.putShort(0)
            return this
        }

        /** u32 count followed by that many u16 elements. */
        fun u16Array(values: IntArray): Writer {
            u32(values.size)
            values.forEach { u16(it) }
            return this
        }

        /** u32 count followed by that many u32 elements. */
        fun u32Array(values: IntArray): Writer {
            u32(values.size)
            values.forEach { u32(it) }
            return this
        }

        fun size(): Int = buf.position()

        fun toByteArray(): ByteArray = buf.array().copyOf(buf.position())
    }

    // --- reading ----------------------------------------------------------

    class Reader(bytes: ByteArray) {
        private val buf = reader(bytes)

        val remaining: Int get() = buf.remaining()

        fun u8(): Int = buf.get().toInt() and 0xFF

        fun u16(): Int = buf.short.toInt() and 0xFFFF

        fun u32(): Int = buf.int

        fun u64(): Long = buf.long

        fun bytes(count: Int): ByteArray = ByteArray(count).also { buf.get(it) }

        /** Mirror of [Writer.string], including the empty-string special case. */
        fun string(): String {
            if (buf.remaining() < 1) return ""
            val units = u8()
            if (units == 0) return ""
            val sb = StringBuilder(units)
            for (i in 0 until units) {
                if (buf.remaining() < 2) break
                val ch = buf.short.toInt() and 0xFFFF
                // The final unit is the NUL terminator; do not append it.
                if (ch != 0) sb.append(ch.toChar())
            }
            return sb.toString()
        }

        fun u32Array(): IntArray {
            val count = u32()
            // Guard against a corrupt count trying to allocate gigabytes.
            val safe = count.coerceIn(0, buf.remaining() / 4)
            return IntArray(safe) { u32() }
        }

        fun skip(count: Int) {
            buf.position(buf.position() + count)
        }
    }

    /**
     * PTP timestamps are strings of the form "YYYYMMDDThhmmss".
     *
     * Sub-second precision and the trailing "Z" or offset are optional and
     * clients tolerate their absence, so this emits the plain local form that
     * Android itself uses.
     */
    fun formatTimestamp(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return "%04d%02d%02dT%02d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }
}
