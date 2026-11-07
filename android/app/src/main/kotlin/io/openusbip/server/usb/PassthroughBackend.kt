package io.openusbip.server.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import io.openusbip.server.BuildConfig
import io.openusbip.server.net.UrbResult
import io.openusbip.server.net.UsbBackend
import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.UsbipDevice

/**
 * Forwards URBs to a real USB device plugged into the phone's OTG port.
 *
 * The counterpart to the synthesised devices: instead of inventing answers,
 * this hands each transfer to Android's USB host API and returns whatever the
 * hardware says. Same [UsbBackend] interface, so the server cannot tell the
 * difference.
 *
 * Two hard limits, both from Android rather than from this code:
 *
 *  - **No isochronous transfers.** UsbDeviceConnection offers control, bulk and
 *    interrupt only, so audio interfaces and webcams cannot be passed through.
 *    The server refuses those URBs before they reach here.
 *  - **Interfaces must be claimed exclusively.** Anything Android has already
 *    bound (a keyboard, a mounted stick) has to be detached first, which
 *    claimInterface with force does.
 */
class PassthroughBackend(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    override val device: UsbipDevice,
) : UsbBackend {

    private var connection: UsbDeviceConnection? = null

    /**
     * The device's own descriptor blob: device descriptor followed by the
     * configuration descriptor and its children.
     *
     * Enumeration requests are answered from this rather than forwarded. The
     * phone's kernel has already enumerated and configured the device and owns
     * its address, so a userspace app cannot issue SET_ADDRESS or re-fetch
     * descriptors -- forwarding them fails, the client sees a broken device and
     * resets it in a loop.
     */
    private var rawDescriptors: ByteArray = ByteArray(0)
    private val claimed = mutableListOf<UsbInterface>()

    /** Endpoint lookup by (number, direction), built once on open. */
    private val endpoints = HashMap<Int, UsbEndpoint>()

    /** Which interface owns each endpoint, so a halt can be cleared on it. */
    private val owningInterface = HashMap<Int, UsbInterface>()

    override fun open() {
        if (connection != null) return

        val conn = usbManager.openDevice(usbDevice)
        if (conn == null) {
            Log.e(TAG, "could not open ${usbDevice.deviceName}; permission not granted?")
            return
        }
        connection = conn

        for (i in 0 until usbDevice.interfaceCount) {
            val iface = usbDevice.getInterface(i)
            // force=true detaches whatever kernel driver already holds it.
            // Without this a stick Android has mounted cannot be exported.
            if (conn.claimInterface(iface, true)) {
                claimed += iface
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val k = key(ep.endpointNumber, ep.direction)
                    endpoints[k] = ep
                    owningInterface[k] = iface
                }
            } else {
                Log.w(TAG, "could not claim interface $i")
            }
        }
        rawDescriptors = runCatching { conn.rawDescriptors ?: ByteArray(0) }.getOrDefault(ByteArray(0))
        Log.i(TAG, "endpoints: " + endpoints.values.joinToString {
            "0x%02x(type=%d,max=%d)".format(it.address, it.type, it.maxPacketSize)
        })
        Log.i(
            TAG,
            "opened ${usbDevice.deviceName}: ${claimed.size} interface(s), " +
                "${endpoints.size} endpoint(s), ${rawDescriptors.size} descriptor bytes",
        )
    }

    override fun close() {
        val conn = connection ?: return
        claimed.forEach { runCatching { conn.releaseInterface(it) } }
        claimed.clear()
        endpoints.clear()
        owningInterface.clear()
        runCatching { conn.close() }
        connection = null
        Log.i(TAG, "released ${usbDevice.deviceName}")
    }

    override fun submit(cmd: CmdSubmit, outData: ByteArray?): UrbResult {
        val conn = connection ?: return UrbResult.noDevice()

        return try {
            val result = if (cmd.isControl) controlTransfer(conn, cmd, outData)
            else dataTransfer(conn, cmd, outData)
            if (DEBUG) {
                val dir = if (cmd.header.isIn) "IN " else "OUT"
                val setup = if (cmd.isControl) cmd.setup.joinToString("") { "%02x".format(it) } else ""
                Log.d(TAG, "ep${cmd.header.ep} $dir len=${cmd.transferBufferLength} $setup -> " +
                    "status=${result.status} bytes=${result.actualLength}")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "transfer failed on ep${cmd.header.ep}", e)
            UrbResult.stall()
        }
    }

    // --- endpoint 0 -------------------------------------------------------

    private fun controlTransfer(
        conn: UsbDeviceConnection,
        cmd: CmdSubmit,
        outData: ByteArray?,
    ): UrbResult {
        val setup = cmd.setup
        if (setup.size < 8) return UrbResult.protocolError()

        val requestType = setup[0].toInt() and 0xFF
        val request = setup[1].toInt() and 0xFF
        val value = le16(setup, 2)
        val index = le16(setup, 4)
        val length = le16(setup, 6)

        val isIn = (requestType and 0x80) != 0

        // Endpoint halt recovery, which needs more than forwarding.
        if (requestType == RECIPIENT_ENDPOINT_OUT && request == REQ_CLEAR_FEATURE &&
            value == FEATURE_ENDPOINT_HALT
        ) {
            conn.controlTransfer(requestType, request, value, index, null, 0, TIMEOUT_MS)
            clearHalt(index)
            return UrbResult.OK
        }

        // Standard device requests are answered here, not on the wire. See
        // rawDescriptors for why.
        if ((requestType and 0x60) == 0x00) {
            localStandardRequest(request, value, length)?.let { return it }
        }

        val buffer = if (isIn) ByteArray(length) else (outData ?: ByteArray(0))

        val moved = conn.controlTransfer(
            requestType, request, value, index, buffer, buffer.size, TIMEOUT_MS,
        )
        // A negative return is a failure, not a byte count.
        if (moved < 0) return UrbResult.stall()

        return if (isIn) UrbResult.ok(buffer.copyOf(moved)) else UrbResult(0, ByteArray(moved))
    }

    /**
     * Answer the standard requests a host issues while enumerating.
     *
     * Returns null for anything better handled by the device itself.
     */
    private fun localStandardRequest(request: Int, value: Int, length: Int): UrbResult? =
        when (request) {
            REQ_GET_DESCRIPTOR -> {
                val type = (value shr 8) and 0xFF
                val index = value and 0xFF
                when (type) {
                    DESC_DEVICE -> deviceDescriptor()?.let { UrbResult.ok(clip(it, length)) }
                    DESC_CONFIGURATION -> configDescriptor()?.let { UrbResult.ok(clip(it, length)) }
                    // Strings live on the device and it will answer for them.
                    DESC_STRING -> null
                    else -> null
                }
            }

            // The device is already addressed and configured by Android; the
            // client only needs these acknowledged.
            REQ_SET_ADDRESS, REQ_SET_CONFIGURATION -> UrbResult.OK
            REQ_GET_CONFIGURATION -> UrbResult.ok(byteArrayOf(1))
            REQ_GET_STATUS -> UrbResult.ok(byteArrayOf(0, 0))
            else -> null
        }

    /**
     * Clear a halted endpoint.
     *
     * Forwarding CLEAR_FEATURE to the device is not enough on its own: the
     * phone's own kernel also tracks the halt, and while it thinks the endpoint
     * is stalled every transfer keeps failing with -EPIPE. Releasing and
     * re-claiming the interface is the only lever Android exposes that resets
     * that state, so a client's stall recovery actually recovers.
     */
    private fun clearHalt(endpointAddress: Int): Boolean {
        val conn = connection ?: return false
        val number = endpointAddress and 0x0F
        val direction = if (endpointAddress and 0x80 != 0) {
            UsbConstants.USB_DIR_IN
        } else {
            UsbConstants.USB_DIR_OUT
        }
        val iface = owningInterface[key(number, direction)] ?: return false

        return runCatching {
            conn.releaseInterface(iface)
            conn.claimInterface(iface, true)
        }.onFailure { Log.w(TAG, "clearHalt failed for ep$number", it) }
            .getOrDefault(false)
            .also { Log.i(TAG, "cleared halt on endpoint 0x%02x -> $it".format(endpointAddress)) }
    }

    private fun deviceDescriptor(): ByteArray? {
        if (rawDescriptors.size < 18) return null
        return rawDescriptors.copyOf(18)
    }

    /** The first configuration descriptor, using its own wTotalLength. */
    private fun configDescriptor(): ByteArray? {
        if (rawDescriptors.size < 18 + 9) return null
        val start = 18
        val total = (rawDescriptors[start + 2].toInt() and 0xFF) or
            ((rawDescriptors[start + 3].toInt() and 0xFF) shl 8)
        if (total <= 0 || start + total > rawDescriptors.size) return null
        return rawDescriptors.copyOfRange(start, start + total)
    }

    private fun clip(data: ByteArray, length: Int): ByteArray =
        if (length in 0 until data.size) data.copyOf(length) else data

    // --- bulk and interrupt ----------------------------------------------

    private fun dataTransfer(
        conn: UsbDeviceConnection,
        cmd: CmdSubmit,
        outData: ByteArray?,
    ): UrbResult {
        val direction =
            if (cmd.header.isIn) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT
        val endpoint = endpoints[key(cmd.header.ep, direction)]
        if (endpoint == null) {
            // Distinct from a device stall: we never found the endpoint at all.
            Log.w(
                TAG,
                "no endpoint for ep${cmd.header.ep} dir=$direction; known: " +
                    endpoints.values.joinToString { "0x%02x".format(it.address) },
            )
            return UrbResult.stall()
        }

        return if (cmd.header.isIn) {
            // Read into at least one full packet, whatever the host asked for.
            //
            // Android drops the transfer when the buffer is smaller than the
            // endpoint's maximum packet size. Bulk-Only Transport hits this on
            // every command: the 13-byte CSW read against a 512-byte endpoint
            // came back empty, usb-storage saw a command that never completed,
            // and reset the device in a loop.
            val packet = endpoint.maxPacketSize.coerceAtLeast(1)
            val wanted = cmd.transferBufferLength
            val buffer = ByteArray(maxOf(wanted, packet))

            val moved = conn.bulkTransfer(endpoint, buffer, buffer.size, TIMEOUT_MS)
            if (DEBUG) {
                Log.d(
                    TAG,
                    "  bulkIn ep=0x%02x maxPacket=%d asked=%d buffer=%d -> moved=%d"
                        .format(endpoint.address, packet, wanted, buffer.size, moved),
                )
            }
            // A timeout on an IN endpoint is normal for a device with nothing
            // to say -- report an empty success rather than an error, or a
            // polling driver treats every idle poll as a fault.
            if (moved < 0) {
                UrbResult.ok(ByteArray(0))
            } else {
                // Never hand back more than the host has room for.
                UrbResult.ok(buffer.copyOf(minOf(moved, wanted)))
            }
        } else {
            val payload = outData ?: ByteArray(0)
            var moved = conn.bulkTransfer(endpoint, payload, payload.size, TIMEOUT_MS)

            // One re-claim and retry before reporting a stall. Re-claiming the
            // interface is the only reset Android exposes, and a first bulk
            // write straight after enumeration can fail while the endpoint is
            // still in whatever state the previous owner left it.
            if (moved < 0) {
                Log.w(TAG, "bulkOut ep=0x%02x failed, re-claiming and retrying".format(endpoint.address))
                if (clearHalt(endpoint.address)) {
                    endpoints[key(endpoint.endpointNumber, endpoint.direction)]?.let { fresh ->
                        moved = conn.bulkTransfer(fresh, payload, payload.size, TIMEOUT_MS)
                    }
                }
            }

            if (DEBUG) {
                Log.d(
                    TAG,
                    "  bulkOut ep=0x%02x len=%d -> moved=%d".format(endpoint.address, payload.size, moved),
                )
            }
            if (moved < 0) UrbResult.stall() else UrbResult(0, ByteArray(moved))
        }
    }

    private fun key(number: Int, direction: Int) = (number shl 1) or (direction ushr 7)

    companion object {
        private const val TAG = "UsbipPassthru"
        private val DEBUG = BuildConfig.DEBUG

        /**
         * Long enough for a slow device to answer, short enough that a wedged
         * one does not block the connection's URB queue indefinitely.
         */
        private const val TIMEOUT_MS = 5000

        private const val REQ_GET_STATUS = 0x00
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val RECIPIENT_ENDPOINT_OUT = 0x02
        private const val FEATURE_ENDPOINT_HALT = 0x00
        private const val REQ_SET_ADDRESS = 0x05
        private const val REQ_GET_DESCRIPTOR = 0x06
        private const val REQ_GET_CONFIGURATION = 0x08
        private const val REQ_SET_CONFIGURATION = 0x09

        private const val DESC_DEVICE = 0x01
        private const val DESC_CONFIGURATION = 0x02
        private const val DESC_STRING = 0x03

        fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}
