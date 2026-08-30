package com.carvoice.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * "Skip to vocals" heuristic - be clear about what this IS and ISN'T:
 *
 * This is NOT real vocal/instrumental separation. Actually isolating a
 * vocal track from a mix requires a full source-separation ML model
 * (Spleeter, Demucs, and similar) - multi-hundred-MB models that are far
 * too heavy to bundle or run on a background service on a car head unit.
 * This app does not attempt that.
 *
 * What it actually does: decodes the first [MAX_ANALYSIS_SECONDS] of a
 * track to PCM, measures short-time loudness (RMS) in ~100ms windows,
 * and looks for the point where the track's energy jumps up and STAYS up
 * - relative to however quiet/sparse its own opening was. On a typical
 * song with a bare instrumental intro (thin drums/guitar/pad) followed by
 * the full band + vocals coming in together, that jump usually lands
 * close to where the vocals start. It is a loudness-based guess, not a
 * vocal detector, and it WILL guess wrong on:
 *   - songs where vocals enter quietly over an already-full-energy intro
 *   - songs with a loud instrumental intro (energy jump unrelated to
 *     vocals - a big drum fill, a guitar solo intro, etc.)
 *   - songs with no real intro at all (vocals from second one)
 *   - live recordings / crowd noise before the track "really" starts
 *
 * It returns null (meaning: don't skip anything) whenever it doesn't find
 * a clear, sustained jump - deliberately conservative, since skipping
 * into the middle of a real verse is worse than not skipping at all.
 */
object VocalIntroDetector {
    private const val MAX_ANALYSIS_SECONDS = 60
    private const val WINDOW_MS = 100L
    private const val MIN_ONSET_SECONDS = 3     // don't bother "skipping" less than this
    private const val MAX_ONSET_SECONDS = 45    // a "detection" beyond this is more likely noise than a real intro
    private const val SUSTAIN_WINDOWS = 8       // ~800ms of sustained energy required, not just one loud transient
    private const val JUMP_MULTIPLIER = 1.6     // how much louder than its own baseline counts as "kicked in"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs the decode+analysis on a background thread (real file I/O and
     * decoding - never call from the main thread); [onResult] is called
     * back on the main thread with the guessed intro length in seconds,
     * or null if nothing confident was found. */
    fun detectIntroEndSeconds(context: Context, uri: Uri, onResult: (Int?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val result = try {
                analyze(appContext, uri)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { onResult(result) }
        }.start()
    }

    private fun analyze(context: Context, uri: Uri): Int? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIndex = i; format = f; break }
            }
            if (audioTrackIndex < 0 || format == null) return null
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val windowSamplesPerChannel = (sampleRate * WINDOW_MS / 1000L).toInt().coerceAtLeast(1)
            val maxSamplesPerChannel = sampleRate.toLong() * MAX_ANALYSIS_SECONDS

            val rmsWindows = mutableListOf<Double>()
            var windowSumSquares = 0.0
            var windowSampleCount = 0
            var totalSamplesPerChannel = 0L

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var doneEnough = false

            while (!sawOutputEOS && !doneEnough) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            var i = 0
                            val n = shortBuffer.remaining()
                            while (i < n) {
                                // Downmix to mono by averaging every
                                // channel's sample within this frame -
                                // loudness is all this cares about, not
                                // stereo image.
                                var frameSum = 0.0
                                var c = 0
                                while (c < channelCount && i < n) {
                                    frameSum += shortBuffer.get(i)
                                    i++; c++
                                }
                                val sample = frameSum / channelCount
                                windowSumSquares += sample * sample
                                windowSampleCount++
                                totalSamplesPerChannel++

                                if (windowSampleCount >= windowSamplesPerChannel) {
                                    rmsWindows.add(sqrt(windowSumSquares / windowSampleCount))
                                    windowSumSquares = 0.0
                                    windowSampleCount = 0
                                }
                                if (totalSamplesPerChannel >= maxSamplesPerChannel) {
                                    doneEnough = true
                                    break
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }
                // INFO_OUTPUT_FORMAT_CHANGED / INFO_TRY_AGAIN_LATER: nothing
                // to do - sampleRate/channelCount were already read from the
                // extractor's format up front, and the loop just retries.
            }

            return findOnsetSeconds(rmsWindows)
        } finally {
            try { codec?.stop() } catch (e: Exception) { /* already stopped/never started */ }
            try { codec?.release() } catch (e: Exception) { /* already gone */ }
            try { extractor.release() } catch (e: Exception) { /* already gone */ }
        }
    }

    private fun findOnsetSeconds(rms: List<Double>): Int? {
        if (rms.size < SUSTAIN_WINDOWS * 2) return null
        val windowsPerSecond = 1000.0 / WINDOW_MS

        // Baseline = the median RMS of the first few seconds - i.e.
        // whatever level THIS song's own opening sits at (robust to one
        // loud transient in there, unlike a plain average would be).
        val baselineWindows = (windowsPerSecond * 4).toInt().coerceAtMost(rms.size)
        val baseline = rms.take(baselineWindows).sorted().let {
            if (it.isEmpty()) 0.0 else it[it.size / 2]
        }
        if (baseline <= 1.0) return null  // near-silent lead-in - not enough signal to judge a "jump" against

        val threshold = baseline * JUMP_MULTIPLIER
        val minIndex = (MIN_ONSET_SECONDS * windowsPerSecond).toInt()
        val maxIndex = (MAX_ONSET_SECONDS * windowsPerSecond).toInt().coerceAtMost(rms.size - SUSTAIN_WINDOWS)

        var i = minIndex
        while (i < maxIndex) {
            if (rms[i] >= threshold) {
                val sustained = (i until (i + SUSTAIN_WINDOWS)).all { idx -> rms[idx] >= threshold * 0.85 }
                if (sustained) return (i / windowsPerSecond).toInt()
            }
            i++
        }
        return null
    }
}
