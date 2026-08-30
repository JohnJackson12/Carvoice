package com.carvoice.app

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes a song's rating into the ACTUAL AUDIO FILE's own tags - an ID3v2
 * POPM ("popularimeter") frame for MP3, a Vorbis Comment "RATING" field
 * for FLAC - so any other app/player that reads standard tags (Windows
 * Explorer, Winamp, foobar2000, MediaMonkey, VLC, etc.) sees the same
 * rating, not just this app's own private SongMetadataStore. Ratings set
 * from either the GUI star row or a voice command call through here in
 * addition to SongMetadataStore, so both stay in sync - SongMetadataStore
 * remains the fast, always-available source this app itself reads from
 * (works even if a write to the file fails or the file can't be
 * modified), while this is what makes it visible everywhere else too.
 *
 * Rating scale used:
 *   - MP3 (POPM byte): 0->0, 1->1, 2->64, 3->128, 4->196, 5->255 - the
 *     same 1-5-star-to-byte mapping Windows Explorer/Media Player uses,
 *     which is what most other tools (MediaMonkey, MP3Tag, etc.) also
 *     read that frame as.
 *   - FLAC (Vorbis Comment "RATING="): a plain 0/20/40/60/80/100
 *     percentage - there's no single universal standard for this field
 *     in Vorbis Comments, but a 0-100 percentage is the convention most
 *     widely recognized (foobar2000, MusicBee).
 *   - Every other format (M4A/AAC/OGG/WAV/etc.): not written into the
 *     file - there's no single cross-app-recognized convention for a
 *     rating tag in those containers worth committing to, and getting it
 *     "wrong" in a way another app misreads would be worse than just not
 *     writing it. The rating still works fully within this app either way
 *     (SongMetadataStore doesn't care what format the file is).
 *
 * Real file I/O (a full read-modify-write of the file) - never call from
 * the main thread.
 */
object AudioTagWriter {

    private val POPM_BYTE_FOR_RATING = mapOf(0 to 0, 1 to 1, 2 to 64, 3 to 128, 4 to 196, 5 to 255)
    private const val POPM_EMAIL = "rating@carvoiceplayer"  // POPM frame's required "owner identifier" field - arbitrary but must be consistent

    /** Returns true if the rating was actually written into the file's
     * own tags (false for an unsupported format, or if the write itself
     * failed - a permission problem, a file that's vanished, etc.). A
     * false return is NOT a failure the caller needs to surface loudly -
     * SongMetadataStore's own copy of the rating is what this app itself
     * always uses regardless, so the app keeps working correctly either
     * way. */
    fun writeRating(context: Context, uri: Uri, rating: Int): Boolean {
        val name = EmbeddedArt.displayName(context, uri) ?: return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when (ext) {
                "mp3" -> writeMp3Popm(context, uri, rating.coerceIn(0, 5))
                "flac" -> writeFlacRating(context, uri, rating.coerceIn(0, 5))
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun readAllBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(maxOf(input.available(), 64 * 1024))
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeAllBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            if (uri.scheme == "file") {
                // A raw filesystem path (FolderBrowserActivity's browser) -
                // MANAGE_EXTERNAL_STORAGE was already required to add this
                // folder in the first place, so a direct File write is fine.
                File(uri.path ?: return false).writeBytes(bytes)
            } else {
                // "wt" = write + truncate - this REPLACES the file's
                // contents rather than appending, which is what a
                // read-modify-write like this needs. Requires the write
                // half of the persistable URI permission taken when the
                // folder tree was added (see SettingsActivity) - already
                // granted for anything reachable here.
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- MP3 (ID3v2 POPM) ----

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

    private fun writeSyncSafeInt(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun writePlainInt(value: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) { b[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return b
    }

    /** Copies every existing frame EXCEPT any prior POPM frame(s) verbatim
     * (raw id+size+flags+body bytes, untouched) - this only ever needs to
     * know where each frame starts/ends, never decode what's inside one,
     * since nothing about any other frame is changing. Simplification
     * accepted: an ID3v2.3/2.4 extended header, if present, is dropped
     * rather than preserved - it's optional (CRC/restriction data, not
     * needed for playback or for any other frame to keep working) and
     * preserving it exactly would add real complexity for close to zero
     * practical benefit. */
    private fun copyNonPopmFrames(tag: ByteArray, majorVersion: Int, hasExtendedHeader: Boolean): ByteArray {
        var pos = 0
        if (hasExtendedHeader && tag.size >= 4) {
            val extSize = if (majorVersion >= 4) syncSafeInt(tag, 0) else plainInt(tag, 0, 4)
            pos += if (majorVersion == 3) extSize + 4 else extSize
        }
        val out = ByteArrayOutputStream()
        while (pos + 6 <= tag.size) {
            if (majorVersion <= 2) {
                val id = String(tag, pos, 3, Charsets.ISO_8859_1)
                if (id == "\u0000\u0000\u0000") break
                val size = plainInt(tag, pos + 3, 3)
                val bodyStart = pos + 6
                if (size < 0 || bodyStart + size > tag.size) break
                if (id != "PIC" && id != "POP") out.write(tag, pos, 6 + size)  // "POP" - ID3v2.2's short form of POPM
                pos = bodyStart + size
            } else {
                val id = String(tag, pos, 4, Charsets.ISO_8859_1)
                if (id == "\u0000\u0000\u0000\u0000") break
                val size = if (majorVersion >= 4) syncSafeInt(tag, pos + 4) else plainInt(tag, pos + 4, 4)
                val bodyStart = pos + 10
                if (size < 0 || bodyStart + size > tag.size) break
                if (id != "POPM") out.write(tag, pos, 10 + size)
                pos = bodyStart + size
            }
        }
        return out.toByteArray()
    }

    private fun buildPopmFrame(rating: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(POPM_EMAIL.toByteArray(Charsets.ISO_8859_1))
        body.write(0)  // null terminator for the email string
        body.write(POPM_BYTE_FOR_RATING[rating] ?: 0)
        body.write(byteArrayOf(0, 0, 0, 0))  // play counter - always 0, this app doesn't track play counts in the tag
        val bodyBytes = body.toByteArray()
        val frame = ByteArrayOutputStream()
        frame.write("POPM".toByteArray(Charsets.ISO_8859_1))
        frame.write(writePlainInt(bodyBytes.size, 4))  // ID3v2.3 plain size (this always writes a v2.3 tag - see writeMp3Popm)
        frame.write(byteArrayOf(0, 0))  // frame flags
        frame.write(bodyBytes)
        return frame.toByteArray()
    }

    private fun writeMp3Popm(context: Context, uri: Uri, rating: Int): Boolean {
        val original = readAllBytes(context, uri) ?: return false
        val hasExistingTag = original.size >= 10 &&
            original[0] == 'I'.code.toByte() && original[1] == 'D'.code.toByte() && original[2] == '3'.code.toByte()

        val keptFrames: ByteArray
        val restOfFile: ByteArray
        if (hasExistingTag) {
            val majorVersion = original[3].toInt() and 0xFF
            val tagBodySize = syncSafeInt(original, 6)
            if (tagBodySize < 0 || 10 + tagBodySize > original.size) return false  // corrupt/unparseable existing tag - refuse rather than risk mangling the file
            val hasExtendedHeader = (original[5].toInt() and 0x40) != 0
            val tagBody = original.copyOfRange(10, 10 + tagBodySize)
            keptFrames = copyNonPopmFrames(tagBody, majorVersion, hasExtendedHeader)
            restOfFile = original.copyOfRange(10 + tagBodySize, original.size)
        } else {
            keptFrames = ByteArray(0)
            restOfFile = original
        }

        val newBody = keptFrames + buildPopmFrame(rating)
        val newHeader = "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3, 0, 0) +  // version 2.3.0, flags 0 (no unsync/extended header/experimental)
            writeSyncSafeInt(newBody.size)
        val result = newHeader + newBody + restOfFile
        return writeAllBytes(context, uri, result)
    }

    // ---- FLAC (Vorbis Comment "RATING") ----

    private fun writeFlacRating(context: Context, uri: Uri, rating: Int): Boolean {
        val original = readAllBytes(context, uri) ?: return false
        if (original.size < 4 || original[0] != 'f'.code.toByte() || original[1] != 'L'.code.toByte() ||
            original[2] != 'a'.code.toByte() || original[3] != 'C'.code.toByte()
        ) return false

        data class Block(val type: Int, val body: ByteArray)
        val blocks = mutableListOf<Block>()
        var pos = 4
        while (pos + 4 <= original.size) {
            val isLast = (original[pos].toInt() and 0x80) != 0
            val type = original[pos].toInt() and 0x7F
            val len = plainInt(original, pos + 1, 3)
            val bodyStart = pos + 4
            if (bodyStart + len > original.size) return false  // corrupt/unparseable - refuse rather than risk mangling the file
            blocks.add(Block(type, original.copyOfRange(bodyStart, bodyStart + len)))
            pos = bodyStart + len
            if (isLast) break
        }
        if (blocks.isEmpty() || blocks[0].type != 0) return false  // STREAMINFO must always be block 0 - not a well-formed FLAC file otherwise
        val audioDataStart = pos
        val audioData = original.copyOfRange(audioDataStart, original.size)

        val percent = rating * 20  // 0,20,40,60,80,100
        val vorbisCommentIdx = blocks.indexOfFirst { it.type == 4 }
        val newVorbisBody = if (vorbisCommentIdx >= 0) {
            updateVorbisComments(blocks[vorbisCommentIdx].body, "RATING", percent.toString())
        } else {
            buildFreshVorbisComments("RATING", percent.toString())
        }
        if (vorbisCommentIdx >= 0) {
            blocks[vorbisCommentIdx] = Block(4, newVorbisBody)
        } else {
            blocks.add(1, Block(4, newVorbisBody))  // right after STREAMINFO - order otherwise doesn't matter to the format
        }

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { i, block ->
            val isLast = i == blocks.size - 1
            val header = ((if (isLast) 0x80 else 0) or block.type)
            out.write(header)
            out.write(writePlainInt(block.body.size, 3))
            out.write(block.body)
        }
        out.write(audioData)
        return writeAllBytes(context, uri, out.toByteArray())
    }

    private fun leInt(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLeInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /** Replaces (or adds) a single "KEY=value" comment inside an existing
     * VORBIS_COMMENT block's body, leaving its vendor string and every
     * other comment field untouched. */
    private fun updateVorbisComments(body: ByteArray, key: String, value: String): ByteArray {
        var p = 0
        if (p + 4 > body.size) return buildFreshVorbisComments(key, value)
        val vendorLen = leInt(body, p); p += 4
        if (p + vendorLen > body.size) return buildFreshVorbisComments(key, value)
        val vendor = body.copyOfRange(p, p + vendorLen); p += vendorLen
        if (p + 4 > body.size) return buildFreshVorbisComments(key, value)
        val count = leInt(body, p); p += 4
        val comments = mutableListOf<String>()
        repeat(count) {
            if (p + 4 <= body.size) {
                val len = leInt(body, p); p += 4
                if (p + len <= body.size) {
                    comments.add(String(body, p, len, Charsets.UTF_8))
                    p += len
                }
            }
        }
        val filtered = comments.filterNot { it.substringBefore('=', it).equals(key, ignoreCase = true) }
        val updated = filtered + "$key=$value"
        return assembleVorbisComments(vendor, updated)
    }

    private fun buildFreshVorbisComments(key: String, value: String): ByteArray {
        return assembleVorbisComments("CarVoicePlayer".toByteArray(Charsets.UTF_8), listOf("$key=$value"))
    }

    private fun assembleVorbisComments(vendor: ByteArray, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(writeLeInt(vendor.size))
        out.write(vendor)
        out.write(writeLeInt(comments.size))
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            out.write(writeLeInt(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }
}
