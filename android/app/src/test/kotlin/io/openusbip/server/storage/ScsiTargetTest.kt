package io.openusbip.server.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCSI tests, written against the command set Linux's usb-storage driver
 * actually issues when bringing a stick up.
 *
 * The high-risk item here is READ CAPACITY: it reports the address of the LAST
 * block, not the block count. Off by one and the volume looks one sector too
 * big, which surfaces as I/O errors only at the very end of the disk -- long
 * after anyone would connect it to this code.
 */
class ScsiTargetTest {

    private val blocks = 2048L        // 1 MiB at 512 bytes
    private fun target(readOnly: Boolean = false): Pair<ScsiTarget, MemoryBlockDevice> {
        val dev = MemoryBlockDevice(blockCount = blocks, readOnly = readOnly)
        return ScsiTarget(dev) to dev
    }

    private fun cdb(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun read10Cdb(lba: Int, count: Int) = cdb(
        ScsiTarget.OP_READ_10, 0,
        (lba ushr 24) and 0xFF, (lba ushr 16) and 0xFF, (lba ushr 8) and 0xFF, lba and 0xFF,
        0, (count ushr 8) and 0xFF, count and 0xFF, 0,
    )

    private fun write10Cdb(lba: Int, count: Int) = cdb(
        ScsiTarget.OP_WRITE_10, 0,
        (lba ushr 24) and 0xFF, (lba ushr 16) and 0xFF, (lba ushr 8) and 0xFF, lba and 0xFF,
        0, (count ushr 8) and 0xFF, count and 0xFF, 0,
    )

    // --- INQUIRY ---------------------------------------------------------

    @Test
    fun `inquiry identifies a removable direct-access block device`() {
        val (scsi, _) = target()
        val r = scsi.execute(cdb(ScsiTarget.OP_INQUIRY, 0, 0, 0, 36, 0), null)

        assertEquals(ScsiTarget.STATUS_GOOD, r.status)
        val d = r.data!!
        assertEquals(36, d.size)
        assertEquals(0x00, d[0].toInt() and 0xFF)          // direct access block device
        assertEquals(0x80, d[1].toInt() and 0xFF)          // removable
        assertEquals(31, d[4].toInt() and 0xFF)            // additional length
        assertEquals("OpnUSBIP", String(d, 8, 8, Charsets.US_ASCII))
    }

    @Test
    fun `inquiry honours a short allocation length`() {
        val (scsi, _) = target()
        assertEquals(8, scsi.execute(cdb(ScsiTarget.OP_INQUIRY, 0, 0, 0, 8, 0), null).data!!.size)
    }

    @Test
    fun `an evpd inquiry is refused rather than answered with junk`() {
        val (scsi, _) = target()
        val r = scsi.execute(cdb(ScsiTarget.OP_INQUIRY, 0x01, 0x80, 0, 36, 0), null)
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, r.status)
    }

    // --- READ CAPACITY ---------------------------------------------------

    @Test
    fun `read capacity 10 reports the LAST lba not the count`() {
        val (scsi, _) = target()
        val d = scsi.execute(cdb(ScsiTarget.OP_READ_CAPACITY_10, 0, 0, 0, 0, 0, 0, 0, 0, 0), null).data!!

        assertEquals(8, d.size)
        assertEquals((blocks - 1).toInt(), ScsiTarget.be32(d, 0))
        assertEquals(512, ScsiTarget.be32(d, 4))
    }

    @Test
    fun `read capacity 16 reports the same geometry`() {
        val (scsi, _) = target()
        val c = cdb(
            ScsiTarget.OP_SERVICE_ACTION_IN_16, ScsiTarget.SERVICE_ACTION_READ_CAPACITY_16,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0,
        )
        val d = scsi.execute(c, null).data!!
        // last LBA is a 64-bit field here
        assertEquals(blocks - 1, ScsiTarget.be32(d, 4).toLong() and 0xFFFFFFFFL)
        assertEquals(512, ScsiTarget.be32(d, 8))
    }

