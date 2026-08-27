package io.openusbip.server.mtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Object store tests, run against a real temp directory on the desktop JVM.
 *
 * The security boundary matters most here. A client supplies both the filename
 * in SendObjectInfo and the parent handle, so without containment a name like
 * "../../secrets" writes outside the directory the user chose to share.
 */
class ObjectStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ObjectStore

    @Before
    fun setUp() {
        root = temp.newFolder("shared")
        store = ObjectStore(root)
        File(root, "notes.txt").writeText("hello")
        File(root, "photo.jpg").writeText("jpeg-ish")
        File(root, "Music").mkdirs()
        File(root, "Music/song.mp3").writeText("audio")
        File(root, ".hidden").writeText("secret")
    }

    // --- handles ----------------------------------------------------------

    @Test
    fun `handles are stable across lookups`() {
        val file = File(root, "notes.txt")
        val first = store.handleFor(file, MtpConstants.HANDLE_ROOT)
        val second = store.handleFor(file, MtpConstants.HANDLE_ROOT)
        assertEquals(first, second)
    }

    @Test
    fun `handles avoid the reserved values`() {
        val h = store.handleFor(File(root, "notes.txt"), MtpConstants.HANDLE_ROOT)!!
        assertNotEquals(MtpConstants.HANDLE_ALL, h)
        assertNotEquals(MtpConstants.HANDLE_ROOT, h)
        assertTrue(h > 0)
    }

    @Test
    fun `distinct files get distinct handles`() {
        val a = store.handleFor(File(root, "notes.txt"), MtpConstants.HANDLE_ROOT)
        val b = store.handleFor(File(root, "photo.jpg"), MtpConstants.HANDLE_ROOT)
        assertNotEquals(a, b)
    }

    // --- listing ----------------------------------------------------------

    @Test
    fun `root listing returns the top level and hides dotfiles`() {
        val names = store.children(MtpConstants.HANDLE_ROOT).map { it.name }
        assertTrue(names.contains("notes.txt"))
        assertTrue(names.contains("photo.jpg"))
        assertTrue(names.contains("Music"))
        assertFalse("dotfiles should be hidden", names.contains(".hidden"))
    }

    @Test
    fun `directories sort before files`() {
        val names = store.children(MtpConstants.HANDLE_ROOT).map { it.name }
        assertEquals("Music", names.first())
    }

    @Test
    fun `listing a subdirectory uses its handle`() {
        val music = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "Music" }
        assertTrue(music.isDirectory)

        val inside = store.children(music.handle).map { it.name }
        assertEquals(listOf("song.mp3"), inside)
    }

    @Test
    fun `children of an unknown handle is empty, not an exception`() {
        assertTrue(store.children(999999).isEmpty())
    }

    @Test
    fun `children of a plain file is empty`() {
        val notes = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "notes.txt" }
        assertTrue(store.children(notes.handle).isEmpty())
    }

    @Test
    fun `allEntries walks the whole tree`() {
        // HANDLE_ALL means every object at any depth, unlike HANDLE_ROOT.
        val names = store.allEntries().map { it.name }
        assertTrue(names.contains("notes.txt"))
        assertTrue(names.contains("song.mp3"))
        assertTrue(names.contains("Music"))
        assertFalse(names.contains(".hidden"))
    }

    // --- metadata ---------------------------------------------------------

    @Test
    fun `entries report size and type`() {
        val notes = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "notes.txt" }
        assertEquals(5L, notes.size)
        assertFalse(notes.isDirectory)

        val music = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "Music" }
        // A directory reports zero size, as MTP expects for an association.
        assertEquals(0L, music.size)
        assertTrue(music.isDirectory)
    }

    @Test
    fun `format codes follow the extension`() {
        assertEquals(MtpConstants.FORMAT_ASSOCIATION, ObjectStore.formatFor(File(root, "Music")))
        assertEquals(MtpConstants.FORMAT_TEXT, ObjectStore.formatFor(File(root, "notes.txt")))
        assertEquals(MtpConstants.FORMAT_EXIF_JPEG, ObjectStore.formatFor(File(root, "photo.jpg")))
        assertEquals(MtpConstants.FORMAT_MP3, ObjectStore.formatFor(File(root, "Music/song.mp3")))
        assertEquals(MtpConstants.FORMAT_UNDEFINED, ObjectStore.formatFor(File(root, "x.zzz")))
    }

    @Test
    fun `parent handle is recorded`() {
        val music = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "Music" }
        val song = store.children(music.handle).first()
        assertEquals(music.handle, song.parent)
    }

    // --- containment: the security boundary -------------------------------

    @Test
    fun `paths outside the root are refused`() {
        val outside = File(root.parentFile, "elsewhere.txt")
        outside.writeText("not shared")
        assertFalse(store.isInsideRoot(outside))
        assertNull(store.handleFor(outside, MtpConstants.HANDLE_ROOT))
    }

    @Test
    fun `traversal in a filename is rejected`() {
        // The client picks this name; it must not escape the share.
        assertFalse(ObjectStore.isSafeName("../escape"))
        assertFalse(ObjectStore.isSafeName("a/b"))
        assertFalse(ObjectStore.isSafeName("a\\b"))
        assertFalse(ObjectStore.isSafeName(".."))
        assertFalse(ObjectStore.isSafeName("."))
        assertFalse(ObjectStore.isSafeName(""))
        assertFalse(ObjectStore.isSafeName("   "))
    }

    @Test
    fun `create refuses a traversing name`() {
        assertNull(store.create(MtpConstants.HANDLE_ROOT, "../evil.txt", isDirectory = false))
        assertFalse(File(root.parentFile, "evil.txt").exists())
    }

    @Test
    fun `ordinary names are accepted`() {
        assertTrue(ObjectStore.isSafeName("photo.jpg"))
        assertTrue(ObjectStore.isSafeName("Report-2026.pdf"))
    }

    // --- create -----------------------------------------------------------

    @Test
    fun `create makes a file and returns a usable handle`() {
        val handle = store.create(MtpConstants.HANDLE_ROOT, "new.txt", isDirectory = false)
        assertNotNull(handle)
        val file = store.fileFor(handle!!)!!
        assertTrue(file.isFile)
        assertEquals("new.txt", file.name)
    }

    @Test
    fun `create makes a directory`() {
        val handle = store.create(MtpConstants.HANDLE_ROOT, "NewFolder", isDirectory = true)!!
        assertTrue(store.fileFor(handle)!!.isDirectory)
    }

    @Test
    fun `create nests under a directory handle`() {
        val music = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "Music" }
        val handle = store.create(music.handle, "new.mp3", isDirectory = false)!!
        assertEquals(File(root, "Music/new.mp3").canonicalPath, store.fileFor(handle)!!.canonicalPath)
    }

    @Test
    fun `create under an unknown parent fails`() {
        assertNull(store.create(424242, "x.txt", isDirectory = false))
    }

    // --- delete -----------------------------------------------------------

    @Test
    fun `delete removes a file and retires its handle`() {
        val notes = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "notes.txt" }
        assertTrue(store.delete(notes.handle))
        assertFalse(File(root, "notes.txt").exists())
        // Retired, not reused: a client still holding it must get an error.
        assertNull(store.entry(notes.handle))
    }

    @Test
    fun `deleting a directory removes its children handles too`() {
        val music = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "Music" }
        val song = store.children(music.handle).first()

        assertTrue(store.delete(music.handle))
        assertFalse(File(root, "Music").exists())
        assertNull(store.entry(music.handle))
        assertNull("child handles must not survive their parent", store.entry(song.handle))
    }

    @Test
    fun `deleting an unknown handle reports failure`() {
        assertFalse(store.delete(777777))
    }

    @Test
    fun `a new handle after a delete is not the old one`() {
        val notes = store.children(MtpConstants.HANDLE_ROOT).first { it.name == "notes.txt" }
        val old = notes.handle
        store.delete(old)

        val fresh = store.create(MtpConstants.HANDLE_ROOT, "notes.txt", isDirectory = false)!!
        assertNotEquals("handles must never be recycled", old, fresh)
    }

    // --- housekeeping -----------------------------------------------------

    @Test
    fun `clear drops every handle`() {
        store.children(MtpConstants.HANDLE_ROOT)
        assertTrue(store.size() > 0)
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `capacity and free space are reported`() {
        assertTrue(store.capacityBytes > 0)
        assertTrue(store.freeBytes >= 0)
    }
}
