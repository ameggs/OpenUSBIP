package io.openusbip.server.mtp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB descriptors for the synthesised MTP device.
 *
 * Getting a host to treat this as MTP rather than a plain camera is the whole
 * problem here, and it takes two things:
 *
 *  1. A Still Image interface (class 6, subclass 1, protocol 1). That alone is
 *     PTP, which is why gphoto2 will mount it as a camera.
 *  2. The Microsoft OS descriptors -- a magic string descriptor at index 0xEE
 *     followed by an Extended Compat ID naming "MTP". This is how Android's own
 *     MTP gadget announces itself, and how libmtp recognises a device that is
 *     not in its hardcoded VID/PID table.
 *
 * Without the OS descriptor the device still works, but a Linux host is liable
 * to claim it with gphoto2 instead of libmtp, which browses far less well.
 *
 * Little-endian throughout, unlike the USB/IP headers carrying it.
 */
object MtpDescriptors {

    const val CLASS_STILL_IMAGE = 0x06
    const val SUBCLASS_STILL_IMAGE = 0x01
    const val PROTOCOL_PIMA_15740 = 0x01

    /** Endpoint numbers; direction comes from the URB, not from these. */
    const val EP_BULK_IN = 1
    const val EP_BULK_OUT = 2
    const val EP_INTERRUPT_IN = 3

    const val MAX_PACKET_BULK = 512
    const val MAX_PACKET_INTERRUPT = 28
    const val MAX_PACKET_CONTROL = 64

    /** String index Windows and libmtp probe for the OS descriptor. */
    const val OS_STRING_INDEX = 0xEE

    /** Vendor request code we nominate for the extended descriptors. */
    const val OS_VENDOR_CODE = 0x01

    private fun le(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun device(idVendor: Int, idProduct: Int, bcdDevice: Int): ByteArray {
        val buf = le(18)
        buf.put(18)
        buf.put(0x01)                          // DEVICE
        buf.putShort(0x0200)                   // USB 2.0
        buf.put(0)                             // class: per-interface
        buf.put(0)
        buf.put(0)
        buf.put(MAX_PACKET_CONTROL.toByte())
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
     * Configuration, interface and three endpoints as one block.
     *
     * The interrupt IN endpoint is required by PTP for event delivery. We never
     * send events, but omitting the endpoint makes some hosts reject the
     * interface as malformed, so it is declared and simply stays quiet.
     */
    fun configuration(): ByteArray {
        val total = 9 + 9 + 7 + 7 + 7
        val buf = le(total)

        buf.put(9)
        buf.put(0x02)                          // CONFIGURATION
        buf.putShort(total.toShort())
        buf.put(1)                             // bNumInterfaces
        buf.put(1)                             // bConfigurationValue
        buf.put(0)                             // iConfiguration
        buf.put(0x80.toByte())                 // bus powered
        buf.put(50)                            // 100mA

        buf.put(9)
        buf.put(0x04)                          // INTERFACE
        buf.put(0)                             // bInterfaceNumber
        buf.put(0)                             // bAlternateSetting
        buf.put(3)                             // bNumEndpoints
        buf.put(CLASS_STILL_IMAGE.toByte())
        buf.put(SUBCLASS_STILL_IMAGE.toByte())
        buf.put(PROTOCOL_PIMA_15740.toByte())
        buf.put(0)                             // iInterface

        // bulk IN
        buf.put(7); buf.put(0x05)
        buf.put((0x80 or EP_BULK_IN).toByte())
        buf.put(0x02)                          // bulk
        buf.putShort(MAX_PACKET_BULK.toShort())
        buf.put(0)

        // bulk OUT
        buf.put(7); buf.put(0x05)
        buf.put(EP_BULK_OUT.toByte())
        buf.put(0x02)
        buf.putShort(MAX_PACKET_BULK.toShort())
        buf.put(0)

        // interrupt IN, for PTP events
        buf.put(7); buf.put(0x05)
        buf.put((0x80 or EP_INTERRUPT_IN).toByte())
        buf.put(0x03)                          // interrupt
        buf.putShort(MAX_PACKET_INTERRUPT.toShort())
        buf.put(6)                             // bInterval
        return buf.array()
    }

    fun languageList(): ByteArray {
        val buf = le(4)
        buf.put(4); buf.put(0x03)
        buf.putShort(0x0409)                   // en-US
        return buf.array()
    }

    fun string(value: String): ByteArray {
        val chars = value.toCharArray()
        val length = 2 + chars.size * 2
        val buf = le(length)
        buf.put(length.toByte())
        buf.put(0x03)
        chars.forEach { buf.putShort(it.code.toShort()) }
        return buf.array()
    }

    /**
     * The Microsoft OS String Descriptor, returned for string index 0xEE.
     *
     * Fixed shape: the signature "MSFT100" in UTF-16, then the vendor request
     * code a host should use to ask for the extended descriptors, then a pad.
     */
    fun osStringDescriptor(): ByteArray {
        val signature = "MSFT100"
        val length = 2 + signature.length * 2 + 2
        val buf = le(length)
        buf.put(length.toByte())
        buf.put(0x03)                          // STRING
        signature.forEach { buf.putShort(it.code.toShort()) }
        buf.put(OS_VENDOR_CODE.toByte())
        buf.put(0)                             // pad
        return buf.array()
    }

    /**
     * Extended Compat ID descriptor, naming this interface as "MTP".
     *
     * This is the bit that gets libmtp and Windows to bind MTP rather than
     * falling back to generic PTP.
     */
    fun extendedCompatId(): ByteArray {
        val total = 16 + 24            // header + one function section
        val buf = le(total)
        buf.putInt(total)              // dwLength
        buf.putShort(0x0100)           // bcdVersion 1.00
        buf.putShort(0x0004)           // wIndex: extended compat ID
        buf.put(1)                     // bCount: one function
        repeat(7) { buf.put(0) }       // reserved

        buf.put(0)                     // bFirstInterfaceNumber
        buf.put(1)                     // reserved (must be 1)
        // compatibleID, 8 bytes, NUL padded
        "MTP".toByteArray(Charsets.US_ASCII).let { id ->
            buf.put(id); repeat(8 - id.size) { buf.put(0) }
        }
        repeat(8) { buf.put(0) }       // subCompatibleID
        repeat(6) { buf.put(0) }       // reserved
        return buf.array()
    }
}