    // --- READ / WRITE ----------------------------------------------------

    @Test
    fun `write then read returns the same bytes`() {
        val (scsi, _) = target()
        val payload = ByteArray(512) { (it % 251).toByte() }

        assertEquals(ScsiTarget.STATUS_GOOD, scsi.execute(write10Cdb(10, 1), payload).status)
        val read = scsi.execute(read10Cdb(10, 1), null)
        assertEquals(ScsiTarget.STATUS_GOOD, read.status)
        assertArrayEquals(payload, read.data)
    }

    @Test
    fun `multi block transfers move the whole run`() {
        val (scsi, _) = target()
        val payload = ByteArray(512 * 4) { (it % 256).toByte() }

        scsi.execute(write10Cdb(100, 4), payload)
        assertArrayEquals(payload, scsi.execute(read10Cdb(100, 4), null).data)
    }

    @Test
    fun `a write does not disturb neighbouring blocks`() {
        val (scsi, _) = target()
        scsi.execute(write10Cdb(5, 1), ByteArray(512) { 0xAA.toByte() })

        assertTrue(scsi.execute(read10Cdb(4, 1), null).data!!.all { it == 0.toByte() })
        assertTrue(scsi.execute(read10Cdb(6, 1), null).data!!.all { it == 0.toByte() })
    }

    @Test
    fun `a zero length transfer is a legal no-op`() {
        val (scsi, _) = target()
        assertEquals(ScsiTarget.STATUS_GOOD, scsi.execute(read10Cdb(0, 0), null).status)
        assertEquals(ScsiTarget.STATUS_GOOD, scsi.execute(write10Cdb(0, 0), null).status)
    }

    // --- range checking --------------------------------------------------

    @Test
    fun `reading past the end reports lba out of range`() {
        val (scsi, _) = target()
        val r = scsi.execute(read10Cdb(blocks.toInt(), 1), null)
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, r.status)

