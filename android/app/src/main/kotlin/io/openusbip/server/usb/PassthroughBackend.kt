package io.openusbip.server.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
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
    private val claimed = mutableListOf<UsbInterface>()

    /** Endpoint lookup by (number, direction), built once on open. */
    private val endpoints = HashMap<Int, UsbEndpoint>()

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
                    endpoints[key(ep.endpointNumber, ep.direction)] = ep
                }
            } else {
                Log.w(TAG, "could not claim interface $i")
            }
        }
        Log.i(TAG, "opened ${usbDevice.deviceName}: ${claimed.size} interface(s), ${endpoints.size} endpoint(s)")
    }

    override fun close() {
        val conn = connection ?: return
        claimed.forEach { runCatching { conn.releaseInterface(it) } }
        claimed.clear()
        endpoints.clear()
        runCatching { conn.close() }
        connection = null
        Log.i(TAG, "released ${usbDevice.deviceName}")
    }

    override fun submit(cmd: CmdSubmit, outData: ByteArray?): UrbResult {
        val conn = connection ?: return UrbResult.noDevice()

        return try {
            if (cmd.isControl) controlTransfer(conn, cmd, outData)
            else dataTransfer(conn, cmd, outData)
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
        val buffer = if (isIn) ByteArray(length) else (outData ?: ByteArray(0))

        val moved = conn.controlTransfer(
            requestType, request, value, index, buffer, buffer.size, TIMEOUT_MS,
        )
        // A negative return is a failure, not a byte count.
        if (moved < 0) return UrbResult.stall()

        return if (isIn) UrbResult.ok(buffer.copyOf(moved)) else UrbResult(0, ByteArray(moved))
    }

    // --- bulk and interrupt ----------------------------------------------

    private fun dataTransfer(
        conn: UsbDeviceConnection,
        cmd: CmdSubmit,
        outData: ByteArray?,
    ): UrbResult {
        val direction =
            if (cmd.header.isIn) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT
        val endpoint = endpoints[key(cmd.header.ep, direction)]
            ?: return UrbResult.stall()

        return if (cmd.header.isIn) {
            val buffer = ByteArray(cmd.transferBufferLength)
            val moved = conn.bulkTransfer(endpoint, buffer, buffer.size, TIMEOUT_MS)
            // A timeout on an IN endpoint is normal for a device with nothing
            // to say -- report an empty success rather than an error, or a
            // polling driver treats every idle poll as a fault.
            if (moved < 0) UrbResult.ok(ByteArray(0)) else UrbResult.ok(buffer.copyOf(moved))
        } else {
            val payload = outData ?: ByteArray(0)
            val moved = conn.bulkTransfer(endpoint, payload, payload.size, TIMEOUT_MS)
            if (moved < 0) UrbResult.stall() else UrbResult(0, ByteArray(moved))
        }
    }

    private fun key(number: Int, direction: Int) = (number shl 1) or (direction ushr 7)

    companion object {
        private const val TAG = "UsbipPassthru"

        /**
         * Long enough for a slow device to answer, short enough that a wedged
         * one does not block the connection's URB queue indefinitely.
         */
        private const val TIMEOUT_MS = 5000

        fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}
