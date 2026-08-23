package io.openusbip.server.storage

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A block device the virtual USB drive is backed by.
 *
 * Deliberately narrow: the SCSI layer only ever needs to know how big the
 * medium is and how to move whole blocks. Keeping it to this makes the SCSI
 * interpreter testable against an in-memory implementation.
 */
interface BlockDevice : Closeable {

    /** Bytes per logical block. 512 for anything a PC will recognise. */
    val blockSize: Int

    /** Number of logical blocks. */
    val blockCount: Long

    val readOnly: Boolean

    /** Total capacity in bytes. */
    val sizeBytes: Long get() = blockCount * blockSize

    /**
     * Read [count] blocks starting at [lba].
     *
     * Must return exactly `count * blockSize` bytes. Reads past the end are
     * the caller's error -- the SCSI layer range-checks first so it can return
     * a proper LOGICAL BLOCK ADDRESS OUT OF RANGE sense rather than throwing.
     */
    fun read(lba: Long, count: Int): ByteArray

    /** Write whole blocks starting at [lba]. */
    fun write(lba: Long, data: ByteArray)

    /** Flush any buffered writes to durable storage. */
    fun flush() {}
}

/**
 * A block device backed by a regular file -- the disk image the phone exports.
 *
 * A file rather than a synthesised filesystem is a deliberate choice: the
 * client gets honest block semantics, so it can format, fsck and write to the
 * volume exactly as it would a real stick, and whatever filesystem it puts
 * there is simply the file's contents. Generating a filesystem on the fly
 * would mean reimplementing FAT write semantics, and any client operation we
 * had not anticipated would corrupt it.
 */
class FileBlockDevice(
    private val file: File,
    override val blockSize: Int = 512,
    override val readOnly: Boolean = false,
) : BlockDevice {

    private val handle: RandomAccessFile =
        RandomAccessFile(file, if (readOnly) "r" else "rw")

    override val blockCount: Long = file.length() / blockSize

    override fun read(lba: Long, count: Int): ByteArray {
        val buffer = ByteArray(count * blockSize)
        synchronized(handle) {
            handle.seek(lba * blockSize)
            handle.readFully(buffer)
        }
        return buffer
    }

    override fun write(lba: Long, data: ByteArray) {
        check(!readOnly) { "block device is read-only" }
        synchronized(handle) {
            handle.seek(lba * blockSize)
            handle.write(data)
        }
    }

    override fun flush() {
        if (!readOnly) synchronized(handle) { handle.fd.sync() }
    }

    override fun close() {
        runCatching { flush() }
        handle.close()
    }

    companion object {
        /**
         * Create a sparse image of [sizeBytes] if it does not already exist.
         *
         * setLength on a fresh file produces a sparse file on ext4/f2fs, so a
         * nominal 4 GB volume costs nothing until written -- which matters on a
         * phone.
         */
        fun createIfAbsent(file: File, sizeBytes: Long, blockSize: Int = 512): File {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                RandomAccessFile(file, "rw").use { it.setLength(sizeBytes - (sizeBytes % blockSize)) }
            }
            return file
        }
    }
}

/** In-memory block device, for tests. */
class MemoryBlockDevice(
    override val blockCount: Long,
    override val blockSize: Int = 512,
    override val readOnly: Boolean = false,
) : BlockDevice {

    private val data = ByteArray((blockCount * blockSize).toInt())

    override fun read(lba: Long, count: Int): ByteArray {
        val offset = (lba * blockSize).toInt()
        return data.copyOfRange(offset, offset + count * blockSize)
    }

    override fun write(lba: Long, bytes: ByteArray) {
        check(!readOnly) { "block device is read-only" }
        bytes.copyInto(data, (lba * blockSize).toInt())
    }

    override fun close() {}
}
