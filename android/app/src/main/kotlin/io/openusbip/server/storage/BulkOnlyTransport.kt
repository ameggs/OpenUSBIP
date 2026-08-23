package io.openusbip.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bulk-Only Transport: the USB mass-storage wrapper around SCSI.
 *
 * One command is three or four bulk transfers:
 *
 *   1. host -> device  CBW, 31 bytes, on the bulk OUT endpoint
 *   2. the data phase, in whichever direction the CBW asked for (may be absent,
 *      and may be split across several URBs)
 *   3. device -> host  CSW, 13 bytes, on the bulk IN endpoint
 *
 * So this is a state machine, not a request/response function: a CSW read has
 * to know what the preceding CBW asked for. All CBW and CSW fields are
 * little-endian -- USB's convention, the opposite of USB/IP's own headers.
 */
class BulkOnlyTransport(private val scsi: ScsiTarget) {

    enum class Phase {
        IDLE,
        DATA_IN,

        /**
         * The CBW asked for data but the command failed, so there is none.
         *
         * A real device stalls the bulk IN endpoint here rather than handing
         * back a CSW: the host is asking for data and would parse 13 bytes of
         * CSW as payload. usb-storage responds to the stall by clearing the
         * halt and reading the status, which is what the transition to STATUS
         * below supports.
         */
        DATA_IN_ERROR,
        DATA_OUT,
        STATUS,
    }

