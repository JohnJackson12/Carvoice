package com.carvoice.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener

/**
 * Replaces org.vosk.android.SpeechService with a capture loop this app
 * fully controls. The library's own SpeechService opens the mic with
 * AudioSource.VOICE_RECOGNITION and feeds it to the recognizer completely
 * unprocessed, at whatever level the hardware happens to produce - fine
 * for a phone held near your mouth, not fine for a mic built into a car
 * dashboard/head-unit picking up a driver a couple feet away over road
 * noise. That mismatch is exactly "works if I speak too loud and still
 * have to repeat myself" - the recognizer was never getting a strong
 * enough signal to begin with, no matter how the wake-word/grammar side
 * of things was tuned.
 *
 * Two independent boosts are layered here, since either one alone can be
 * missing/ineffective on a given device:
 *  1. The platform's own AutomaticGainControl/NoiseSuppressor/
 *     AcousticEchoCanceler pre-processor effects, attached to this
 *     specific AudioRecord session - attempted whenever the device
 *     actually offers them (plenty of budget car-head-unit SoCs don't).
 *  2. A user-adjustable digital gain multiplier (Prefs.micSensitivity,
 *     exposed as the Settings "Mic sensitivity" slider) applied to every
 *     raw PCM sample by hand before it reaches the recognizer - the one
 *     lever that's ALWAYS available regardless of what the hardware/HAL
 *     supports, which is what makes this "full" sensitivity support
 *     rather than "whatever the platform happens to expose".
 *
 * Talks to [Recognizer] directly (acceptWaveForm/getResult/
 * getPartialResult/getFinalResult) instead of going through
 * SpeechService, and reports through the same org.vosk.android.
 * RecognitionListener interface VoiceService already implements, so
 * swapping this in is a drop-in change at the call site.
 */
class MicCapture(
    private val recognizer: Recognizer,
    private val sampleRate: Float,
) {
    @Volatile private var gain: Float = 1f
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    /** Safe to call anytime, including mid-capture - takes effect on the
     * very next audio chunk read (the loop reads [gain] fresh every
     * iteration), so dragging the Settings slider audibly changes pickup
     * sensitivity in real time without needing to restart listening. */
    fun setGain(multiplier: Float) {
        gain = multiplier.coerceIn(1f, 10f)
    }

    fun startListening(listener: RecognitionListener) {
        if (running) return
        val bufferSizeSamples = Math.round(sampleRate * BUFFER_SIZE_SECONDS)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val requestedBuf = (bufferSizeSamples * 2).coerceAtLeast(if (minBuf > 0) minBuf else bufferSizeSamples * 2)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                requestedBuf
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError(RuntimeException("Couldn't open the microphone"))
            return
        }
        recorder = rec
        attachPreprocessingEffects(rec.audioSessionId)

        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                rec.startRecording()
            } catch (e: Exception) {
                listener.onError(e)
                running = false
                return@Thread
            }
            val buffer = ShortArray(bufferSizeSamples)
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n < 0) {
                    // A negative return here is one of AudioRecord.ERROR_*,
                    // not a byte count - treat as fatal for this session
                    // rather than looping forever on a dead device.
                    listener.onError(RuntimeException("Microphone read error ($n)"))
                    break
                }
                if (n == 0) continue
                val currentGain = gain
                if (currentGain > 1.001f) applyGain(buffer, n, currentGain)
                try {
                    val isFinal = recognizer.acceptWaveForm(buffer, n)
                    if (isFinal) listener.onResult(recognizer.result) else listener.onPartialResult(recognizer.partialResult)
                } catch (e: Exception) {
                    // One bad chunk shouldn't end listening for the rest of
                    // the drive - Vosk's own JNI layer can throw on a
                    // malformed/edge-case buffer often enough that this
                    // needs to be resilient here, not just at start-up.
                }
            }
            try { listener.onFinalResult(recognizer.finalResult) } catch (e: Exception) { /* shutting down anyway */ }
        }
        thread?.start()
    }

    private fun attachPreprocessingEffects(sessionId: Int) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) { /* not available on this device - the manual gain below still applies */ }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) { /* not available on this device */ }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) { /* not available on this device */ }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (e: InterruptedException) { /* ignore - shutting down regardless */ }
        thread = null
        try { recorder?.stop() } catch (e: Exception) { /* already stopped/never started */ }
    }

    fun shutdown() {
        stop()
        try { agc?.release() } catch (e: Exception) {}
        try { noiseSuppressor?.release() } catch (e: Exception) {}
        try { echoCanceler?.release() } catch (e: Exception) {}
        agc = null; noiseSuppressor = null; echoCanceler = null
        try { recorder?.release() } catch (e: Exception) {}
        recorder = null
    }

    /** In-place linear gain with hard clipping at the 16-bit PCM range -
     * a plain multiply-and-clamp, deliberately simple: this only needs to
     * make quiet speech loud enough for the recognizer to work with, not
     * sound broadcast-quality, and a fancier compressor/limiter would be
     * real DSP work for no real benefit to recognition accuracy here. */
    private fun applyGain(buffer: ShortArray, len: Int, multiplier: Float) {
        for (i in 0 until len) {
            val boosted = buffer[i] * multiplier
            buffer[i] = when {
                boosted > Short.MAX_VALUE -> Short.MAX_VALUE
                boosted < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> boosted.toInt().toShort()
            }
        }
    }

    companion object {
        // Matches vosk-android's own SpeechService chunking (0.2s per
        // read) - no reason to diverge from a value the recognizer is
        // already tuned to expect a steady stream of.
        private const val BUFFER_SIZE_SECONDS = 0.2f
    }
}
