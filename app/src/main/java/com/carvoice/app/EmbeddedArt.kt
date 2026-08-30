package com.carvoice.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Reads embedded cover art straight out of an MP3's ID3v2 tag or a
 * FLAC's PICTURE metadata block, by parsing the actual file bytes -
 * NOT via MediaMetadataRetriever.
 *
 * Why this exists: MediaMetadataRetriever.embeddedPicture is what
 * AlbumArt used before, and for a lot of real-world files it just
 * doesn't work - it depends on Android's stagefright media parser
 * correctly recognizing the tag/picture-frame layout a given ripping or
 * tagging tool produced, and that parser is known to be inconsistent
 * across ID3v2.2 vs v2.3 vs v2.4, various frame-encoding quirks, and
 * FLAC's METADATA_BLOCK_PICTURE in particular. That mismatch is exactly
 * what "plays with cover art fine in every other player, blank in this
 * one" looks like: other players typically ship their own tag-parsing
 * code for this exact reason, rather than trusting the platform's media
 * parser. This is that same kind of direct parsing, scoped to just the
 * two tag formats that matter here (MP3/ID3v2 and FLAC).
 *
 * Known, accepted limitation: doesn't handle the ID3v2.3 "whole tag"
 * unsynchronisation flag (bit 7 of the header flags byte). In practice
 * modern tagging tools (Mp3tag, foobar2000, iTunes, etc.) don't set that
 * flag on tags carrying embedded art, specifically because naively
 * unsynchronising binary image data would corrupt it - so this is a
 * narrow, deliberately-not-chased edge case, not an oversight.
 */
object EmbeddedArt {