    /** The parsed Command Block Wrapper. */
    data class Cbw(
        val tag: Int,
        val dataTransferLength: Int,
        val isDeviceToHost: Boolean,
        val lun: Int,
        val cdb: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Cbw) return false
            return tag == other.tag &&
                dataTransferLength == other.dataTransferLength &&
                isDeviceToHost == other.isDeviceToHost &&
                lun == other.lun && cdb.contentEquals(other.cdb)
        }

        override fun hashCode(): Int {
            var r = tag
            r = 31 * r + dataTransferLength
            r = 31 * r + isDeviceToHost.hashCode()
            r = 31 * r + lun
            r = 31 * r + cdb.contentHashCode()
            return r
        }
    }

    var phase: Phase = Phase.IDLE
        private set

    private var cbw: Cbw? = null

    /** Data still owed to the host in a DATA_IN phase. */
    private var pendingIn: ByteArray = ByteArray(0)
    private var pendingInOffset = 0

    /** Data accumulated from the host during a DATA_OUT phase. */
    private var collectedOut = ByteArray(0)

    private var status = STATUS_GOOD
    private var transferred = 0

    /**
     * Handle a write to the bulk OUT endpoint.
     *
     * Returns true if the bytes were consumed. In IDLE this is a CBW; in
     * DATA_OUT it is payload.
     */
    fun handleOut(data: ByteArray): Boolean {
        return when (phase) {
            Phase.IDLE -> acceptCbw(data)
            Phase.DATA_OUT -> acceptOutData(data)
            else -> false      // unexpected; caller stalls
        }
    }

    /**
     * Handle a read from the bulk IN endpoint, for at most [requested] bytes.
     *
     * Returns the bytes to hand back, or null if the read is unexpected in the
     * current phase and should be stalled.
     */
    fun handleIn(requested: Int): ByteArray? {
        return when (phase) {
            Phase.DATA_IN -> takeInData(requested)

            // Stall once, then let the host come back for the CSW.
            Phase.DATA_IN_ERROR -> {
                phase = Phase.STATUS
                null
            }

            Phase.STATUS -> takeCsw()
            // A host that reads while we expect a CBW or OUT data gets a
            // zero-length reply rather than a stall: usb-storage sometimes
            // probes, and stalling there triggers a reset cycle.
            else -> ByteArray(0)
        }
    }

    // --- phase 1: CBW -----------------------------------------------------

    private fun acceptCbw(data: ByteArray): Boolean {
        if (data.size < CBW_SIZE) return false

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != CBW_SIGNATURE) return false

        val tag = buf.int
        val length = buf.int
        val flags = buf.get().toInt() and 0xFF
        val lun = buf.get().toInt() and 0x0F
        val cdbLength = (buf.get().toInt() and 0x1F).coerceIn(0, 16)
        val cdb = ByteArray(16)
        buf.get(cdb)

        val parsed = Cbw(
            tag = tag,
            dataTransferLength = length,
            isDeviceToHost = (flags and 0x80) != 0,
            lun = lun,
            cdb = cdb.copyOf(cdbLength),
        )
        cbw = parsed
        transferred = 0
        status = STATUS_GOOD

        // A device with one LUN must fail commands aimed elsewhere.
        if (lun != 0) {
            status = STATUS_FAILED
            phase = Phase.STATUS
            return true
        }

        return when {
            // No data phase: run it now and go straight to status.
            length == 0 -> {
                runScsi(null)
                phase = Phase.STATUS
                true
            }

            // Device-to-host: run it now so the payload is ready to read.
            parsed.isDeviceToHost -> {
                runScsi(null)
                phase = when {
                    pendingIn.isNotEmpty() -> Phase.DATA_IN
                    // Failed with nothing to return: stall the data phase.
                    status != STATUS_GOOD -> Phase.DATA_IN_ERROR
                    // Succeeded with genuinely no data; residue covers it.
                    else -> Phase.STATUS
                }
                true
            }

            // Host-to-device: collect the payload first, then run it.
            else -> {
                collectedOut = ByteArray(0)
                phase = Phase.DATA_OUT
                true
            }
        }
    }

    // --- phase 2: data ----------------------------------------------------

    private fun takeInData(requested: Int): ByteArray {
        val remaining = pendingIn.size - pendingInOffset
        val n = minOf(requested, remaining)
        val chunk = pendingIn.copyOfRange(pendingInOffset, pendingInOffset + n)
        pendingInOffset += n
        transferred += n

        // Once drained, the host's next IN read is the CSW.
        if (pendingInOffset >= pendingIn.size) phase = Phase.STATUS
        return chunk
    }

    private fun acceptOutData(data: ByteArray): Boolean {
        collectedOut += data
        val expected = cbw?.dataTransferLength ?: 0

        if (collectedOut.size >= expected) {
            transferred = expected
            runScsi(collectedOut.copyOf(expected))
            phase = Phase.STATUS
        }
        return true
    }

    // --- phase 3: CSW -----------------------------------------------------

    private fun takeCsw(): ByteArray {
        val current = cbw
        val expected = current?.dataTransferLength ?: 0
        val residue = (expected - transferred).coerceAtLeast(0)

        val buf = ByteBuffer.allocate(CSW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(CSW_SIGNATURE)
        buf.putInt(current?.tag ?: 0)
        buf.putInt(residue)
        buf.put(status.toByte())

        reset()
        return buf.array()
    }

    private fun runScsi(outData: ByteArray?) {
        val current = cbw ?: return
        val result = try {
            scsi.execute(current.cdb, outData)
        } catch (e: Exception) {
            ScsiTarget.Result.CHECK_CONDITION
        }

        status = if (result.status == ScsiTarget.STATUS_GOOD) STATUS_GOOD else STATUS_FAILED

        if (current.isDeviceToHost) {
            // Never offer more than the CBW said it would take.
            val data = result.data ?: ByteArray(0)
            pendingIn = if (data.size > current.dataTransferLength) {
                data.copyOf(current.dataTransferLength)
            } else {
                data
            }
            pendingInOffset = 0
        }
    }

    /** Abort any in-flight command, as a Bulk-Only mass storage reset does. */
    fun reset() {
        phase = Phase.IDLE
        cbw = null
        pendingIn = ByteArray(0)
        pendingInOffset = 0
        collectedOut = ByteArray(0)
        transferred = 0
        status = STATUS_GOOD
    }

    companion object {
        const val CBW_SIZE = 31
        const val CSW_SIZE = 13

        /** "USBC" and "USBS" as little-endian words. */
        const val CBW_SIGNATURE = 0x43425355
        const val CSW_SIGNATURE = 0x53425355

        const val STATUS_GOOD = 0x00
        const val STATUS_FAILED = 0x01
        const val STATUS_PHASE_ERROR = 0x02

        /** Build a CBW, for tests and for anything driving this directly. */
        fun buildCbw(
            tag: Int,
            dataTransferLength: Int,
            deviceToHost: Boolean,
            cdb: ByteArray,
            lun: Int = 0,
        ): ByteArray {
            val buf = ByteBuffer.allocate(CBW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(CBW_SIGNATURE)
            buf.putInt(tag)
            buf.putInt(dataTransferLength)
            buf.put(if (deviceToHost) 0x80.toByte() else 0x00)
            buf.put(lun.toByte())
            buf.put(cdb.size.toByte())
            val padded = ByteArray(16)
            cdb.copyInto(padded, 0, 0, minOf(cdb.size, 16))
            buf.put(padded)
            return buf.array()
        }
    }
}
