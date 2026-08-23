package io.openusbip.server.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.openusbip.server.protocol.UsbipDevice

/**
 * Android-side USB enumeration.
 *
 * This is the only place that touches `android.hardware.usb`; the mapping
 * itself lives in [DeviceMapping] so it stays unit-testable off-device.
 */
class UsbHost(private val usbManager: UsbManager) {

    /** Every attached device, whether or not we hold permission for it. */
    fun attachedDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Find a device by the busid we advertised, or null if it has gone away. */
    fun findByBusid(busid: String): UsbDevice? =
        attachedDevices().firstOrNull { specOf(it).let { s -> DeviceMapping.toUsbipDevice(s)?.busid == busid } }

    /**
     * Devices we are willing to export. Permission is *not* required to
     * enumerate, only to open -- so this lists devices the user has not yet
     * approved, and the UI prompts at share time.
     */
    fun exportableDevices(): List<Pair<UsbDevice, UsbipDevice>> =
        attachedDevices().mapNotNull { device ->
            val spec = specOf(device)
            if (!DeviceMapping.isExportable(spec)) return@mapNotNull null
            DeviceMapping.toUsbipDevice(spec)?.let { device to it }
        }

    companion object {

        /** Flatten an Android [UsbDevice] into framework-free data. */
        fun specOf(device: UsbDevice): DeviceMapping.DeviceSpec {
            val interfaces = (0 until device.interfaceCount).map { i ->
                val iface = device.getInterface(i)
                DeviceMapping.InterfaceSpec(
                    interfaceClass = iface.interfaceClass,
                    interfaceSubClass = iface.interfaceSubclass,
                    interfaceProtocol = iface.interfaceProtocol,
                )
            }
            return DeviceMapping.DeviceSpec(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                deviceSubClass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
                version = runCatching { device.version }.getOrNull(),
                configurationCount = runCatching { device.configurationCount }.getOrDefault(1),
                interfaces = interfaces,
            )
        }

        /** Human-readable label for the UI. */
        fun describe(device: UsbDevice): String {
            val name = listOfNotNull(
                runCatching { device.manufacturerName }.getOrNull(),
                runCatching { device.productName }.getOrNull(),
            ).filter { it.isNotBlank() }.joinToString(" ")

            val ids = String.format("%04x:%04x", device.vendorId, device.productId)
            return if (name.isBlank()) ids else "$name ($ids)"
        }
    }
}
