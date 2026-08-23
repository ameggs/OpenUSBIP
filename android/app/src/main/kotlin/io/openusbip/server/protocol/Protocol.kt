package io.openusbip.server.protocol

/**
 * USB/IP wire protocol constants and structure sizes.
 *
 * Reference: Linux `Documentation/usb/usbip_protocol.rst` and
 * `tools/usb/usbip/`. Every field on the wire is big-endian (network order),
 * including on little-endian hosts.
 *
 * This file and the rest of this package are deliberately free of Android
 * imports so the protocol can be unit-tested on a desktop JVM. Only the `usb`
 * and `net` packages touch the Android framework.
 */
object Protocol {

    /** Protocol version reported in the op-phase header (1.1.1). */
    const val VERSION = 0x0111

    // --- op (negotiation) phase opcodes -----------------------------------

    const val OP_REQ_DEVLIST = 0x8005
    const val OP_REP_DEVLIST = 0x0005
    const val OP_REQ_IMPORT = 0x8003
    const val OP_REP_IMPORT = 0x0003

    // --- URB phase commands ----------------------------------------------

    const val CMD_SUBMIT = 0x00000001
    const val RET_SUBMIT = 0x00000003
    const val CMD_UNLINK = 0x00000002
    const val RET_UNLINK = 0x00000004

    // --- status codes ----------------------------------------------------

    /** Request succeeded. */
    const val ST_OK = 0x00

    /** Request failed. usbip's client prints this as a generic error. */
    const val ST_NA = 0x01

    // --- transfer direction ----------------------------------------------

    const val DIR_OUT = 0
    const val DIR_IN = 1

    // --- structure sizes, in bytes ---------------------------------------

    /** version(2) + code(2) + status(4) */
    const val OP_HEADER_SIZE = 8

    /**
     * path(256) + busid(32) + busnum(4) + devnum(4) + speed(4) +
     * idVendor(2) + idProduct(2) + bcdDevice(2) + bDeviceClass(1) +
     * bDeviceSubClass(1) + bDeviceProtocol(1) + bConfigurationValue(1) +
     * bNumConfigurations(1) + bNumInterfaces(1)
     */
    const val USB_DEVICE_SIZE = 312

    /** bInterfaceClass(1) + bInterfaceSubClass(1) + bInterfaceProtocol(1) + pad(1) */
    const val USB_INTERFACE_SIZE = 4

    /** command(4) + seqnum(4) + devid(4) + direction(4) + ep(4) */
    const val BASIC_HEADER_SIZE = 20

    /** Every URB-phase header is a fixed 48 bytes: basic(20) + body(28). */
    const val URB_HEADER_SIZE = 48

    const val PATH_LEN = 256
    const val BUSID_LEN = 32

    /** usbipd's registered port. */
    const val DEFAULT_PORT = 3240

    // --- USB speed codes (linux/usb/ch9.h enum usb_device_speed) ----------

    const val SPEED_UNKNOWN = 0
    const val SPEED_LOW = 1
    const val SPEED_FULL = 2
    const val SPEED_HIGH = 3
    const val SPEED_WIRELESS = 4
    const val SPEED_SUPER = 5
    const val SPEED_SUPER_PLUS = 6

    /**
     * usbip identifies a device by bus and device number packed into one word.
     * The client echoes this back in every URB, so it must round-trip exactly.
     */
    fun devid(busnum: Int, devnum: Int): Int = (busnum shl 16) or (devnum and 0xFFFF)

    fun busnumOf(devid: Int): Int = (devid ushr 16) and 0xFFFF

    fun devnumOf(devid: Int): Int = devid and 0xFFFF
}
