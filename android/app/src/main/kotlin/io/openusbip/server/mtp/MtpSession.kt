package io.openusbip.server.mtp

import java.io.File

/**
 * Executes MTP operations against an [ObjectStore].
 *
 * Transport-free by design: this takes a decoded command container and returns
 * what to send back, so it can be driven from a desktop test without any USB
 * involved. [MtpBackend] does the framing.
 *
 * A transaction is Command -> optional Data -> Response. Operations that carry
 * data *to* the device (SendObjectInfo, SendObject) cannot complete on the
 * command alone, so they park state here and finish when the data arrives.
 */
class MtpSession(
    private val store: ObjectStore,
    private val deviceName: String = "Android",
    private val serial: String = "OPENUSBIP0001",
) {

    /** What the transport should do next. */
    sealed interface Reply {
        /** Response only. */
        data class Status(val code: Int, val params: List<Int> = emptyList()) : Reply

        /** Data container, then an OK response. */
        data class Data(val payload: ByteArray, val params: List<Int> = emptyList()) : Reply {
            override fun equals(other: Any?) = other is Data &&
                payload.contentEquals(other.payload) && params == other.params
            override fun hashCode() = 31 * payload.contentHashCode() + params.hashCode()
        }

        /** Device expects a data container from the host before responding. */
        data object AwaitData : Reply
    }

    var sessionOpen: Boolean = false
        private set

    /** Set by SendObjectInfo, consumed by the following SendObject. */
    private var pendingSend: PendingSend? = null

    private data class PendingSend(val handle: Int, val file: File, val declaredSize: Long)

    // --- command entry point ---------------------------------------------

    fun handleCommand(command: PtpContainer): Reply {
        val op = command.code

        // OpenSession and GetDeviceInfo are the only operations legal before a
        // session exists; everything else must be told to open one first.
        if (!sessionOpen && op != MtpConstants.OP_OPEN_SESSION &&
            op != MtpConstants.OP_GET_DEVICE_INFO
        ) {
            return Reply.Status(MtpConstants.RESP_SESSION_NOT_OPEN)
        }

        return when (op) {
            MtpConstants.OP_GET_DEVICE_INFO -> Reply.Data(deviceInfo())
            MtpConstants.OP_OPEN_SESSION -> openSession(command.param(0))
            MtpConstants.OP_CLOSE_SESSION -> closeSession()
            MtpConstants.OP_GET_STORAGE_IDS -> Reply.Data(
                PtpCodec.Writer().u32Array(intArrayOf(MtpConstants.STORAGE_ID)).toByteArray(),
            )
            MtpConstants.OP_GET_STORAGE_INFO -> storageInfo(command.param(0))
            MtpConstants.OP_GET_NUM_OBJECTS -> numObjects(command)
            MtpConstants.OP_GET_OBJECT_HANDLES -> objectHandles(command)
            MtpConstants.OP_GET_OBJECT_INFO -> objectInfo(command.param(0))
            MtpConstants.OP_GET_OBJECT -> getObject(command.param(0))
            MtpConstants.OP_GET_PARTIAL_OBJECT -> getPartialObject(command)
            MtpConstants.OP_DELETE_OBJECT -> deleteObject(command.param(0))
            MtpConstants.OP_SEND_OBJECT_INFO -> Reply.AwaitData
            MtpConstants.OP_SEND_OBJECT -> Reply.AwaitData
            MtpConstants.OP_GET_OBJECT_PROPS_SUPPORTED -> Reply.Data(
                PtpCodec.Writer().u16Array(SUPPORTED_OBJECT_PROPS).toByteArray(),
            )
            MtpConstants.OP_GET_OBJECT_PROP_VALUE -> objectPropValue(command.param(0), command.param(1))
            MtpConstants.OP_GET_OBJECT_PROP_DESC -> objectPropDesc(command.param(0))
            MtpConstants.OP_GET_OBJECT_REFERENCES -> Reply.Data(
                // No object has references; an empty array is the correct answer.
                PtpCodec.Writer().u32Array(IntArray(0)).toByteArray(),
            )
            MtpConstants.OP_GET_DEVICE_PROP_VALUE -> devicePropValue(command.param(0))
            else -> Reply.Status(MtpConstants.RESP_OPERATION_NOT_SUPPORTED)
        }
    }

    /**
     * Handle the data phase of an operation that carries data to the device.
     * Called once the host's data container has arrived.
     */
    fun handleData(command: PtpContainer, payload: ByteArray): Reply = when (command.code) {
        MtpConstants.OP_SEND_OBJECT_INFO -> sendObjectInfo(command, payload)
        MtpConstants.OP_SEND_OBJECT -> sendObject(payload)
        else -> Reply.Status(MtpConstants.RESP_OPERATION_NOT_SUPPORTED)
    }

    // --- session ----------------------------------------------------------

    private fun openSession(id: Int): Reply {
        if (id == 0) return Reply.Status(MtpConstants.RESP_INVALID_TRANSACTION_ID)
        if (sessionOpen) return Reply.Status(MtpConstants.RESP_SESSION_ALREADY_OPEN)
        sessionOpen = true
        store.ensureRootExists()
        return Reply.Status(MtpConstants.RESP_OK)
    }

    private fun closeSession(): Reply {
        sessionOpen = false
        pendingSend = null
        // Handles are per-session, so a reconnecting client gets fresh ones.
        store.clear()
        return Reply.Status(MtpConstants.RESP_OK)
    }

    // --- device and storage ----------------------------------------------

    private fun deviceInfo(): ByteArray {
        val w = PtpCodec.Writer(512)
        w.u16(100)                                  // standard version 1.00
        w.u32(0x00000006)                           // MTP vendor extension id
        w.u16(100)                                  // MTP version
        w.string("microsoft.com: 1.0;")             // extensions
        w.u16(0)                                    // functional mode: standard
        w.u16Array(SUPPORTED_OPERATIONS)
        w.u16Array(IntArray(0))                     // events
        w.u16Array(SUPPORTED_DEVICE_PROPS)
        w.u16Array(IntArray(0))                     // capture formats
        w.u16Array(SUPPORTED_PLAYBACK_FORMATS)
        w.string("OpenUSBIP")                       // manufacturer
        w.string(deviceName)                        // model
        w.string("0.1.0")                           // device version
        w.string(serial)
        return w.toByteArray()
    }

    private fun storageInfo(storageId: Int): Reply {
        if (storageId != MtpConstants.STORAGE_ID) {
            return Reply.Status(MtpConstants.RESP_INVALID_STORAGE_ID)
        }
        val w = PtpCodec.Writer(128)
        w.u16(MtpConstants.STORAGE_TYPE_FIXED_RAM)
        w.u16(MtpConstants.FILESYSTEM_TYPE_HIERARCHICAL)
        w.u16(MtpConstants.ACCESS_READ_WRITE)
        w.u64(store.capacityBytes)
        w.u64(store.freeBytes)
        w.u32(0xFFFFFFFF.toInt())                   // free objects: unknown
        w.string("Internal shared storage")         // storage description
        w.string("")                                // volume identifier
        return Reply.Data(w.toByteArray())
    }

    private fun devicePropValue(prop: Int): Reply = when (prop) {
        MtpConstants.DEVICE_PROP_DEVICE_FRIENDLY_NAME ->
            Reply.Data(PtpCodec.Writer().string(deviceName).toByteArray())
        MtpConstants.DEVICE_PROP_BATTERY_LEVEL ->
            Reply.Data(PtpCodec.Writer().u8(100).toByteArray())
        else -> Reply.Status(MtpConstants.RESP_DEVICE_PROP_NOT_SUPPORTED)
    }

    // --- browsing ---------------------------------------------------------

    private fun entriesFor(parent: Int): List<ObjectStore.Entry> =
        if (parent == MtpConstants.HANDLE_ALL) store.allEntries() else store.children(parent)

    private fun numObjects(command: PtpContainer): Reply {
        val parent = command.param(2)
        return Reply.Status(MtpConstants.RESP_OK, listOf(entriesFor(parent).size))
    }

    private fun objectHandles(command: PtpContainer): Reply {
        // params: storageId, formatCode, parentHandle
        val storageId = command.param(0)
        if (storageId != 0 && storageId != MtpConstants.STORAGE_ID &&
            storageId != 0xFFFFFFFF.toInt()
        ) {
            return Reply.Status(MtpConstants.RESP_INVALID_STORAGE_ID)
        }

        val handles = entriesFor(command.param(2)).map { it.handle }.toIntArray()
        return Reply.Data(PtpCodec.Writer().u32Array(handles).toByteArray())
    }

    private fun objectInfo(handle: Int): Reply {
        val entry = store.entry(handle)
            ?: return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)

        val isDir = entry.isDirectory
        val w = PtpCodec.Writer(256)
        w.u32(MtpConstants.STORAGE_ID)
        w.u16(ObjectStore.formatFor(entry.file))
        w.u16(MtpConstants.PROTECTION_NONE)
        // ObjectCompressedSize is 32-bit here; a file over 4 GB has to be
        // reported through the 64-bit ObjectSize property instead.
        w.u32(entry.size.coerceAtMost(0xFFFFFFFFL).toInt())
        // ThumbFormat(2) + ThumbSize, ThumbW, ThumbH, ImageW, ImageH,
        // BitDepth (6 x 4) = 26 bytes, putting ParentObject at offset 38 and
        // Filename at 52 as the spec requires. Writing image width/height
        // again here is the easy mistake -- it shifts the filename to 64 and
        // every string after it becomes unreadable.
        w.u16(0).u32(0).u32(0).u32(0).u32(0).u32(0).u32(0)
        w.u32(entry.parent)
        w.u16(if (isDir) MtpConstants.ASSOCIATION_GENERIC_FOLDER else 0)
        w.u32(0)                                              // association desc
        w.u32(0)                                              // sequence number
        w.string(entry.name)
        w.string(PtpCodec.formatTimestamp(entry.modified))    // date created
        w.string(PtpCodec.formatTimestamp(entry.modified))    // date modified
        w.string("")                                          // keywords
        return Reply.Data(w.toByteArray())
    }

    private fun objectPropValue(handle: Int, prop: Int): Reply {
        val entry = store.entry(handle)
            ?: return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)

        val w = PtpCodec.Writer(64)
        when (prop) {
            MtpConstants.PROP_STORAGE_ID -> w.u32(MtpConstants.STORAGE_ID)
            MtpConstants.PROP_OBJECT_FORMAT -> w.u16(ObjectStore.formatFor(entry.file))
            MtpConstants.PROP_PROTECTION_STATUS -> w.u16(MtpConstants.PROTECTION_NONE)
            MtpConstants.PROP_OBJECT_SIZE -> w.u64(entry.size)
            MtpConstants.PROP_OBJECT_FILE_NAME -> w.string(entry.name)
            MtpConstants.PROP_NAME, MtpConstants.PROP_DISPLAY_NAME -> w.string(entry.name)
            MtpConstants.PROP_PARENT_OBJECT -> w.u32(entry.parent)
            MtpConstants.PROP_DATE_MODIFIED, MtpConstants.PROP_DATE_CREATED ->
                w.string(PtpCodec.formatTimestamp(entry.modified))
            MtpConstants.PROP_PERSISTENT_UID -> w.u128(entry.handle.toLong(), 0L)
            else -> return Reply.Status(MtpConstants.RESP_OBJECT_PROP_NOT_SUPPORTED)
        }
        return Reply.Data(w.toByteArray())
    }

    private fun objectPropDesc(prop: Int): Reply {
        val type = when (prop) {
            MtpConstants.PROP_STORAGE_ID, MtpConstants.PROP_PARENT_OBJECT -> MtpConstants.TYPE_UINT32
            MtpConstants.PROP_OBJECT_FORMAT, MtpConstants.PROP_PROTECTION_STATUS -> MtpConstants.TYPE_UINT16
            MtpConstants.PROP_OBJECT_SIZE -> MtpConstants.TYPE_UINT64
            MtpConstants.PROP_PERSISTENT_UID -> MtpConstants.TYPE_UINT128
            MtpConstants.PROP_OBJECT_FILE_NAME, MtpConstants.PROP_NAME,
            MtpConstants.PROP_DISPLAY_NAME, MtpConstants.PROP_DATE_MODIFIED,
            MtpConstants.PROP_DATE_CREATED,
            -> MtpConstants.TYPE_STR
            else -> return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_PROP_CODE)
        }

        val writable = prop == MtpConstants.PROP_OBJECT_FILE_NAME
        val w = PtpCodec.Writer(64)
        w.u16(prop)
        w.u16(type)
        w.u8(if (writable) 1 else 0)
        // Default value, typed to match.
        when (type) {
            MtpConstants.TYPE_UINT16 -> w.u16(0)
            MtpConstants.TYPE_UINT32 -> w.u32(0)
            MtpConstants.TYPE_UINT64 -> w.u64(0)
            MtpConstants.TYPE_UINT128 -> w.u128(0, 0)
            else -> w.string("")
        }
        w.u32(0)      // group code
        w.u8(0)       // form flag: none
        return Reply.Data(w.toByteArray())
    }

    // --- reading ----------------------------------------------------------

    private fun getObject(handle: Int): Reply {
        val entry = store.entry(handle)
            ?: return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)
        if (entry.isDirectory) return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)

        return try {
            Reply.Data(entry.file.readBytes())
        } catch (e: Exception) {
            Reply.Status(MtpConstants.RESP_ACCESS_DENIED)
        }
    }

    private fun getPartialObject(command: PtpContainer): Reply {
        val entry = store.entry(command.param(0))
            ?: return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)
        if (entry.isDirectory) return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)

        val offset = command.param(1).toLong() and 0xFFFFFFFFL
        val requested = command.param(2).toLong() and 0xFFFFFFFFL
        if (offset >= entry.size) {
            return Reply.Data(ByteArray(0), listOf(0))
        }

        val count = minOf(requested, entry.size - offset).toInt()
        return try {
            val chunk = ByteArray(count)
            entry.file.inputStream().use { stream ->
                stream.skip(offset)
                var read = 0
                while (read < count) {
                    val n = stream.read(chunk, read, count - read)
                    if (n < 0) break
                    read += n
                }
            }
            // The response parameter reports how much actually came back.
            Reply.Data(chunk, listOf(count))
        } catch (e: Exception) {
            Reply.Status(MtpConstants.RESP_ACCESS_DENIED)
        }
    }

    // --- writing ----------------------------------------------------------

    private fun sendObjectInfo(command: PtpContainer, payload: ByteArray): Reply {
        val requestedParent = command.param(1)
        val info = parseObjectInfo(payload)
            ?: return Reply.Status(MtpConstants.RESP_NO_VALID_OBJECT_INFO)

        if (!ObjectStore.isSafeName(info.name)) {
            return Reply.Status(MtpConstants.RESP_ACCESS_DENIED)
        }

        val isDirectory = info.format == MtpConstants.FORMAT_ASSOCIATION
        val parent = if (requestedParent == 0) MtpConstants.HANDLE_ROOT else requestedParent

        val handle = store.create(parent, info.name, isDirectory)
            ?: return Reply.Status(MtpConstants.RESP_INVALID_PARENT_OBJECT)

        // A directory is complete already; a file waits for SendObject.
        pendingSend = if (isDirectory) {
            null
        } else {
            PendingSend(handle, store.fileFor(handle)!!, info.size)
        }

        // Response parameters: storage, parent, new handle. Clients rely on
        // the third to address the SendObject that follows.
        return Reply.Status(
            MtpConstants.RESP_OK,
            listOf(MtpConstants.STORAGE_ID, parent, handle),
        )
    }

    private fun sendObject(payload: ByteArray): Reply {
        val pending = pendingSend
            ?: return Reply.Status(MtpConstants.RESP_NO_VALID_OBJECT_INFO)
        pendingSend = null

        return try {
            pending.file.writeBytes(payload)
            Reply.Status(MtpConstants.RESP_OK)
        } catch (e: Exception) {
            Reply.Status(MtpConstants.RESP_STORE_FULL)
        }
    }

    private fun deleteObject(handle: Int): Reply {
        if (handle == MtpConstants.HANDLE_ALL || handle == MtpConstants.HANDLE_ROOT) {
            // Deleting "everything" is legal MTP but far too blunt to offer.
            return Reply.Status(MtpConstants.RESP_ACCESS_DENIED)
        }
        store.entry(handle) ?: return Reply.Status(MtpConstants.RESP_INVALID_OBJECT_HANDLE)
        return if (store.delete(handle)) {
            Reply.Status(MtpConstants.RESP_OK)
        } else {
            Reply.Status(MtpConstants.RESP_ACCESS_DENIED)
        }
    }

    /** Only the fields needed to create the object; the rest is advisory. */
    private data class ParsedInfo(val format: Int, val size: Long, val name: String)

    private fun parseObjectInfo(payload: ByteArray): ParsedInfo? {
        // storage(4) format(2) protection(2) size(4) then thumb/image fields,
        // parent(4) assoc(2) assocDesc(4) seq(4), then the filename string.
        if (payload.size < 52) return null
        return try {
            val r = PtpCodec.Reader(payload)
            r.u32()                       // storage id
            val format = r.u16()
            r.u16()                       // protection
            val size = r.u32().toLong() and 0xFFFFFFFFL
            // Matches the encoder above: u16 + 6 x u32 covers thumb and image
            // fields through BitDepth, so ParentObject is next.
            r.u16(); repeat(6) { r.u32() }
            r.u32()                       // parent
            r.u16()                       // association type
            r.u32()                       // association description
            r.u32()                       // sequence number
            val name = r.string()
            if (name.isEmpty()) null else ParsedInfo(format, size, name)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        val SUPPORTED_OPERATIONS = intArrayOf(
            MtpConstants.OP_GET_DEVICE_INFO,
            MtpConstants.OP_OPEN_SESSION,
            MtpConstants.OP_CLOSE_SESSION,
            MtpConstants.OP_GET_STORAGE_IDS,
            MtpConstants.OP_GET_STORAGE_INFO,
            MtpConstants.OP_GET_NUM_OBJECTS,
            MtpConstants.OP_GET_OBJECT_HANDLES,
            MtpConstants.OP_GET_OBJECT_INFO,
            MtpConstants.OP_GET_OBJECT,
            MtpConstants.OP_GET_PARTIAL_OBJECT,
            MtpConstants.OP_DELETE_OBJECT,
            MtpConstants.OP_SEND_OBJECT_INFO,
            MtpConstants.OP_SEND_OBJECT,
            MtpConstants.OP_GET_DEVICE_PROP_VALUE,
            MtpConstants.OP_GET_OBJECT_PROPS_SUPPORTED,
            MtpConstants.OP_GET_OBJECT_PROP_DESC,
            MtpConstants.OP_GET_OBJECT_PROP_VALUE,
            MtpConstants.OP_GET_OBJECT_REFERENCES,
        )

        val SUPPORTED_DEVICE_PROPS = intArrayOf(
            MtpConstants.DEVICE_PROP_DEVICE_FRIENDLY_NAME,
            MtpConstants.DEVICE_PROP_BATTERY_LEVEL,
        )

        val SUPPORTED_PLAYBACK_FORMATS = intArrayOf(
            MtpConstants.FORMAT_UNDEFINED,
            MtpConstants.FORMAT_ASSOCIATION,
            MtpConstants.FORMAT_TEXT,
            MtpConstants.FORMAT_EXIF_JPEG,
            MtpConstants.FORMAT_PNG,
            MtpConstants.FORMAT_MP3,
            MtpConstants.FORMAT_MP4,
        )

        val SUPPORTED_OBJECT_PROPS = intArrayOf(
            MtpConstants.PROP_STORAGE_ID,
            MtpConstants.PROP_OBJECT_FORMAT,
            MtpConstants.PROP_PROTECTION_STATUS,
            MtpConstants.PROP_OBJECT_SIZE,
            MtpConstants.PROP_OBJECT_FILE_NAME,
            MtpConstants.PROP_DATE_MODIFIED,
            MtpConstants.PROP_PARENT_OBJECT,
            MtpConstants.PROP_PERSISTENT_UID,
            MtpConstants.PROP_NAME,
        )
    }
}
