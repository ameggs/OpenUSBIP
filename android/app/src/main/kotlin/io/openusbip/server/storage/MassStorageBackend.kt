package io.openusbip.server.storage

import io.openusbip.server.net.UrbResult
import io.openusbip.server.net.UsbBackend
import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.protocol.UsbipDevice
import io.openusbip.server.protocol.UsbipInterface

/**
 * A USB mass-storage device that does not exist.
 *
 * This is how internal storage reaches a client. Nothing is plugged in; the
 * device is synthesised, answering enumeration and Bulk-Only Transport itself
 * and backed by a file on the phone. To the client it is indistinguishable
 * from a USB stick, and because it implements the same [UsbBackend] interface
 * as a real passed-through device, the server treats it identically.
 *
 * Endianness is the recurring hazard: the setup packet parsed here is
 * little-endian (USB), while the surrounding USB/IP headers are big-endian.
 */
class MassStorageBackend(
    private val block: BlockDevice,
    busid: String,
    private val serial: String = "OPENUSBIP0001",
    private val manufacturer: String = "OpenUSBIP",
    private val product: String = "Virtual Disk",
    idVendor: Int = 0x1D6B,          // Linux Foundation, as a stand-in
    idProduct: Int = 0x0104,
) : UsbBackend {

    private val scsi = ScsiTarget(block, product = product)
    private val bot = BulkOnlyTransport(scsi)

    override val device = UsbipDevice(
        path = "/virtual/openusbip/$busid",
        busid = busid,
        busnum = VIRTUAL_BUSNUM,
        devnum = busid.substringAfterLast('-').toIntOrNull() ?: 1,
        speed = Protocol.SPEED_HIGH,
        idVendor = idVendor,
        idProduct = idProduct,
        bcdDevice = 0x0100,
        deviceClass = 0x00,             // per-interface
        deviceSubClass = 0x00,
        deviceProtocol = 0x00,
        configurationValue = 1,
        numConfigurations = 1,
        numInterfaces = 1,
        interfaces = listOf(
            UsbipInterface(
                UsbDescriptors.CLASS_MASS_STORAGE,
                UsbDescriptors.SUBCLASS_SCSI,
                UsbDescriptors.PROTOCOL_BULK_ONLY,
            ),
        ),
    )

    /** Bytes on the medium, for the UI. */
    val capacityBytes: Long get() = block.sizeBytes

    override fun submit(cmd: CmdSubmit, outData: ByteArray?): UrbResult =
        if (cmd.isControl) handleControl(cmd, outData) else handleBulk(cmd, outData)

    override fun close() {
        bot.reset()
        runCatching { block.flush() }
    }

    // --- control endpoint -------------------------------------------------

    private fun handleControl(cmd: CmdSubmit, outData: ByteArray?): UrbResult {
        val setup = cmd.setup
        if (setup.size < 8) return UrbResult.protocolError()

        val requestType = setup[0].toInt() and 0xFF
        val request = setup[1].toInt() and 0xFF
        val value = le16(setup, 2)
        val length = le16(setup, 6)

        val isClass = (requestType and 0x60) == 0x20

        return if (isClass) {
            handleClassRequest(request, length)
        } else {
            handleStandardRequest(request, value, length)
        }
    }

    private fun handleStandardRequest(request: Int, value: Int, length: Int): UrbResult =
        when (request) {
            REQ_GET_DESCRIPTOR -> {
                val type = (value shr 8) and 0xFF
                val index = value and 0xFF
                descriptor(type, index)?.let { UrbResult.ok(clip(it, length)) }
                    ?: UrbResult.stall()
            }

            // Accepting the configuration is what completes enumeration.
            REQ_SET_CONFIGURATION -> UrbResult.OK
            REQ_GET_CONFIGURATION -> UrbResult.ok(byteArrayOf(1))
            REQ_SET_INTERFACE -> UrbResult.OK
            REQ_GET_INTERFACE -> UrbResult.ok(byteArrayOf(0))

            // Two zero bytes: not halted, not self-powered.
            REQ_GET_STATUS -> UrbResult.ok(byteArrayOf(0, 0))

            // Clearing a halt is the host recovering from a stall; there is no
            // real endpoint to unhalt, so acknowledging is correct.
            REQ_CLEAR_FEATURE -> UrbResult.OK
            REQ_SET_FEATURE -> UrbResult.OK

            // A device that reports USB 2.0 but is not dual-speed must stall
            // this rather than answer it.
            REQ_SET_ADDRESS -> UrbResult.OK

            else -> UrbResult.stall()
        }

    private fun handleClassRequest(request: Int, length: Int): UrbResult = when (request) {
        // GET MAX LUN: zero means a single logical unit. Stalling this is
        // legal but makes usb-storage probe harder than it needs to.
        REQ_GET_MAX_LUN -> UrbResult.ok(byteArrayOf(0))

        // Bulk-Only mass storage reset: abandon any command in flight.
        REQ_BOT_RESET -> { bot.reset(); UrbResult.OK }

        else -> UrbResult.stall()
    }

    private fun descriptor(type: Int, index: Int): ByteArray? = when (type) {
        UsbDescriptors.TYPE_DEVICE ->
            UsbDescriptors.device(device.idVendor, device.idProduct, device.bcdDevice)

        UsbDescriptors.TYPE_CONFIGURATION -> UsbDescriptors.configuration()

        UsbDescriptors.TYPE_STRING -> when (index) {
            0 -> UsbDescriptors.languageList()
            1 -> UsbDescriptors.string(manufacturer)
            2 -> UsbDescriptors.string(product)
            3 -> UsbDescriptors.string(serial)
            else -> null
        }

        // Not dual-speed, so this must be stalled rather than faked.
        UsbDescriptors.TYPE_DEVICE_QUALIFIER -> null

        else -> null
    }

    // --- bulk endpoints ---------------------------------------------------

    private fun handleBulk(cmd: CmdSubmit, outData: ByteArray?): UrbResult {
        return if (cmd.header.isIn) {
            if (cmd.header.ep != UsbDescriptors.EP_BULK_IN) return UrbResult.stall()
            bot.handleIn(cmd.transferBufferLength)?.let { UrbResult.ok(it) } ?: UrbResult.stall()
        } else {
            if (cmd.header.ep != UsbDescriptors.EP_BULK_OUT) return UrbResult.stall()
            val payload = outData ?: ByteArray(0)
            if (bot.handleOut(payload)) UrbResult.OK else UrbResult.stall()
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun clip(data: ByteArray, length: Int): ByteArray =
        if (length in 0 until data.size) data.copyOf(length) else data

    companion object {
        /** Virtual devices live on their own bus so they cannot collide with
         *  a real one passed through from the OTG port. */
        const val VIRTUAL_BUSNUM = 99

        private const val REQ_GET_STATUS = 0x00
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val REQ_SET_FEATURE = 0x03
        private const val REQ_SET_ADDRESS = 0x05
        private const val REQ_GET_DESCRIPTOR = 0x06
        private const val REQ_GET_CONFIGURATION = 0x08
        private const val REQ_SET_CONFIGURATION = 0x09
        private const val REQ_GET_INTERFACE = 0x0A
        private const val REQ_SET_INTERFACE = 0x0B

        private const val REQ_BOT_RESET = 0xFF
        private const val REQ_GET_MAX_LUN = 0xFE

        fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}
