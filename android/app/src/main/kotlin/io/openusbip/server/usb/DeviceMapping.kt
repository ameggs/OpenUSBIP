package io.openusbip.server.usb

import io.openusbip.server.protocol.Protocol
import io.openusbip.server.protocol.UsbipDevice
import io.openusbip.server.protocol.UsbipInterface

/**
 * Translation between Android's USB model and the USB/IP wire model.
 *
 * Android does not expose a device's bus or device number directly, but
 * `UsbDevice.getDeviceName()` returns the kernel path -- "/dev/bus/usb/001/004"
 * -- which carries both. Everything here is a pure function over plain data so
 * it can be unit-tested on a desktop JVM; the Android glue lives in
 * [UsbHost] and only supplies these inputs.
 */
object DeviceMapping {

    /** Bus and device number as parsed from a kernel USB path. */
    data class BusDev(val busnum: Int, val devnum: Int)

    /**
     * Parse "/dev/bus/usb/001/004" into bus 1, device 4.
     *
     * Returns null rather than guessing when the name is not in that form --
     * a fabricated bus/device pair would produce a devid the client echoes
     * back and we could no longer match to a real device.
     */
    fun parseBusDev(deviceName: String): BusDev? {
        val parts = deviceName.trim().trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val devnum = parts[parts.size - 1].toIntOrNull() ?: return null
        val busnum = parts[parts.size - 2].toIntOrNull() ?: return null
        if (busnum < 0 || devnum < 0) return null
        return BusDev(busnum, devnum)
    }

    /**
     * The busid a client sees and passes back in OP_REQ_IMPORT.
     *
     * Real usbip derives this from physical topology ("1-1.1"). Android gives
     * us no topology, so this is synthesised from bus and device number. It
     * only has to be stable for the life of the connection and unique across
     * exported devices, both of which hold.
     */
    fun busid(busnum: Int, devnum: Int): String = "$busnum-$devnum"

    /**
     * Android's `getVersion()` returns a dotted string like "2.00"; the wire
     * format wants BCD. "2.00" becomes 0x0200.
     */
    fun bcdVersion(version: String?): Int {
        if (version.isNullOrBlank()) return 0
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return 0
        val minorText = parts.getOrNull(1)?.trim().orEmpty().padEnd(2, '0').take(2)
        val minor = minorText.toIntOrNull() ?: 0
        // Each nibble is a decimal digit: major 2, minor 00 -> 0x0200.
        return ((major / 10) shl 12) or ((major % 10) shl 8) or
            ((minor / 10) shl 4) or (minor % 10)
    }

    /** Plain description of one Android USB interface. */
    data class InterfaceSpec(
        val interfaceClass: Int,
        val interfaceSubClass: Int,
        val interfaceProtocol: Int,
    )

    /** Plain description of one Android USB device, free of framework types. */
    data class DeviceSpec(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val deviceSubClass: Int,
        val deviceProtocol: Int,
        val version: String?,
        val configurationCount: Int,
        val interfaces: List<InterfaceSpec>,
    )

    /**
     * Build the wire representation of a device.
     *
     * Speed is reported as high-speed: Android exposes no speed before API 35,
     * and the value is advisory -- the client uses it for display and for
     * choosing a virtual port, not for framing.
     */
    fun toUsbipDevice(spec: DeviceSpec): UsbipDevice? {
        val busDev = parseBusDev(spec.deviceName) ?: return null
        return UsbipDevice(
            path = spec.deviceName,
            busid = busid(busDev.busnum, busDev.devnum),
            busnum = busDev.busnum,
            devnum = busDev.devnum,
            speed = Protocol.SPEED_HIGH,
            idVendor = spec.vendorId and 0xFFFF,
            idProduct = spec.productId and 0xFFFF,
            bcdDevice = bcdVersion(spec.version),
            deviceClass = spec.deviceClass and 0xFF,
            deviceSubClass = spec.deviceSubClass and 0xFF,
            deviceProtocol = spec.deviceProtocol and 0xFF,
            configurationValue = 1,
            numConfigurations = spec.configurationCount.coerceAtLeast(1),
            numInterfaces = spec.interfaces.size,
            interfaces = spec.interfaces.map {
                UsbipInterface(it.interfaceClass, it.interfaceSubClass, it.interfaceProtocol)
            },
        )
    }

    /**
     * Whether this device is worth offering to clients.
     *
     * Hubs are excluded: exporting one would move a whole tree, and Android
     * never hands userspace a hub anyway. Class 9 is the hub class.
     */
    fun isExportable(spec: DeviceSpec): Boolean {
        if (spec.deviceClass == 0x09) return false
        if (spec.interfaces.size == 1 && spec.interfaces[0].interfaceClass == 0x09) return false
        return parseBusDev(spec.deviceName) != null
    }
}
