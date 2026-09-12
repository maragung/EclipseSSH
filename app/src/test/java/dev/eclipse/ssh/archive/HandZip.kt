package dev.eclipse.ssh.archive

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry

/**
 * A byte-level ZIP writer for the shapes no real writer will emit: lying EOCD fields,
 * path-escaping names, saturated sizes, headers that disagree with each other. Every field this
 * class writes is one the engine under test has to read, and being able to spell any value into
 * any of them is the point.
 *
 * Top-level in the test package rather than private inside one suite because two suites now
 * build hostile archives — the listing's own tests and the extractor's refusal tests — and the
 * shapes both need are the same shapes; duplicating the writer would leave two drift-prone
 * spellings of "a lying central directory" where one is bad enough.
 */
internal class HandZip {
    private val local = ByteArrayOutputStream()
    private val central = ByteArrayOutputStream()
    private var entryCount = 0

    /**
     * Appends one entry (local file header, payload, and a central directory record) and returns
     * the local header's offset, which the data-offset tests need for arithmetic.
     *
     * The central and local headers are written from separate arguments on purpose: the format
     * allows them to disagree, and one test depends on building a disagreement.
     */
    fun add(
        name: ByteArray,
        data: ByteArray = ByteArray(0),
        flags: Int = 0,
        method: Int = ZipEntry.STORED,
        localName: ByteArray = name,
        localExtra: ByteArray = ByteArray(0),
        cdExtra: ByteArray = ByteArray(0),
        dosTime: Int = 0,
        dosDate: Int = 0,
        externalAttrs: Long = 0,
        cdUncompressedSize: Long = data.size.toLong(),
        cdCompressedSize: Long = data.size.toLong(),
    ): Long {
        val headerOffset = local.size().toLong()

        local.write(le32(0x04034b50))
        local.write(le16(20)) // version needed
        local.write(le16(flags))
        local.write(le16(method))
        local.write(le16(dosTime))
        local.write(le16(dosDate))
        local.write(le32(0)) // crc - never read by the listing engine
        local.write(le32(data.size.toLong()))
        local.write(le32(data.size.toLong()))
        local.write(le16(localName.size))
        local.write(le16(localExtra.size))
        local.write(localName)
        local.write(localExtra)
        local.write(data)

        central.write(le32(0x02014b50))
        central.write(le16(20)) // version made by
        central.write(le16(20)) // version needed
        central.write(le16(flags))
        central.write(le16(method))
        central.write(le16(dosTime))
        central.write(le16(dosDate))
        central.write(le32(0)) // crc
        central.write(le32(cdCompressedSize))
        central.write(le32(cdUncompressedSize))
        central.write(le16(name.size))
        central.write(le16(cdExtra.size))
        central.write(le16(0)) // comment length
        central.write(le16(0)) // disk number
        central.write(le16(0)) // internal attributes
        central.write(le32(externalAttrs))
        central.write(le32(headerOffset))
        central.write(name)
        central.write(cdExtra)

        entryCount++
        return headerOffset
    }

    /**
     * Assembles the archive. With [zip64] the classic EOCD's count and offsets are left saturated
     * and a ZIP64 EOCD plus locator sit between the central directory and it, which is how real
     * writers spell "this archive is past the 32-bit limits". The [eocdPatch] lambda mutates the
     * 22-byte classic EOCD before it is appended, so a test can corrupt exactly one field of an
     * otherwise valid archive.
     */
    fun build(
        zip64: Boolean = false,
        eocdPatch: (ByteArray) -> Unit = {},
    ): ByteArray {
        val localBytes = local.toByteArray()
        val centralBytes = central.toByteArray()
        val cdOffset = localBytes.size.toLong()
        val out = ByteArrayOutputStream()
        out.write(localBytes)
        out.write(centralBytes)

        if (zip64) {
            val zip64Offset = cdOffset + centralBytes.size
            out.write(le32(0x06064b50))
            out.write(le64(44)) // size of this record past this field
            out.write(le16(45)) // version made by
            out.write(le16(45)) // version needed
            out.write(le32(0)) // disk number
            out.write(le32(0)) // central directory start disk
            out.write(le64(entryCount.toLong())) // entries on this disk
            out.write(le64(entryCount.toLong())) // entries total
            out.write(le64(centralBytes.size.toLong()))
            out.write(le64(cdOffset))
            out.write(le32(0x07064b50))
            out.write(le32(0)) // disk with the ZIP64 EOCD
            out.write(le64(zip64Offset))
            out.write(le32(1)) // total disks
        }

        val eocd = ByteArray(ZipArchive.EOCD_MIN_BYTES)
        // Classic EOCD layout: signature, this disk, CD start disk, records on this disk,
        // records total (16-bit, saturated under ZIP64), CD size (32-bit), CD offset
        // (32-bit), comment length - each field at the offset the format fixes it at.
        writeField(eocd, 0, le32(0x06054b50))
        writeField(eocd, 4, le16(0)) // disk number
        writeField(eocd, 6, le16(0)) // central directory start disk
        writeField(eocd, 8, le16(if (zip64) 0xFFFF else entryCount)) // records on this disk
        writeField(eocd, 10, le16(if (zip64) 0xFFFF else entryCount)) // records total
        writeField(eocd, 12, le32(if (zip64) 0xFFFFFFFFL else centralBytes.size.toLong()))
        writeField(eocd, 16, le32(if (zip64) 0xFFFFFFFFL else cdOffset))
        writeField(eocd, 20, le16(0)) // comment length
        eocdPatch(eocd)
        out.write(eocd)
        return out.toByteArray()
    }

    private fun writeField(target: ByteArray, at: Int, bytes: ByteArray) {
        bytes.forEachIndexed { index, byte -> target[at + index] = byte }
    }
}

/**
 * The little-endian writers [HandZip] spells records with. Top-level (not nested in one suite)
 * for the same reason HandZip is: the extractor's tests build archives too, and both suites must
 * spell the same bytes with the same helpers.
 */
internal fun le16(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
)

internal fun le32(value: Long): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 24) and 0xFF).toByte(),
)

internal fun le64(value: Long): ByteArray =
    (0 until 8).map { index -> ((value ushr (8 * index)) and 0xFF).toByte() }.toByteArray()
