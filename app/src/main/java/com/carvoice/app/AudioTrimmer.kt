package com.carvoice.app

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Physically cuts audio out of the front/end of a song's own file and
 * OVERWRITES it - this is the destructive counterpart to the trim dots on
 * the main seek bar, which only preview a non-destructive playback
 * start/end skip (see VoiceService.setTrim / SongMetadataStore). This is
 * what actually runs when the trim (scissors) button is tapped, or the
 * voice command "<wake> apply trim" is spoken.
 *
 * MP3-only for now, by an accurate frame-boundary cut rather than any
 * decode/re-encode: MP3 is a sequence of self-contained frames, each one
 * independently decodable and each covering a fixed, computable slice of
 * time (see frameLengthBytes/samplesPerFrame below) - which means whole
 * frames can be dropped from the start/end of the byte stream with zero
 * quality loss and no need for any decoder/encoder at all, similar in
 * spirit to how AudioTagWriter edits ID3 frames directly rather than
 * re-encoding the file. FLAC/M4A/OGG/WAV would each need their own
 * format-specific framing logic (or a real decode+re-encode pass, which
 * this app has no codec library for) to do the same thing losslessly -
 * out of scope for now; those formats keep using the non-destructive
 * playback-time trim only, and [trim] returns [Result.Unsupported] for
 * them rather than doing anything destructive it can't do accurately.
 */
object AudioTrimmer {

    sealed class Result {
        object Success : Result()
        data class Unsupported(val reason: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** [frontSeconds]/[endSeconds] - same shape as VoiceService.setTrim.
     * Real file I/O + in-memory rebuild of the whole file - never call
     * from the main thread. */
    fun trim(context: Context, uri: Uri, frontSeconds: Int, endSeconds: Int): Result {
        if (frontSeconds <= 0 && endSeconds <= 0) return Result.Failed("Nothing to trim - both points are at 0")
        val name = EmbeddedArt.displayName(context, uri) ?: return Result.Failed("Couldn't read the file")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "mp3") {
            return Result.Unsupported(
                "Real-time file cutting currently only supports MP3. \"$name\" will keep using " +
                    "the start/end skip instead (still saved, just not physically cut from the file)."
            )
        }
        return try {
            trimMp3(context, uri, frontSeconds, endSeconds)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Trim failed")
        }
    }

    // ---- shared file I/O (same approach as AudioTagWriter) ----

