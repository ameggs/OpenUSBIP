package io.openusbip.server.net

import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.UsbipDevice

/**
 * The outcome of servicing one URB.
 *
 * [status] follows the kernel's URB convention: 0 for success, a negative
 * errno otherwise (-EPIPE for a stall, -EPROTO for a malformed request). The
 * client surfaces these to whatever driver issued the transfer, so returning
 * a sensible errno matters -- a driver can retry a stall but not a lie.
 */
data class UrbResult(
    val status: Int,
    val data: ByteArray? = null,
) {
    /** Bytes actually moved. For IN this is the payload length. */
    val actualLength: Int get() = data?.size ?: 0

    companion object {
        val OK = UrbResult(0)

        fun ok(data: ByteArray) = UrbResult(0, data)

        /** -EPIPE: endpoint stalled. The standard "I don't support that". */
        fun stall() = UrbResult(-32)

        /** -EPROTO: the request itself made no sense. */
        fun protocolError() = UrbResult(-71)

        /** -ENODEV: the device is gone. */
        fun noDevice() = UrbResult(-19)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UrbResult) return false
        return status == other.status && (data?.contentEquals(other.data) ?: (other.data == null))
    }

    override fun hashCode(): Int = 31 * status + (data?.contentHashCode() ?: 0)
}

/**
 * Something that can be exported over USB/IP.
 *
 * Two implementations exist: one forwards URBs to a real device attached to
 * the phone's OTG port, the other synthesises a USB mass-storage device backed
 * by files on the phone. The server does not care which it is talking to,
 * which is the whole point of this interface -- internal storage and real
 * peripherals reach a client through exactly the same path.
 */
interface UsbBackend {

    /** What DEVLIST and IMPORT advertise for this device. */
    val device: UsbipDevice

    /** Stable identity used to claim and release, matching [device].busid. */
    val busid: String get() = device.busid

    /**
     * Service one URB.
     *
     * [outData] carries the payload for an OUT transfer and is null for IN.
     * Implementations must not block indefinitely: a wedged backend stalls the
     * client's whole USB stack, since URBs on one device are serialised here.
     */
    fun submit(cmd: CmdSubmit, outData: ByteArray?): UrbResult

    /** Called when a client detaches or the connection drops. */
    fun close() {}

    /** Called when a client successfully imports this device. */
    fun open() {}
}