    /** Extracts embedded art for [uri], or null if none was found /
     * anything about the file couldn't be parsed. Dispatches purely by
     * file extension (from the display name - a raw file:// uri's own
     * path segment, or a content:// uri's OpenableColumns.DISPLAY_NAME),
     * since ID3 and FLAC are structurally unrelated formats. */
    fun extract(context: Context, uri: Uri): ByteArray? {
        val name = displayName(context, uri) ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ext) {
                    "mp3" -> extractId3v2Apic(input)
                    "flac" -> extractFlacPicture(input)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Shared with AudioTagWriter (rating tags use the same dispatch-by-
     * extension idea as art extraction above). */
    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else uri.lastPathSegment
            } else uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        } finally {
            try { cursor?.close() } catch (e: Exception) { /* already gone */ }
        }
    }

    // ---- ID3v2 (APIC / PIC) ----

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    /** Syncsafe 4-byte int (each byte's high bit is always 0) - used for
     * the overall ID3v2 tag size, and for individual frame sizes in
     * ID3v2.4 specifically (v2.2/v2.3 frame sizes are plain big-endian). */
    private fun syncSafeInt(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)
    }

    private fun plainInt(b: ByteArray, offset: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) v = (v shl 8) or (b[offset + i].toInt() and 0xFF)
        return v
    }

    private fun extractId3v2Apic(input: InputStream): ByteArray? {
        val header = readFully(input, 10) ?: return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null  // no ID3v2 tag at all
        }
        val majorVersion = header[3].toInt() and 0xFF
        val tagSize = syncSafeInt(header, 6)
        if (tagSize <= 0 || tagSize > 32 * 1024 * 1024) return null  // sanity bound - a broken/huge size isn't a real tag
        val tag = readFully(input, tagSize) ?: return null

        var pos = 0
        // v2.3/v2.4 extended header, if present (flag bit 6 of header[5]) -
        // its own size is a plain 4-byte (v2.3) or syncsafe (v2.4) int
        // right at the start of `tag`; skip past it to reach the frames.
        val hasExtendedHeader = (header[5].toInt() and 0x40) != 0
        if (hasExtendedHeader && tag.size >= 4) {
            val extSize = if (majorVersion >= 4) syncSafeInt(tag, 0) else plainInt(tag, 0, 4)
            pos += if (majorVersion == 3) extSize + 4 else extSize  // v2.3's own size field excludes itself; v2.4's includes it
        }

        while (pos + 6 <= tag.size) {
            if (majorVersion <= 2) {
                // v2.2: 3-char frame id, 3-byte plain size, no flags
                val id = String(tag, pos, 3, Charsets.ISO_8859_1)
                if (id == "\u0000\u0000\u0000") break  // padding reached
                val size = plainInt(tag, pos + 3, 3)
                val bodyStart = pos + 6
                if (size <= 0 || bodyStart + size > tag.size) break
                if (id == "PIC") return parsePicFrame(tag, bodyStart, size)
                pos = bodyStart + size
            } else {
                // v2.3 / v2.4: 4-char frame id, 4-byte size (syncsafe only in v2.4), 2 flag bytes
                val id = String(tag, pos, 4, Charsets.ISO_8859_1)
                if (id == "\u0000\u0000\u0000\u0000") break  // padding reached
                val size = if (majorVersion >= 4) syncSafeInt(tag, pos + 4) else plainInt(tag, pos + 4, 4)
                val bodyStart = pos + 10
                if (size <= 0 || bodyStart + size > tag.size) break
                if (id == "APIC") return parseApicFrame(tag, bodyStart, size)
                pos = bodyStart + size
            }
        }
        return null
    }

    /** APIC (ID3v2.3/2.4): encoding(1) + MIME\0 + pictureType(1) + description(\0 or \0\0) + data */
    private fun parseApicFrame(tag: ByteArray, start: Int, size: Int): ByteArray? {
        var p = start
        val end = start + size
        if (p >= end) return null
        val encoding = tag[p].toInt() and 0xFF
        p += 1
        val mimeEnd = indexOfByte(tag, p, end, 0)
        if (mimeEnd < 0) return null
        p = mimeEnd + 1
        if (p >= end) return null
        p += 1  // picture type byte
        val descTerminatorLen = if (encoding == 1 || encoding == 2) 2 else 1
        val descEnd = if (descTerminatorLen == 2) indexOfDoubleZero(tag, p, end) else indexOfByte(tag, p, end, 0)
        if (descEnd < 0) return null
        p = descEnd + descTerminatorLen
        if (p >= end) return null
        return tag.copyOfRange(p, end)
    }

    /** PIC (ID3v2.2): encoding(1) + imageFormat(3, fixed-width, no terminator) + pictureType(1) + description(\0 or \0\0) + data */
    private fun parsePicFrame(tag: ByteArray, start: Int, size: Int): ByteArray? {
        var p = start
        val end = start + size
        if (p + 4 >= end) return null
        val encoding = tag[p].toInt() and 0xFF
        p += 1 + 3  // encoding byte, then the 3-char format code (e.g. "JPG")
        p += 1  // picture type byte
        val descTerminatorLen = if (encoding == 1 || encoding == 2) 2 else 1
        val descEnd = if (descTerminatorLen == 2) indexOfDoubleZero(tag, p, end) else indexOfByte(tag, p, end, 0)
        if (descEnd < 0) return null
        p = descEnd + descTerminatorLen
        if (p >= end) return null
        return tag.copyOfRange(p, end)
    }

    private fun indexOfByte(buf: ByteArray, from: Int, to: Int, value: Int): Int {
        var i = from
        while (i < to) { if ((buf[i].toInt() and 0xFF) == value) return i; i++ }
        return -1
    }

    private fun indexOfDoubleZero(buf: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 1 < to) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) return i
            i++
        }
        return -1
    }

    // ---- FLAC (METADATA_BLOCK_PICTURE) ----

    private fun extractFlacPicture(input: InputStream): ByteArray? {
        val magic = readFully(input, 4) ?: return null
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) return null

        while (true) {
            val blockHeader = readFully(input, 4) ?: return null
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockLen = plainInt(blockHeader, 1, 3)
            if (blockType == 6) {  // PICTURE
                val block = readFully(input, blockLen) ?: return null
                return parseFlacPictureBlock(block)
            } else {
                if (!skipFully(input, blockLen)) return null
            }
            if (isLast) return null  // reached the last metadata block with no PICTURE block found
        }
    }

    private fun skipFully(input: InputStream, n: Int): Boolean {
        var remaining = n.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // Some stream implementations can return 0 from skip() even
                // when more data is available - fall back to reading (and
                // discarding) a byte at a time rather than looping forever.
                if (input.read() < 0) return false
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private fun parseFlacPictureBlock(b: ByteArray): ByteArray? {
        var p = 4  // picture type (unused here)
        if (p + 4 > b.size) return null
        val mimeLen = plainInt(b, p, 4); p += 4
        p += mimeLen  // MIME type string
        if (p + 4 > b.size) return null
        val descLen = plainInt(b, p, 4); p += 4
        p += descLen  // description string
        p += 16  // width, height, color depth, colors-used - 4 bytes each, all unused here
        if (p + 4 > b.size) return null
        val dataLen = plainInt(b, p, 4); p += 4
        if (dataLen <= 0 || p + dataLen > b.size) return null
        return b.copyOfRange(p, p + dataLen)
    }
}
