package io.openusbip.server.protocol

import io.openusbip.server.protocol.Wire.getFixedString
import io.openusbip.server.protocol.Wire.getU16
import io.openusbip.server.protocol.Wire.getU32
import io.openusbip.server.protocol.Wire.getU8
import io.openusbip.server.protocol.Wire.putFixedString
import io.openusbip.server.protocol.Wire.putPadding
import io.openusbip.server.protocol.Wire.putU16
import io.openusbip.server.protocol.Wire.putU32
import io.openusbip.server.protocol.Wire.putU8
import io.openusbip.server.protocol.Wire.skip
import java.nio.ByteBuffer

/** One entry of a device's interface list, as sent in OP_REP_DEVLIST. */
data class UsbipInterface(
    val interfaceClass: Int,
    val interfaceSubClass: Int,
    val interfaceProtocol: Int,
) {
    fun encodeInto(buf: ByteBuffer) {
        buf.putU8(interfaceClass)
        buf.putU8(interfaceSubClass)
        buf.putU8(interfaceProtocol)
        buf.putPadding(1)
    }

    companion object {
        fun decodeFrom(buf: ByteBuffer): UsbipInterface {
            val i = UsbipInterface(buf.getU8(), buf.getU8(), buf.getU8())
            buf.skip(1)
            return i
        }
    }
}

/**
 * `struct usbip_usb_device` -- the device description exchanged during the op
 * phase. 312 bytes on the wire.
 */
data class UsbipDevice(
    val path: String,
    val busid: String,
    val busnum: Int,
    val devnum: Int,
    val speed: Int,
    val idVendor: Int,
    val idProduct: Int,
    val bcdDevice: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val configurationValue: Int,
    val numConfigurations: Int,
    val numInterfaces: Int,
    val interfaces: List<UsbipInterface> = emptyList(),
) {
    val devid: Int get() = Protocol.devid(busnum, devnum)

    fun encodeInto(buf: ByteBuffer) {
        buf.putFixedString(path, Protocol.PATH_LEN)
        buf.putFixedString(busid, Protocol.BUSID_LEN)
        buf.putU32(busnum)
        buf.putU32(devnum)
        buf.putU32(speed)
        buf.putU16(idVendor)
        buf.putU16(idProduct)
        buf.putU16(bcdDevice)
        buf.putU8(deviceClass)
        buf.putU8(deviceSubClass)
        buf.putU8(deviceProtocol)
        buf.putU8(configurationValue)
        buf.putU8(numConfigurations)
        buf.putU8(numInterfaces)
    }

    /** The device record only; interfaces are appended separately by DEVLIST. */
    fun encode(): ByteArray {
        val buf = Wire.buffer(Protocol.USB_DEVICE_SIZE)
        encodeInto(buf)
        return buf.array()
    }

    companion object {
        fun decodeFrom(buf: ByteBuffer): UsbipDevice = UsbipDevice(
            path = buf.getFixedString(Protocol.PATH_LEN),
            busid = buf.getFixedString(Protocol.BUSID_LEN),
            busnum = buf.getU32(),
            devnum = buf.getU32(),
            speed = buf.getU32(),
            idVendor = buf.getU16(),
            idProduct = buf.getU16(),
            bcdDevice = buf.getU16(),
            deviceClass = buf.getU8(),
            deviceSubClass = buf.getU8(),
            deviceProtocol = buf.getU8(),
            configurationValue = buf.getU8(),
            numConfigurations = buf.getU8(),
            numInterfaces = buf.getU8(),
        )
    }
}

/** The 8-byte op-phase header that prefixes DEVLIST and IMPORT messages. */
data class OpHeader(val version: Int, val code: Int, val status: Int) {

    fun encode(): ByteArray {
        val buf = Wire.buffer(Protocol.OP_HEADER_SIZE)
        buf.putU16(version)
        buf.putU16(code)
        buf.putU32(status)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): OpHeader {
            val buf = Wire.reader(bytes)
            return OpHeader(buf.getU16(), buf.getU16(), buf.getU32())
        }
    }
}

/** Builders for the replies a server sends during the op phase. */
object OpReply {

    /** OP_REP_DEVLIST: header, device count, then each device with interfaces. */
    fun devlist(devices: List<UsbipDevice>): ByteArray {
        val size = Protocol.OP_HEADER_SIZE + 4 +
            devices.sumOf {
                Protocol.USB_DEVICE_SIZE + it.interfaces.size * Protocol.USB_INTERFACE_SIZE
            }
        val buf = Wire.buffer(size)
        buf.putU16(Protocol.VERSION)
        buf.putU16(Protocol.OP_REP_DEVLIST)
        buf.putU32(Protocol.ST_OK)
        buf.putU32(devices.size)
        for (device in devices) {
            device.encodeInto(buf)
            device.interfaces.forEach { it.encodeInto(buf) }
        }
        return buf.array()
    }

    /**
     * OP_REP_IMPORT on success: header followed by the device record.
     *
     * Note the asymmetry with [importFailure]: the kernel client reads the
     * device record only when status is OK, so a failure reply must be the
     * bare 8-byte header. Sending 312 zero bytes after a failure header
     * desynchronises the client.
     */
    fun importSuccess(device: UsbipDevice): ByteArray {
        val buf = Wire.buffer(Protocol.OP_HEADER_SIZE + Protocol.USB_DEVICE_SIZE)
        buf.putU16(Protocol.VERSION)
        buf.putU16(Protocol.OP_REP_IMPORT)
        buf.putU32(Protocol.ST_OK)
        device.encodeInto(buf)
        return buf.array()
    }

    fun importFailure(): ByteArray =
        OpHeader(Protocol.VERSION, Protocol.OP_REP_IMPORT, Protocol.ST_NA).encode()
}

