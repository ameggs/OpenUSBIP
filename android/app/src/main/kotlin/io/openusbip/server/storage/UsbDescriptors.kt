package io.openusbip.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB descriptors for the synthesised mass-storage device.
 *
 * Beware the endianness split that runs through this whole feature: USB/IP's
 * own headers are big-endian, but everything *inside* USB -- descriptors, the
 * setup packet, CBW and CSW -- is little-endian. Mixing the two produces
 * descriptors that parse as garbage, so every buffer here is explicitly
 * LITTLE_ENDIAN.
 *
 * A USB/IP client does not take the device's identity from OP_REP_IMPORT
 * alone; once vhci_hcd creates the virtual device, the USB core enumerates it
 * for real, issuing GET_DESCRIPTOR control transfers over the connection. So
 * these have to be correct and self-consistent or enumeration fails.
 */
object UsbDescriptors {

    const val TYPE_DEVICE = 0x01
    const val TYPE_CONFIGURATION = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_INTERFACE = 0x04
    const val TYPE_ENDPOINT = 0x05
    const val TYPE_DEVICE_QUALIFIER = 0x06

    /** Mass Storage class, SCSI transparent command set, Bulk-Only Transport. */
    const val CLASS_MASS_STORAGE = 0x08
    const val SUBCLASS_SCSI = 0x06
    const val PROTOCOL_BULK_ONLY = 0x50

    /** Endpoint numbers. Direction comes from the URB, not from these. */
    const val EP_BULK_IN = 1
    const val EP_BULK_OUT = 2

    const val MAX_PACKET_BULK = 512
    const val MAX_PACKET_CONTROL = 64

    private fun le(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** 18-byte device descriptor. */
    fun device(idVendor: Int, idProduct: Int, bcdDevice: Int): ByteArray {
        val buf = le(18)
        buf.put(18)                            // bLength
        buf.put(TYPE_DEVICE.toByte())          // bDescriptorType
        buf.putShort(0x0200)                   // bcdUSB 2.0
        buf.put(0)                             // bDeviceClass: per-interface
        buf.put(0)                             // bDeviceSubClass
        buf.put(0)                             // bDeviceProtocol
        buf.put(MAX_PACKET_CONTROL.toByte())   // bMaxPacketSize0
        buf.putShort(idVendor.toShort())
        buf.putShort(idProduct.toShort())
        buf.putShort(bcdDevice.toShort())
        buf.put(1)                             // iManufacturer
        buf.put(2)                             // iProduct
        buf.put(3)                             // iSerialNumber
        buf.put(1)                             // bNumConfigurations
        return buf.array()
    }

    /**
     * Configuration descriptor plus its interface and both bulk endpoints,
     * returned as one contiguous block. 9 + 9 + 7 + 7 = 32 bytes.
     *
     * wTotalLength must cover the whole block: the host reads 9 bytes first,
     * learns the total, then re-reads. A wrong total truncates enumeration.
     */
    fun configuration(): ByteArray {
        val total = 9 + 9 + 7 + 7
        val buf = le(total)

        // configuration
        buf.put(9)
        buf.put(TYPE_CONFIGURATION.toByte())
        buf.putShort(total.toShort())
        buf.put(1)                             // bNumInterfaces
        buf.put(1)                             // bConfigurationValue
        buf.put(0)                             // iConfiguration
        buf.put(0x80.toByte())                 // bmAttributes: bus powered
        buf.put(50)                            // bMaxPower: 100mA

        // interface
        buf.put(9)
        buf.put(TYPE_INTERFACE.toByte())
        buf.put(0)                             // bInterfaceNumber
        buf.put(0)                             // bAlternateSetting
        buf.put(2)                             // bNumEndpoints
        buf.put(CLASS_MASS_STORAGE.toByte())
        buf.put(SUBCLASS_SCSI.toByte())
        buf.put(PROTOCOL_BULK_ONLY.toByte())
        buf.put(0)                             // iInterface

        // bulk IN endpoint (0x80 | 1)
        buf.put(7)
        buf.put(TYPE_ENDPOINT.toByte())
        buf.put((0x80 or EP_BULK_IN).toByte())
        buf.put(0x02)                          // bulk
        buf.putShort(MAX_PACKET_BULK.toShort())
        buf.put(0)                             // bInterval

        // bulk OUT endpoint
        buf.put(7)
        buf.put(TYPE_ENDPOINT.toByte())
        buf.put(EP_BULK_OUT.toByte())
        buf.put(0x02)
        buf.putShort(MAX_PACKET_BULK.toShort())
        buf.put(0)
        return buf.array()
    }

    /** String descriptor 0: the supported language list. */
    fun languageList(): ByteArray {
        val buf = le(4)
        buf.put(4)
        buf.put(TYPE_STRING.toByte())
        buf.putShort(0x0409)                   // en-US
        return buf.array()
    }

    /** A UTF-16LE string descriptor, as USB requires. */
    fun string(value: String): ByteArray {
        val chars = value.toCharArray()
        val length = 2 + chars.size * 2
        val buf = le(length)
        buf.put(length.toByte())
        buf.put(TYPE_STRING.toByte())
        chars.forEach { buf.putShort(it.code.toShort()) }
        return buf.array()
    }
}
