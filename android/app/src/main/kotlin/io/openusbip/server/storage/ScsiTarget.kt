package io.openusbip.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The SCSI command set a USB mass-storage device must answer.
 *
 * Scope is set by what Linux's `usb-storage` driver actually issues while
 * bringing a stick up and mounting it: INQUIRY, TEST UNIT READY, REQUEST
 * SENSE, READ CAPACITY, MODE SENSE, READ(10) and WRITE(10), plus a handful of
 * no-ops it expects to succeed. Anything else is answered with ILLEGAL REQUEST
 * / INVALID COMMAND OPERATION CODE, which is what a real device does and which
 * the driver handles cleanly.
 *
 * All multi-byte SCSI fields are big-endian.
 */
class ScsiTarget(
    private val block: BlockDevice,
    private val vendor: String = "OpnUSBIP",
    private val product: String = "Virtual Disk",
    private val revision: String = "0001",
) {

    /** Outcome of one SCSI command. */
    data class Result(
        val status: Int,
        val data: ByteArray? = null,
    ) {
        companion object {
            val GOOD = Result(STATUS_GOOD)
            fun good(data: ByteArray) = Result(STATUS_GOOD, data)
            val CHECK_CONDITION = Result(STATUS_CHECK_CONDITION)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return status == other.status &&
                (data?.contentEquals(other.data) ?: (other.data == null))
        }

        override fun hashCode(): Int = 31 * status + (data?.contentHashCode() ?: 0)
    }

    // Current sense, returned by the next REQUEST SENSE. A real target keeps
    // exactly one pending sense per LUN and clears it once read.
    private var senseKey = SENSE_NO_SENSE
    private var senseAsc = 0
    private var senseAscq = 0

    /**
     * Execute one command descriptor block.
     *
     * [outData] is the data-out payload for commands that carry one (WRITE),
     * and null otherwise.
     */
    fun execute(cdb: ByteArray, outData: ByteArray?): Result {
        if (cdb.isEmpty()) return fail(SENSE_ILLEGAL_REQUEST, ASC_INVALID_COMMAND)

        return when (cdb[0].toInt() and 0xFF) {
            OP_TEST_UNIT_READY -> Result.GOOD
            OP_INQUIRY -> inquiry(cdb)
            OP_REQUEST_SENSE -> requestSense(cdb)
            OP_READ_CAPACITY_10 -> readCapacity10()
            OP_SERVICE_ACTION_IN_16 -> serviceActionIn16(cdb)
            OP_MODE_SENSE_6 -> modeSense6(cdb)
            OP_MODE_SENSE_10 -> modeSense10(cdb)
            OP_READ_10 -> read10(cdb)
            OP_WRITE_10 -> write10(cdb, outData)
            OP_PREVENT_ALLOW_REMOVAL -> Result.GOOD
            OP_START_STOP_UNIT -> Result.GOOD
            OP_SYNCHRONIZE_CACHE -> { block.flush(); Result.GOOD }
            OP_VERIFY_10 -> Result.GOOD
            else -> fail(SENSE_ILLEGAL_REQUEST, ASC_INVALID_COMMAND)
        }
    }

    /** How many bytes a command will produce, for BOT length reconciliation. */
    fun expectedInLength(cdb: ByteArray): Int {
        if (cdb.isEmpty()) return 0
        return when (cdb[0].toInt() and 0xFF) {
            OP_INQUIRY -> be16(cdb, 3)
            OP_REQUEST_SENSE -> cdb[4].toInt() and 0xFF
            OP_READ_CAPACITY_10 -> 8
            OP_SERVICE_ACTION_IN_16 -> be32(cdb, 10)
            OP_MODE_SENSE_6 -> cdb[4].toInt() and 0xFF
            OP_MODE_SENSE_10 -> be16(cdb, 7)
            OP_READ_10 -> be16(cdb, 7) * block.blockSize
            else -> 0
        }
    }

    // --- individual commands ---------------------------------------------

    private fun inquiry(cdb: ByteArray): Result {
        // EVPD (vital product data) pages are optional; refusing them is
        // legitimate and the driver falls back to standard inquiry data.
        if ((cdb[1].toInt() and 0x01) != 0) {
            return fail(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB)
        }

        val allocation = be16(cdb, 3)
        val data = ByteArray(36)
        data[0] = 0x00                     // peripheral device type: direct access block
        data[1] = 0x80.toByte()            // RMB: removable medium
        data[2] = 0x06                     // SPC-4
        data[3] = 0x02                     // response data format 2
        data[4] = 31                       // additional length (36 - 5)
        putAscii(data, 8, vendor, 8)
        putAscii(data, 16, product, 16)
        putAscii(data, 32, revision, 4)

        return Result.good(data.copyOf(minOf(allocation.coerceAtLeast(0), data.size)))
    }

    private fun requestSense(cdb: ByteArray): Result {
        val allocation = (cdb[4].toInt() and 0xFF).coerceAtLeast(0)
        val data = ByteArray(18)
        data[0] = 0x70                     // fixed format, current error
        data[2] = (senseKey and 0x0F).toByte()
        data[7] = 10                       // additional sense length
        data[12] = senseAsc.toByte()
        data[13] = senseAscq.toByte()

        // Sense is consumed once reported.
        senseKey = SENSE_NO_SENSE
        senseAsc = 0
        senseAscq = 0

        return Result.good(data.copyOf(minOf(allocation, data.size)))
    }

    private fun readCapacity10(): Result {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        // READ CAPACITY(10) reports the address of the LAST block, not the
        // count. Off by one here and the client sees a volume one block too
        // large and errors on the final sector.
        val lastLba = (block.blockCount - 1).coerceAtLeast(0)
        buf.putInt(if (lastLba > 0xFFFFFFFFL) -1 else lastLba.toInt())
        buf.putInt(block.blockSize)
        return Result.good(buf.array())
    }

    private fun serviceActionIn16(cdb: ByteArray): Result {
        // Only READ CAPACITY(16) is supported off this opcode.
        if ((cdb[1].toInt() and 0x1F) != SERVICE_ACTION_READ_CAPACITY_16) {
            return fail(SENSE_ILLEGAL_REQUEST, ASC_INVALID_COMMAND)
        }
        val allocation = be32(cdb, 10)
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putLong((block.blockCount - 1).coerceAtLeast(0))
        buf.putInt(block.blockSize)
        return Result.good(buf.array().copyOf(minOf(allocation.coerceAtLeast(0), 32)))
    }

    private fun modeSense6(cdb: ByteArray): Result {
        val allocation = (cdb[4].toInt() and 0xFF).coerceAtLeast(0)
        val data = ByteArray(4)
        data[0] = 3                        // mode data length following
        data[1] = 0                        // medium type
        // Bit 7 of the device-specific parameter is the write-protect flag.
        data[2] = if (block.readOnly) 0x80.toByte() else 0x00
        data[3] = 0                        // block descriptor length
        return Result.good(data.copyOf(minOf(allocation, data.size)))
    }

    private fun modeSense10(cdb: ByteArray): Result {
        val allocation = be16(cdb, 7)
        val data = ByteArray(8)
        data[1] = 6
        data[3] = if (block.readOnly) 0x80.toByte() else 0x00
        return Result.good(data.copyOf(minOf(allocation.coerceAtLeast(0), data.size)))
    }

    private fun read10(cdb: ByteArray): Result {
        val lba = be32(cdb, 2).toLong() and 0xFFFFFFFFL
        val count = be16(cdb, 7)
        if (count == 0) return Result.GOOD          // legal no-op

        if (!inRange(lba, count)) {
            return fail(SENSE_ILLEGAL_REQUEST, ASC_LBA_OUT_OF_RANGE)
        }
        return Result.good(block.read(lba, count))
    }

    private fun write10(cdb: ByteArray, outData: ByteArray?): Result {
        if (block.readOnly) return fail(SENSE_DATA_PROTECT, ASC_WRITE_PROTECTED)

        val lba = be32(cdb, 2).toLong() and 0xFFFFFFFFL
        val count = be16(cdb, 7)
        if (count == 0) return Result.GOOD

        if (!inRange(lba, count)) {
            return fail(SENSE_ILLEGAL_REQUEST, ASC_LBA_OUT_OF_RANGE)
        }

        val expected = count * block.blockSize
        if (outData == null || outData.size < expected) {
            // Short data-out: the transfer was cut off. Reporting this rather
            // than writing a partial block avoids silently corrupting the
            // volume.
            return fail(SENSE_ABORTED_COMMAND, ASC_INVALID_FIELD_IN_CDB)
        }

        block.write(lba, outData.copyOf(expected))
        return Result.GOOD
    }

    // --- helpers ---------------------------------------------------------

    private fun inRange(lba: Long, count: Int): Boolean =
        lba >= 0 && count >= 0 && lba + count <= block.blockCount

    private fun fail(key: Int, asc: Int, ascq: Int = 0): Result {
        senseKey = key
        senseAsc = asc
        senseAscq = ascq
        return Result.CHECK_CONDITION
    }

    private fun putAscii(target: ByteArray, offset: Int, value: String, width: Int) {
        val padded = value.padEnd(width).substring(0, width)
        padded.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    companion object {
        const val STATUS_GOOD = 0x00
        const val STATUS_CHECK_CONDITION = 0x01

        // opcodes
        const val OP_TEST_UNIT_READY = 0x00
        const val OP_REQUEST_SENSE = 0x03
        const val OP_INQUIRY = 0x12
        const val OP_MODE_SENSE_6 = 0x1A
        const val OP_START_STOP_UNIT = 0x1B
        const val OP_PREVENT_ALLOW_REMOVAL = 0x1E
        const val OP_READ_CAPACITY_10 = 0x25
        const val OP_READ_10 = 0x28
        const val OP_WRITE_10 = 0x2A
        const val OP_VERIFY_10 = 0x2F
        const val OP_SYNCHRONIZE_CACHE = 0x35
        const val OP_MODE_SENSE_10 = 0x5A
        const val OP_SERVICE_ACTION_IN_16 = 0x9E

        const val SERVICE_ACTION_READ_CAPACITY_16 = 0x10

        // sense keys
        const val SENSE_NO_SENSE = 0x00
        const val SENSE_NOT_READY = 0x02
        const val SENSE_ILLEGAL_REQUEST = 0x05
        const val SENSE_DATA_PROTECT = 0x07
        const val SENSE_ABORTED_COMMAND = 0x0B

        // additional sense codes
        const val ASC_LBA_OUT_OF_RANGE = 0x21
        const val ASC_INVALID_COMMAND = 0x20
        const val ASC_INVALID_FIELD_IN_CDB = 0x24
        const val ASC_WRITE_PROTECTED = 0x27
        const val ASC_MEDIUM_NOT_PRESENT = 0x3A

        fun be16(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        fun be32(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)
    }
}