        // and the sense explains why
        val sense = scsi.execute(cdb(ScsiTarget.OP_REQUEST_SENSE, 0, 0, 0, 18, 0), null).data!!
        assertEquals(ScsiTarget.SENSE_ILLEGAL_REQUEST, sense[2].toInt() and 0x0F)
        assertEquals(ScsiTarget.ASC_LBA_OUT_OF_RANGE, sense[12].toInt() and 0xFF)
    }

    @Test
    fun `a transfer straddling the end is rejected whole`() {
        val (scsi, _) = target()
        // Last valid block is blocks-1; asking for 4 from blocks-2 overruns.
        val r = scsi.execute(read10Cdb((blocks - 2).toInt(), 4), null)
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, r.status)
    }

    @Test
    fun `the final block is readable`() {
        // Guards the off-by-one that READ CAPACITY invites.
        val (scsi, _) = target()
        assertEquals(
            ScsiTarget.STATUS_GOOD,
            scsi.execute(read10Cdb((blocks - 1).toInt(), 1), null).status,
        )
    }

    // --- sense -----------------------------------------------------------

    @Test
    fun `sense is cleared once reported`() {
        val (scsi, _) = target()
        scsi.execute(read10Cdb(blocks.toInt(), 1), null)      // provoke an error

        val first = scsi.execute(cdb(ScsiTarget.OP_REQUEST_SENSE, 0, 0, 0, 18, 0), null).data!!
        assertEquals(ScsiTarget.SENSE_ILLEGAL_REQUEST, first[2].toInt() and 0x0F)

        val second = scsi.execute(cdb(ScsiTarget.OP_REQUEST_SENSE, 0, 0, 0, 18, 0), null).data!!
        assertEquals(ScsiTarget.SENSE_NO_SENSE, second[2].toInt() and 0x0F)
    }

    @Test
    fun `sense uses the fixed format the driver expects`() {
        val (scsi, _) = target()
        val d = scsi.execute(cdb(ScsiTarget.OP_REQUEST_SENSE, 0, 0, 0, 18, 0), null).data!!
        assertEquals(18, d.size)
        assertEquals(0x70, d[0].toInt() and 0xFF)   // current error, fixed format
        assertEquals(10, d[7].toInt() and 0xFF)     // additional sense length
    }

    // --- write protect ---------------------------------------------------

    @Test
    fun `a read only volume refuses writes and says why`() {
        val (scsi, _) = target(readOnly = true)
        val r = scsi.execute(write10Cdb(0, 1), ByteArray(512))
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, r.status)

        val sense = scsi.execute(cdb(ScsiTarget.OP_REQUEST_SENSE, 0, 0, 0, 18, 0), null).data!!
        assertEquals(ScsiTarget.SENSE_DATA_PROTECT, sense[2].toInt() and 0x0F)
        assertEquals(ScsiTarget.ASC_WRITE_PROTECTED, sense[12].toInt() and 0xFF)
    }

    @Test
    fun `mode sense advertises the write protect bit`() {
        val (ro, _) = target(readOnly = true)
        val (rw, _) = target(readOnly = false)
        val c = cdb(ScsiTarget.OP_MODE_SENSE_6, 0, 0x3F, 0, 4, 0)

        assertEquals(0x80, ro.execute(c, null).data!![2].toInt() and 0xFF)
        assertEquals(0x00, rw.execute(c, null).data!![2].toInt() and 0xFF)
    }

    // --- short data-out --------------------------------------------------

    @Test
    fun `a truncated write is refused rather than half applied`() {
        val (scsi, dev) = target()
        // Ask to write 2 blocks but supply only one block of data.
        val r = scsi.execute(write10Cdb(0, 2), ByteArray(512))
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, r.status)
        // nothing should have landed
        assertTrue(dev.read(0, 2).all { it == 0.toByte() })
    }

    // --- no-ops and unknowns ---------------------------------------------

    @Test
    fun `the no-op commands the driver issues all succeed`() {
        val (scsi, _) = target()
        for (op in listOf(
            ScsiTarget.OP_TEST_UNIT_READY,
            ScsiTarget.OP_PREVENT_ALLOW_REMOVAL,
            ScsiTarget.OP_START_STOP_UNIT,
            ScsiTarget.OP_SYNCHRONIZE_CACHE,
        )) {
            val r = scsi.execute(cdb(op, 0, 0, 0, 0, 0, 0, 0, 0, 0), null)
            assertEquals("opcode 0x%02x".format(op), ScsiTarget.STATUS_GOOD, r.status)
        }
    }

    @Test
    fun `an unknown opcode reports invalid command like a real device`() {
        val (scsi, _) = target()
        val r = scsi.execute(cdb(0xFD, 0, 0, 0, 0, 0), null)
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, r.status)

        val sense = scsi.execute(cdb(ScsiTarget.OP_REQUEST_SENSE, 0, 0, 0, 18, 0), null).data!!
        assertEquals(ScsiTarget.SENSE_ILLEGAL_REQUEST, sense[2].toInt() and 0x0F)
        assertEquals(ScsiTarget.ASC_INVALID_COMMAND, sense[12].toInt() and 0xFF)
    }

    @Test
    fun `an empty cdb does not crash`() {
        val (scsi, _) = target()
        assertEquals(ScsiTarget.STATUS_CHECK_CONDITION, scsi.execute(ByteArray(0), null).status)
    }

    // --- expected length reconciliation ----------------------------------

    @Test
    fun `expected in length matches what each command produces`() {
        val (scsi, _) = target()
        assertEquals(36, scsi.expectedInLength(cdb(ScsiTarget.OP_INQUIRY, 0, 0, 0, 36, 0)))
        assertEquals(8, scsi.expectedInLength(cdb(ScsiTarget.OP_READ_CAPACITY_10, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(512 * 4, scsi.expectedInLength(read10Cdb(0, 4)))
        assertEquals(0, scsi.expectedInLength(write10Cdb(0, 4)))
    }
}
