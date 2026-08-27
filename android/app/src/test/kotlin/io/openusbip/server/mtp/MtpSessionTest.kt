package io.openusbip.server.mtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** MTP operation tests, driven with decoded containers against a temp dir. */
class MtpSessionTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ObjectStore
    private lateinit var session: MtpSession
    private var txn = 0

    @Before
    fun setUp() {
        root = temp.newFolder("shared")
        File(root, "notes.txt").writeText("hello world")
        File(root, "Music").mkdirs()
        File(root, "Music/song.mp3").writeText("audio-bytes")
        store = ObjectStore(root)
        session = MtpSession(store, deviceName = "TestPhone")
        open()
    }

    private fun cmd(op: Int, vararg params: Int) = PtpContainer(
        MtpConstants.CONTAINER_COMMAND, op, ++txn, params.toList(),
    )

    private fun open() {
        session.handleCommand(cmd(MtpConstants.OP_OPEN_SESSION, 1))
    }

    private fun dataOf(reply: MtpSession.Reply): ByteArray =
        (reply as MtpSession.Reply.Data).payload

    private fun statusOf(reply: MtpSession.Reply): Int =
        (reply as MtpSession.Reply.Status).code

    private fun handles(parent: Int): IntArray =
        PtpCodec.Reader(
            dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_HANDLES, MtpConstants.STORAGE_ID, 0, parent))),
        ).u32Array()

    private fun nameOf(handle: Int): String {
        val r = PtpCodec.Reader(dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_INFO, handle))))
        r.skip(52)
        return r.string()
    }

    // --- session gating ---------------------------------------------------

    @Test
    fun `operations before a session are refused`() {
        val fresh = MtpSession(ObjectStore(root))
        assertEquals(
            MtpConstants.RESP_SESSION_NOT_OPEN,
            statusOf(fresh.handleCommand(cmd(MtpConstants.OP_GET_STORAGE_IDS))),
        )
    }

    @Test
    fun `GetDeviceInfo works before a session, as the spec requires`() {
        val fresh = MtpSession(ObjectStore(root))
        assertTrue(fresh.handleCommand(cmd(MtpConstants.OP_GET_DEVICE_INFO)) is MtpSession.Reply.Data)
    }

    @Test
    fun `opening twice is reported, not silently accepted`() {
        assertEquals(
            MtpConstants.RESP_SESSION_ALREADY_OPEN,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_OPEN_SESSION, 1))),
        )
    }

    @Test
    fun `session zero is invalid`() {
        val fresh = MtpSession(ObjectStore(root))
        assertEquals(
            MtpConstants.RESP_INVALID_TRANSACTION_ID,
            statusOf(fresh.handleCommand(cmd(MtpConstants.OP_OPEN_SESSION, 0))),
        )
    }

    @Test
    fun `closing a session drops handles so a reconnect gets fresh ones`() {
        handles(MtpConstants.HANDLE_ROOT)
        assertTrue(store.size() > 0)
        session.handleCommand(cmd(MtpConstants.OP_CLOSE_SESSION))
        assertFalse(session.sessionOpen)
        assertEquals(0, store.size())
    }

    // --- device info ------------------------------------------------------

    @Test
    fun `device info advertises MTP and the operations we implement`() {
        val r = PtpCodec.Reader(dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_DEVICE_INFO))))
        assertEquals(100, r.u16())              // standard version
        assertEquals(6, r.u32())                // MTP vendor extension id
        assertEquals(100, r.u16())              // MTP version
        assertTrue(r.string().contains("microsoft.com"))
        r.u16()                                 // functional mode
        val ops = r.u16Array()                  // operations are UINT16
        assertTrue(ops.contains(MtpConstants.OP_GET_OBJECT))
        assertTrue(ops.contains(MtpConstants.OP_SEND_OBJECT))
        assertTrue(ops.contains(MtpConstants.OP_GET_OBJECT_HANDLES))
    }

    @Test
    fun `device info carries the manufacturer, model, version and serial`() {
        val r = PtpCodec.Reader(dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_DEVICE_INFO))))
        r.u16(); r.u32(); r.u16(); r.string(); r.u16()   // header through functional mode
        r.u16Array()   // operations
        r.u16Array()   // events
        r.u16Array()   // device properties
        r.u16Array()   // capture formats
        r.u16Array()   // playback formats

        assertEquals("OpenUSBIP", r.string())
        assertEquals("TestPhone", r.string())
        assertEquals("0.1.0", r.string())
        assertEquals("OPENUSBIP0001", r.string())
    }

    // --- storage ----------------------------------------------------------

    @Test
    fun `storage ids reports exactly one storage`() {
        val ids = PtpCodec.Reader(
            dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_STORAGE_IDS))),
        ).u32Array()
        assertArrayEquals(intArrayOf(MtpConstants.STORAGE_ID), ids)
    }

    @Test
    fun `storage info reports a hierarchical read-write filesystem`() {
        val r = PtpCodec.Reader(
            dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_STORAGE_INFO, MtpConstants.STORAGE_ID))),
        )
        r.u16()                                                     // storage type
        assertEquals(MtpConstants.FILESYSTEM_TYPE_HIERARCHICAL, r.u16())
        assertEquals(MtpConstants.ACCESS_READ_WRITE, r.u16())
        assertTrue("capacity should be positive", r.u64() > 0)
    }

    @Test
    fun `an unknown storage id is rejected`() {
        assertEquals(
            MtpConstants.RESP_INVALID_STORAGE_ID,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_GET_STORAGE_INFO, 0x9999))),
        )
    }

    // --- browsing ---------------------------------------------------------

    @Test
    fun `root listing shows the top level only`() {
        val names = handles(MtpConstants.HANDLE_ROOT).map { nameOf(it) }
        assertTrue(names.contains("notes.txt"))
        assertTrue(names.contains("Music"))
        assertFalse("must not recurse for HANDLE_ROOT", names.contains("song.mp3"))
    }

    @Test
    fun `handle zero means every object at any depth`() {
        // The distinction between HANDLE_ALL and HANDLE_ROOT is easy to get
        // wrong and makes a client either show nothing or recurse everything.
        val names = handles(MtpConstants.HANDLE_ALL).map { nameOf(it) }
        assertTrue(names.contains("notes.txt"))
        assertTrue(names.contains("song.mp3"))
    }

    @Test
    fun `listing a folder handle shows its children`() {
        val music = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "Music" }
        assertEquals(listOf("song.mp3"), handles(music).map { nameOf(it) })
    }

    @Test
    fun `GetNumObjects agrees with the handle list`() {
        val count = (session.handleCommand(
            cmd(MtpConstants.OP_GET_NUM_OBJECTS, MtpConstants.STORAGE_ID, 0, MtpConstants.HANDLE_ROOT),
        ) as MtpSession.Reply.Status).params.first()
        assertEquals(handles(MtpConstants.HANDLE_ROOT).size, count)
    }

    @Test
    fun `object info marks a directory as an association`() {
        val music = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "Music" }
        val r = PtpCodec.Reader(dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_INFO, music))))
        r.u32()
        assertEquals(MtpConstants.FORMAT_ASSOCIATION, r.u16())
    }

    @Test
    fun `object info reports file size`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        val r = PtpCodec.Reader(dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_INFO, notes))))
        r.u32(); r.u16(); r.u16()
        assertEquals("hello world".length, r.u32())
    }

    @Test
    fun `object info on an unknown handle is refused`() {
        assertEquals(
            MtpConstants.RESP_INVALID_OBJECT_HANDLE,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_INFO, 987654))),
        )
    }

    // --- reading ----------------------------------------------------------

    @Test
    fun `GetObject returns file contents`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        assertArrayEquals(
            "hello world".toByteArray(),
            dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT, notes))),
        )
    }

    @Test
    fun `GetObject on a directory is refused`() {
        val music = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "Music" }
        assertEquals(
            MtpConstants.RESP_INVALID_OBJECT_HANDLE,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT, music))),
        )
    }

    @Test
    fun `GetPartialObject honours offset and length`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        val reply = session.handleCommand(
            cmd(MtpConstants.OP_GET_PARTIAL_OBJECT, notes, 6, 5),
        ) as MtpSession.Reply.Data
        assertArrayEquals("world".toByteArray(), reply.payload)
        assertEquals(5, reply.params.first())
    }

    @Test
    fun `GetPartialObject past the end returns nothing rather than failing`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        val reply = session.handleCommand(
            cmd(MtpConstants.OP_GET_PARTIAL_OBJECT, notes, 9999, 10),
        ) as MtpSession.Reply.Data
        assertEquals(0, reply.payload.size)
    }

    // --- properties -------------------------------------------------------

    @Test
    fun `object size comes back as a 64 bit property`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        val r = PtpCodec.Reader(
            dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_PROP_VALUE, notes, MtpConstants.PROP_OBJECT_SIZE))),
        )
        assertEquals(11L, r.u64())
    }

    @Test
    fun `filename property matches the object`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        val r = PtpCodec.Reader(
            dataOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_PROP_VALUE, notes, MtpConstants.PROP_OBJECT_FILE_NAME))),
        )
        assertEquals("notes.txt", r.string())
    }

    @Test
    fun `an unsupported property is reported not faked`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        assertEquals(
            MtpConstants.RESP_OBJECT_PROP_NOT_SUPPORTED,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_GET_OBJECT_PROP_VALUE, notes, 0xDCFF))),
        )
    }

    // --- writing ----------------------------------------------------------

    private fun objectInfoPayload(name: String, size: Long, format: Int): ByteArray {
        val w = PtpCodec.Writer(128)
        w.u32(MtpConstants.STORAGE_ID)
        w.u16(format)
        w.u16(0)
        w.u32(size.toInt())
        // ThumbFormat + 6 x u32 (thumb size/w/h, image w/h, bit depth) = 26,
        // so ParentObject sits at 38 and Filename at 52.
        w.u16(0); repeat(6) { w.u32(0) }
        w.u32(0)                      // parent
        w.u16(if (format == MtpConstants.FORMAT_ASSOCIATION) 1 else 0)
        w.u32(0)                      // association description
        w.u32(0)                      // sequence number
        w.string(name)
        w.string(""); w.string(""); w.string("")
        return w.toByteArray()
    }

    @Test
    fun `SendObjectInfo then SendObject creates a file with contents`() {
        val info = cmd(MtpConstants.OP_SEND_OBJECT_INFO, MtpConstants.STORAGE_ID, MtpConstants.HANDLE_ROOT)
        assertTrue(session.handleCommand(info) is MtpSession.Reply.AwaitData)

        val reply = session.handleData(info, objectInfoPayload("new.txt", 5, MtpConstants.FORMAT_TEXT))
                as MtpSession.Reply.Status
        assertEquals(MtpConstants.RESP_OK, reply.code)
        assertEquals(3, reply.params.size)     // storage, parent, new handle

        val send = cmd(MtpConstants.OP_SEND_OBJECT)
        assertTrue(session.handleCommand(send) is MtpSession.Reply.AwaitData)
        assertEquals(MtpConstants.RESP_OK, statusOf(session.handleData(send, "12345".toByteArray())))

        assertEquals("12345", File(root, "new.txt").readText())
    }

    @Test
    fun `SendObjectInfo with an association creates a folder and needs no data`() {
        val info = cmd(MtpConstants.OP_SEND_OBJECT_INFO, MtpConstants.STORAGE_ID, MtpConstants.HANDLE_ROOT)
        session.handleCommand(info)
        assertEquals(
            MtpConstants.RESP_OK,
            statusOf(session.handleData(info, objectInfoPayload("NewDir", 0, MtpConstants.FORMAT_ASSOCIATION))),
        )
        assertTrue(File(root, "NewDir").isDirectory)
    }

    @Test
    fun `SendObject without a preceding SendObjectInfo is refused`() {
        val send = cmd(MtpConstants.OP_SEND_OBJECT)
        session.handleCommand(send)
        assertEquals(
            MtpConstants.RESP_NO_VALID_OBJECT_INFO,
            statusOf(session.handleData(send, "data".toByteArray())),
        )
    }

    @Test
    fun `a traversing filename is refused at the protocol layer`() {
        val info = cmd(MtpConstants.OP_SEND_OBJECT_INFO, MtpConstants.STORAGE_ID, MtpConstants.HANDLE_ROOT)
        session.handleCommand(info)
        assertEquals(
            MtpConstants.RESP_ACCESS_DENIED,
            statusOf(session.handleData(info, objectInfoPayload("../escape.txt", 4, MtpConstants.FORMAT_TEXT))),
        )
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `malformed object info is rejected`() {
        val info = cmd(MtpConstants.OP_SEND_OBJECT_INFO, MtpConstants.STORAGE_ID, MtpConstants.HANDLE_ROOT)
        session.handleCommand(info)
        assertEquals(
            MtpConstants.RESP_NO_VALID_OBJECT_INFO,
            statusOf(session.handleData(info, ByteArray(10))),
        )
    }

    // --- deleting ---------------------------------------------------------

    @Test
    fun `DeleteObject removes a file`() {
        val notes = handles(MtpConstants.HANDLE_ROOT).first { nameOf(it) == "notes.txt" }
        assertEquals(MtpConstants.RESP_OK, statusOf(session.handleCommand(cmd(MtpConstants.OP_DELETE_OBJECT, notes))))
        assertFalse(File(root, "notes.txt").exists())
    }

    @Test
    fun `deleting everything is refused as too blunt`() {
        assertEquals(
            MtpConstants.RESP_ACCESS_DENIED,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_DELETE_OBJECT, MtpConstants.HANDLE_ALL))),
        )
        assertTrue(File(root, "notes.txt").exists())
    }

    @Test
    fun `deleting an unknown handle is refused`() {
        assertEquals(
            MtpConstants.RESP_INVALID_OBJECT_HANDLE,
            statusOf(session.handleCommand(cmd(MtpConstants.OP_DELETE_OBJECT, 555555))),
        )
    }

    // --- unknown ops ------------------------------------------------------

    @Test
    fun `an unimplemented operation says so`() {
        assertEquals(
            MtpConstants.RESP_OPERATION_NOT_SUPPORTED,
            statusOf(session.handleCommand(cmd(0x99FF))),
        )
    }
}