    private fun readAllBytes(context: Context, uri: Uri): ByteArray? = try {
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

    private fun writeAllBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean = try {
        if (uri.scheme == "file") {
            File(uri.path ?: return false).writeBytes(bytes)
        } else {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
        }
        true
    } catch (e: Exception) {
        false
    }

    // ---- MPEG audio frame parsing ----

    private val BITRATE_KBPS = mapOf(
        // [mpegVersionIsV1][layer] -> table indexed by the 4-bit bitrate index (0 and 15 are invalid/free/bad)
        Triple(true, 1, 0) to intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, -1),
        Triple(true, 2, 0) to intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, -1),
        Triple(true, 3, 0) to intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1),
        Triple(false, 1, 0) to intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, -1),
        Triple(false, 2, 0) to intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1),
        Triple(false, 3, 0) to intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1),
    )

    private data class FrameInfo(
        val start: Int,
        val length: Int,
        val samples: Int,
        val sampleRate: Int,
    )

    /** Parses the MPEG audio frame header at [pos], or null if it isn't a
     * valid frame sync there. */
    private fun parseFrame(data: ByteArray, pos: Int): FrameInfo? {
        if (pos + 4 > data.size) return null
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        val b2 = data[pos + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null  // not a frame sync

        val versionBits = (b1 shr 3) and 0x03  // 00=MPEG2.5, 10=MPEG2, 11=MPEG1
        val layerBits = (b1 shr 1) and 0x03    // 01=Layer3, 10=Layer2, 11=Layer1
        if (versionBits == 1 || layerBits == 0) return null  // reserved values - not a real frame

        val isMpeg1 = versionBits == 3
        val layer = when (layerBits) { 3 -> 1; 2 -> 2; else -> 3 }
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

        val bitrateTable = BITRATE_KBPS[Triple(isMpeg1, layer, 0)] ?: return null
        val bitrateKbps = bitrateTable[bitrateIndex]
        if (bitrateKbps <= 0) return null

        val sampleRate = when {
            versionBits == 3 -> intArrayOf(44100, 48000, 32000)[sampleRateIndex]  // MPEG1
            versionBits == 2 -> intArrayOf(22050, 24000, 16000)[sampleRateIndex]  // MPEG2
            else -> intArrayOf(11025, 12000, 8000)[sampleRateIndex]               // MPEG2.5
        }
        val bitrateBps = bitrateKbps * 1000

        val samplesPerFrame = when {
            layer == 1 -> 384
            layer == 2 -> 1152
            isMpeg1 -> 1152  // Layer III, MPEG1
            else -> 576      // Layer III, MPEG2/2.5
        }

        val frameLength = when (layer) {
            1 -> ((12 * bitrateBps / sampleRate) + padding) * 4
            2 -> (144 * bitrateBps / sampleRate) + padding
            else -> if (isMpeg1) (144 * bitrateBps / sampleRate) + padding else (72 * bitrateBps / sampleRate) + padding
        }
        if (frameLength <= 4 || pos + frameLength > data.size) return null

        return FrameInfo(pos, frameLength, samplesPerFrame, sampleRate)
    }

    /** True if the frame at [frame] is a Xing/Info/VBRI VBR-header frame -
     * metadata about the whole file, not real audio, so it's dropped from
     * the output entirely rather than counted as playable content. A
     * plain linear scan for the tag's ASCII marker rather than computing
     * its exact expected offset (which depends on MPEG version/channel
     * mode) - the frame is only a few hundred bytes, so this is cheap and
     * avoids needing to also decode the channel-mode bits just for this. */
    private fun isVbrHeaderFrame(data: ByteArray, frame: FrameInfo): Boolean {
        val end = (frame.start + frame.length).coerceAtMost(data.size)
        var i = frame.start + 4
        while (i + 4 <= end) {
            val matchesAt = { a: Char, b: Char, c: Char, d: Char ->
                data[i] == a.code.toByte() && data[i + 1] == b.code.toByte() &&
                    data[i + 2] == c.code.toByte() && data[i + 3] == d.code.toByte()
            }
            if (matchesAt('X', 'i', 'n', 'g') || matchesAt('I', 'n', 'f', 'o') || matchesAt('V', 'B', 'R', 'I')) return true
            i++
        }
        return false
    }

    private fun syncSafeInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or (b[offset + 3].toInt() and 0x7F)

    private fun trimMp3(context: Context, uri: Uri, frontSeconds: Int, endSeconds: Int): Result {
        val original = readAllBytes(context, uri) ?: return Result.Failed("Couldn't read the file")

        var audioStart = 0
        if (original.size >= 10 && original[0] == 'I'.code.toByte() && original[1] == 'D'.code.toByte() && original[2] == '3'.code.toByte()) {
            val tagSize = syncSafeInt(original, 6)
            if (tagSize >= 0 && 10 + tagSize <= original.size) audioStart = 10 + tagSize
        }

        var audioEnd = original.size
        if (audioEnd - audioStart >= 128) {
            val tagStart = audioEnd - 128
            if (original[tagStart] == 'T'.code.toByte() && original[tagStart + 1] == 'A'.code.toByte() && original[tagStart + 2] == 'G'.code.toByte()) {
                audioEnd = tagStart
            }
        }

        // Single pass: walk every real audio frame, recording its byte
        // range and the cumulative playback time BEFORE it starts. Kept
        // as a list rather than computed in two passes since the total
        // duration isn't known until the whole file's been walked once
        // anyway (VBR files have a different bitrate per frame).
        data class Entry(val frame: FrameInfo, val timeBeforeMs: Long)
        val entries = mutableListOf<Entry>()
        var pos = audioStart
        var timeMs = 0L
        var sawAnyFrame = false
        while (pos < audioEnd) {
            val frame = parseFrame(original, pos)
            if (frame == null) { pos++; continue }  // byte-level resync, same tolerance real MP3 decoders use for junk/padding between frames
            sawAnyFrame = true
            if (!isVbrHeaderFrame(original, frame)) {
                entries.add(Entry(frame, timeMs))
                timeMs += (frame.samples.toLong() * 1000L) / frame.sampleRate
            }
            pos = frame.start + frame.length
        }
        if (!sawAnyFrame || entries.isEmpty()) return Result.Failed("Couldn't find any audio frames to trim")

        val totalMs = timeMs
        val frontMs = frontSeconds * 1000L
        val keepUntilMs = totalMs - (endSeconds * 1000L)
        if (frontMs + (totalMs - keepUntilMs) >= totalMs) {
            return Result.Failed("That trim would remove the entire song")
        }

        val startEntry = entries.firstOrNull { it.timeBeforeMs >= frontMs } ?: entries.last()
        val cutStart = startEntry.frame.start
        val endEntry = entries.firstOrNull { it.timeBeforeMs >= keepUntilMs }
        val cutEnd = endEntry?.frame?.start ?: audioEnd
        if (cutEnd <= cutStart) return Result.Failed("That trim would remove the entire song")

        val result = ByteArrayOutputStream(audioStart + (cutEnd - cutStart) + (original.size - audioEnd))
        result.write(original, 0, audioStart)                    // ID3v2 tag, untouched
        result.write(original, cutStart, cutEnd - cutStart)       // the kept audio window
        if (audioEnd < original.size) result.write(original, audioEnd, original.size - audioEnd)  // ID3v1 tag, untouched

        return if (writeAllBytes(context, uri, result.toByteArray())) Result.Success
        else Result.Failed("Couldn't save the trimmed file - it may be read-only")
    }
}
