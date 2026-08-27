package io.openusbip.server.mtp

import io.openusbip.server.net.UrbResult
import io.openusbip.server.net.UsbBackend
import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.protocol.UsbipDevice
import io.openusbip.server.protocol.UsbipInterface
import java.io.File

/**
 * A synthesised MTP device, exported over USB/IP.
 *
 * This is what lets a client browse the phone's actual filesystem. Mass storage
 * cannot do that -- a block device has one owner, and Android has its storage
 * mounted -- whereas MTP is file-level, so the phone keeps ownership and simply
 * answers file operations. It is the same protocol a phone speaks over a cable.
 *
 * Shares the [UsbBackend] interface with everything else, so the server does
 * not know or care that this device is invented.
 *
 * Transport responsibilities live here; the operations themselves are in
 * [MtpSession], which is transport-free and unit-tested.
 */
class MtpBackend(
    sharedRoot: File,
    busid: String,
    private val deviceName: String = "Android",
    private val serial: String = "OPENUSBIP0001",
    idVendor: Int = 0x18D1,          // Google, which is what phones report
    idProduct: Int = 0x4EE1,         // "MTP" composite
) : UsbBackend {

    val store = ObjectStore(sharedRoot)
    private val session = MtpSession(store, deviceName, serial)

    /**
     * The command a data phase belongs to.
     *
     * PTP splits a transaction across separate bulk transfers, so when a data
     * container arrives we need to know which command asked for it. Without
     * this a SendObject payload has no idea where to be written.
     */
    private var currentCommand: PtpContainer? = null

    /** Response owed to the host after it has read the data container. */
    private var pendingResponse: PtpContainer? = null

    /** Data container the host has not finished reading yet. */
    private var pendingData: ByteArray = ByteArray(0)
    private var pendingDataOffset = 0

    /** Accumulates a host-to-device data container across multiple URBs. */
    private var incoming: ByteArray = ByteArray(0)
    private var incomingExpected = 0

    override val device = UsbipDevice(
        path = "/virtual/openusbip/$busid",
        busid = busid,
        busnum = VIRTUAL_BUSNUM,
        devnum = busid.substringAfterLast('-').toIntOrNull() ?: 2,
        speed = Protocol.SPEED_HIGH,
        idVendor = idVendor,
        idProduct = idProduct,
        bcdDevice = 0x0100,
        deviceClass = 0x00,
        deviceSubClass = 0x00,
        deviceProtocol = 0x00,
        configurationValue = 1,
        numConfigurations = 1,
        numInterfaces = 1,
        interfaces = listOf(
            UsbipInterface(
                MtpDescriptors.CLASS_STILL_IMAGE,
                MtpDescriptors.SUBCLASS_STILL_IMAGE,
                MtpDescriptors.PROTOCOL_PIMA_15740,
            ),
        ),
    )

    override fun submit(cmd: CmdSubmit, outData: ByteArray?): UrbResult =
        if (cmd.isControl) handleControl(cmd) else handleBulk(cmd, outData)

    override fun close() {
        reset()
        store.clear()
    }

    private fun reset() {
        currentCommand = null
        pendingResponse = null
        pendingData = ByteArray(0)
        pendingDataOffset = 0
        incoming = ByteArray(0)
        incomingExpected = 0
    }

    // --- control endpoint -------------------------------------------------

    private fun handleControl(cmd: CmdSubmit): UrbResult {
        val setup = cmd.setup
        if (setup.size < 8) return UrbResult.protocolError()

        val requestType = setup[0].toInt() and 0xFF
        val request = setup[1].toInt() and 0xFF
        val value = le16(setup, 2)
        val index = le16(setup, 4)
        val length = le16(setup, 6)

        // A vendor request with our nominated code is a host asking for the
        // Microsoft OS descriptors.
        if ((requestType and 0x60) == 0x40 && request == MtpDescriptors.OS_VENDOR_CODE) {
            return if (index == 0x0004) {
                UrbResult.ok(clip(MtpDescriptors.extendedCompatId(), length))
            } else {
                UrbResult.stall()
            }
        }

        // PTP class requests. Cancel and reset must abandon the transaction, or
        // a client that gave up mid-transfer leaves us permanently confused.
        if ((requestType and 0x60) == 0x20) {
            return when (request) {
                PTP_CANCEL_REQUEST, PTP_DEVICE_RESET -> { reset(); UrbResult.OK }
                PTP_GET_DEVICE_STATUS -> UrbResult.ok(deviceStatus())
                else -> UrbResult.stall()
            }
        }

        return when (request) {
            REQ_GET_DESCRIPTOR -> {
                val type = (value shr 8) and 0xFF
                val stringIndex = value and 0xFF
                descriptor(type, stringIndex)?.let { UrbResult.ok(clip(it, length)) }
                    ?: UrbResult.stall()
            }
            REQ_SET_CONFIGURATION -> UrbResult.OK
            REQ_GET_CONFIGURATION -> UrbResult.ok(byteArrayOf(1))
            REQ_SET_INTERFACE -> UrbResult.OK
            REQ_GET_INTERFACE -> UrbResult.ok(byteArrayOf(0))
            REQ_GET_STATUS -> UrbResult.ok(byteArrayOf(0, 0))
            REQ_CLEAR_FEATURE, REQ_SET_FEATURE, REQ_SET_ADDRESS -> UrbResult.OK
            else -> UrbResult.stall()
        }
    }

    /** PTP device status: 4-byte structure reporting OK. */
    private fun deviceStatus(): ByteArray {
        val w = PtpCodec.Writer(4)
        w.u16(4)                                    // wLength
        w.u16(MtpConstants.RESP_OK)
        return w.toByteArray()
    }

    private fun descriptor(type: Int, index: Int): ByteArray? = when (type) {
        0x01 -> MtpDescriptors.device(device.idVendor, device.idProduct, device.bcdDevice)
        0x02 -> MtpDescriptors.configuration()
        0x03 -> when (index) {
            0 -> MtpDescriptors.languageList()
            1 -> MtpDescriptors.string("OpenUSBIP")
            2 -> MtpDescriptors.string(deviceName)
            3 -> MtpDescriptors.string(serial)
            // The magic index that starts the Microsoft OS descriptor handshake.
            MtpDescriptors.OS_STRING_INDEX -> MtpDescriptors.osStringDescriptor()
            else -> null
        }
        else -> null
    }

    // --- bulk endpoints ---------------------------------------------------

    private fun handleBulk(cmd: CmdSubmit, outData: ByteArray?): UrbResult {
        return if (cmd.header.isIn) {
            when (cmd.header.ep) {
                MtpDescriptors.EP_BULK_IN -> UrbResult.ok(readIn(cmd.transferBufferLength))
                // We never raise events, so an interrupt read simply has
                // nothing to return. A stall here makes hosts retry forever.
                MtpDescriptors.EP_INTERRUPT_IN -> UrbResult.ok(ByteArray(0))
                else -> UrbResult.stall()
            }
        } else {
            if (cmd.header.ep != MtpDescriptors.EP_BULK_OUT) return UrbResult.stall()
            writeOut(outData ?: ByteArray(0))
        }
    }

    /**
     * Serve a host read.
     *
     * Order matters: any outstanding data container is drained first, then the
     * response. Sending the response early makes the client treat the
     * transaction as finished and discard the payload.
     */
    private fun readIn(requested: Int): ByteArray {
        if (pendingDataOffset < pendingData.size) {
            val n = minOf(requested, pendingData.size - pendingDataOffset)
            val chunk = pendingData.copyOfRange(pendingDataOffset, pendingDataOffset + n)
            pendingDataOffset += n
            if (pendingDataOffset >= pendingData.size) {
                pendingData = ByteArray(0)
                pendingDataOffset = 0
            }
            return chunk
        }

        pendingResponse?.let { response ->
            pendingResponse = null
            currentCommand = null
            return response.encode()
        }

        return ByteArray(0)
    }

    /** Handle a host write: either a new command, or data for the current one. */
    private fun writeOut(payload: ByteArray): UrbResult {
        // Mid-collection of a data container?
        if (incomingExpected > 0) {
            incoming += payload
            if (incoming.size >= incomingExpected) {
                finishIncomingData()
            }
            return UrbResult.OK
        }

        val container = PtpContainer.decode(payload) ?: return UrbResult.stall()

        return when (container.type) {
            MtpConstants.CONTAINER_COMMAND -> {
                currentCommand = container
                dispatch(container)
                UrbResult.OK
            }

            MtpConstants.CONTAINER_DATA -> {
                // The declared length covers the header; collect until we have
                // it all, since a large payload arrives across several URBs.
                val declared = declaredLength(payload)
                incomingExpected = declared
                incoming = payload
                if (incoming.size >= declared) finishIncomingData()
                UrbResult.OK
            }

            else -> UrbResult.stall()
        }
    }

    private fun declaredLength(payload: ByteArray): Int {
        val length = PtpCodec.Reader(payload).u32()
        return if (length in MtpConstants.CONTAINER_HEADER_SIZE..MAX_DATA_BYTES) {
            length
        } else {
            payload.size
        }
    }

    private fun finishIncomingData() {
        val command = currentCommand
        val body = incoming.copyOfRange(
            MtpConstants.CONTAINER_HEADER_SIZE.coerceAtMost(incoming.size),
            incoming.size,
        )
        incoming = ByteArray(0)
        incomingExpected = 0

        if (command == null) {
            pendingResponse = PtpContainer.response(MtpConstants.RESP_GENERAL_ERROR, 0)
            return
        }
        applyReply(session.handleData(command, body), command)
    }

    private fun dispatch(command: PtpContainer) {
        when (val reply = session.handleCommand(command)) {
            // Nothing to queue: the host will send a data container next.
            is MtpSession.Reply.AwaitData -> Unit
            else -> applyReply(reply, command)
        }
    }

    private fun applyReply(reply: MtpSession.Reply, command: PtpContainer) {
        when (reply) {
            is MtpSession.Reply.Status -> {
                pendingResponse = PtpContainer.response(
                    reply.code, command.transactionId, *reply.params.toIntArray(),
                )
            }

            is MtpSession.Reply.Data -> {
                pendingData = PtpContainer
                    .data(command.code, command.transactionId, reply.payload)
                    .encode()
                pendingDataOffset = 0
                pendingResponse = PtpContainer.response(
                    MtpConstants.RESP_OK, command.transactionId, *reply.params.toIntArray(),
                )
            }

            MtpSession.Reply.AwaitData -> Unit
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun clip(data: ByteArray, length: Int): ByteArray =
        if (length in 0 until data.size) data.copyOf(length) else data

    companion object {
        /** Same virtual bus as the mass-storage device, a different devnum. */
        const val VIRTUAL_BUSNUM = 99

        /** Sanity bound on a declared data length, to reject a corrupt header. */
        const val MAX_DATA_BYTES = 64 * 1024 * 1024

        private const val REQ_GET_STATUS = 0x00
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val REQ_SET_FEATURE = 0x03
        private const val REQ_SET_ADDRESS = 0x05
        private const val REQ_GET_DESCRIPTOR = 0x06
        private const val REQ_GET_CONFIGURATION = 0x08
        private const val REQ_SET_CONFIGURATION = 0x09
        private const val REQ_GET_INTERFACE = 0x0A
        private const val REQ_SET_INTERFACE = 0x0B

        // PTP class-specific requests
        private const val PTP_CANCEL_REQUEST = 0x64
        private const val PTP_GET_DEVICE_STATUS = 0x67
        private const val PTP_DEVICE_RESET = 0x66

        fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}