/** Shared first 20 bytes of every URB-phase message. */
data class BasicHeader(
    val command: Int,
    val seqnum: Int,
    val devid: Int,
    val direction: Int,
    val ep: Int,
) {
    val isIn: Boolean get() = direction == Protocol.DIR_IN

    fun encodeInto(buf: ByteBuffer) {
        buf.putU32(command)
        buf.putU32(seqnum)
        buf.putU32(devid)
        buf.putU32(direction)
        buf.putU32(ep)
    }

    companion object {
        fun decodeFrom(buf: ByteBuffer): BasicHeader = BasicHeader(
            command = buf.getU32(),
            seqnum = buf.getU32(),
            devid = buf.getU32(),
            direction = buf.getU32(),
            ep = buf.getU32(),
        )
    }
}

/**
 * USBIP_CMD_SUBMIT: the client asking us to run one URB.
 *
 * [transferBufferLength] is how many bytes the client expects to move. For an
 * OUT transfer that many payload bytes follow the 48-byte header on the wire;
 * for IN, nothing follows and we owe the client that many bytes back.
 */
data class CmdSubmit(
    val header: BasicHeader,
    val transferFlags: Int,
    val transferBufferLength: Int,
    val startFrame: Int,
    val numberOfPackets: Int,
    val interval: Int,
    val setup: ByteArray,
) {
    /**
     * Isochronous URBs carry per-packet descriptors and cannot be serviced
     * through Android's USB API at all, so they are detected and refused
     * rather than silently mishandled.
     */
    val isIsochronous: Boolean get() = numberOfPackets > 0 && numberOfPackets != -1

    val isControl: Boolean get() = header.ep == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CmdSubmit) return false
        return header == other.header &&
            transferFlags == other.transferFlags &&
            transferBufferLength == other.transferBufferLength &&
            startFrame == other.startFrame &&
            numberOfPackets == other.numberOfPackets &&
            interval == other.interval &&
            setup.contentEquals(other.setup)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + transferFlags
        result = 31 * result + transferBufferLength
        result = 31 * result + startFrame
        result = 31 * result + numberOfPackets
        result = 31 * result + interval
        result = 31 * result + setup.contentHashCode()
        return result
    }

    companion object {
        /** Decode a full 48-byte CMD_SUBMIT header. */
        fun decode(bytes: ByteArray): CmdSubmit {
            require(bytes.size >= Protocol.URB_HEADER_SIZE) {
                "CMD_SUBMIT header must be ${Protocol.URB_HEADER_SIZE} bytes, got ${bytes.size}"
            }
            val buf = Wire.reader(bytes)
            val header = BasicHeader.decodeFrom(buf)
            val flags = buf.getU32()
            val length = buf.getU32()
            val startFrame = buf.getU32()
            val packets = buf.getU32()
            val interval = buf.getU32()
            val setup = ByteArray(8)
            buf.get(setup)
            return CmdSubmit(header, flags, length, startFrame, packets, interval, setup)
        }
    }
}

/** USBIP_RET_SUBMIT: our answer to one URB. */
data class RetSubmit(
    val seqnum: Int,
    val devid: Int,
    val direction: Int,
    val ep: Int,
    val status: Int,
    val actualLength: Int,
    val startFrame: Int = 0,
    val numberOfPackets: Int = 0,
    val errorCount: Int = 0,
) {
    /** The 48-byte header. Any IN payload is written separately after it. */
    fun encode(): ByteArray {
        val buf = Wire.buffer(Protocol.URB_HEADER_SIZE)
        BasicHeader(Protocol.RET_SUBMIT, seqnum, devid, direction, ep).encodeInto(buf)
        buf.putU32(status)
        buf.putU32(actualLength)
        buf.putU32(startFrame)
        buf.putU32(numberOfPackets)
        buf.putU32(errorCount)
        buf.putPadding(8)
        return buf.array()
    }

    companion object {
        /** Build the reply to [cmd] carrying no data, e.g. an error. */
        fun error(cmd: CmdSubmit, status: Int): RetSubmit = RetSubmit(
            seqnum = cmd.header.seqnum,
            devid = cmd.header.devid,
            direction = cmd.header.direction,
            ep = cmd.header.ep,
            status = status,
            actualLength = 0,
        )

        fun success(cmd: CmdSubmit, actualLength: Int): RetSubmit = RetSubmit(
            seqnum = cmd.header.seqnum,
            devid = cmd.header.devid,
            direction = cmd.header.direction,
            ep = cmd.header.ep,
            status = 0,
            actualLength = actualLength,
        )
    }
}

/** USBIP_CMD_UNLINK: cancel a previously submitted URB. */
data class CmdUnlink(val header: BasicHeader, val unlinkSeqnum: Int) {
    companion object {
        fun decode(bytes: ByteArray): CmdUnlink {
            require(bytes.size >= Protocol.URB_HEADER_SIZE) {
                "CMD_UNLINK header must be ${Protocol.URB_HEADER_SIZE} bytes, got ${bytes.size}"
            }
            val buf = Wire.reader(bytes)
            val header = BasicHeader.decodeFrom(buf)
            return CmdUnlink(header, buf.getU32())
        }
    }
}

/** USBIP_RET_UNLINK. */
data class RetUnlink(val seqnum: Int, val devid: Int, val status: Int) {
    fun encode(): ByteArray {
        val buf = Wire.buffer(Protocol.URB_HEADER_SIZE)
        BasicHeader(Protocol.RET_UNLINK, seqnum, devid, Protocol.DIR_OUT, 0).encodeInto(buf)
        buf.putU32(status)
        buf.putPadding(24)
        return buf.array()
    }
}
