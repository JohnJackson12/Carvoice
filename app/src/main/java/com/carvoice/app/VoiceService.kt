package com.carvoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

class VoiceService : Service(), RecognitionListener {

    companion object {
        const val CHANNEL_ID = "voice_service_channel"
        const val NOTIF_ID = 1

        // Set by MainActivity while it's on screen. Left null (and just
        // skipped) when the activity isn't around - the service doesn't
        // depend on these at all, which is what lets it keep listening
        // and playing with the screen off.
        var logCallback: ((String) -> Unit)? = null
        var nowPlayingCallback: ((title: String, artist: String, playing: Boolean) -> Unit)? = null
        var progressCallback: ((positionMs: Int, durationMs: Int) -> Unit)? = null

        // Simple way for MainActivity's transport buttons to reach the
        // running service without binding - the service posts itself
        // here once alive.
        var instance: VoiceService? = null
    }

    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentIndex = -1
    private lateinit var audioManager: AudioManager

    // -- volume: the ONE source of truth is Prefs.playbackVolume(). Ducking
    // (for listening / for TTS) is always a temporary multiplier on top of
    // it, restored to exactly that value afterward - never to a hardcoded
    // 1.0. This is the actual fix for "volume gets messed up": before,
    // nothing tracked what level you'd actually set, so anything that
    // touched volume had nothing correct to restore to. --------------------
    private var baseVolume: Float = 0.85f
    private var focusRequest: AudioFocusRequest? = null

    private var trimEndSeconds = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    progressCallback?.invoke(mp.currentPosition, mp.duration)
                    if (trimEndSeconds > 0 && mp.isPlaying) {
                        val cutoffMs = mp.duration - (trimEndSeconds * 1000)
                        if (cutoffMs > 0 && mp.currentPosition >= cutoffMs) {
                            next()
                            return  // next() reschedules this loop for the new track
                        }
                    }
                } catch (e: IllegalStateException) {
                    // MediaPlayer between release() and the next prepare() - skip this tick.
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    private fun log(msg: String) = logCallback?.invoke(msg)

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        baseVolume = Prefs.playbackVolume(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        // While TTS is actually speaking, duck the music via the same
        // audio-focus mechanism as voice recognition - one mechanism for
        // both, so they can't fight each other or leave volume stuck low.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { requestDuckFocus() }
            override fun onDone(utteranceId: String?) { abandonDuckFocus() }
            override fun onError(utteranceId: String?) { abandonDuckFocus() }
        })

        MusicLibrary.addListener { onLibraryChanged() }
        if (MusicLibrary.all().isEmpty()) MusicLibrary.rescan(this)
        if (currentIndex == -1 && MusicLibrary.all().isNotEmpty()) {
            currentIndex = 0
            loadCurrentTrack(announce = false)
        }

        log("Unpacking speech model (first run only)...")
        StorageService.unpack(
            this, "model", "model",
            { model -> startRecognizer(model) },
            { exception -> log("[!] Couldn't load speech model: ${exception.message}") }
        )

        progressHandler.post(progressRunnable)
    }

    private fun startRecognizer(model: org.vosk.Model) {
        val wake = Prefs.wakeWord(this)
        val aliases = Prefs.wakeAliases(this)
        val wakeList = CommandParser.wakePhrases(wake, aliases).joinToString(" / ")
        log("Model ready - listening for: $wakeList")
        val titleKeys = MusicLibrary.all().map { TitleNormalizer.normalize(it.title) }
        val grammar = org.json.JSONArray(CommandParser.grammarPhrases(titleKeys, wake, aliases)).toString()
        val recognizer = Recognizer(model, 16000.0f, grammar)
        speechService?.stop()
        speechService?.shutdown()
        speechService = SpeechService(recognizer, 16000.0f)
        speechService?.startListening(this)
        this.loadedModel = model
    }

    private var loadedModel: org.vosk.Model? = null

    /** Called whenever Settings changes the wake word/aliases or the
     * folder list changes the song set - the recognizer's grammar has to
     * be rebuilt either way, same as update_wake_word / update_song_titles
     * on the Windows app's voice_control.py. */
    fun refreshRecognizer() {
        val model = loadedModel ?: return
        startRecognizer(model)
    }

    private fun onLibraryChanged() {
        if (currentIndex == -1 && MusicLibrary.all().isNotEmpty()) {
            currentIndex = 0
            loadCurrentTrack(announce = false)
        }
        refreshRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        progressHandler.removeCallbacksAndMessages(null)
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        mediaPlayer?.release()
        mediaPlayer = null
        abandonDuckFocus()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // -- audio focus / ducking - the actual volume-stability fix --------------

    private fun requestDuckFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build()
            }
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        mediaPlayer?.setVolume(baseVolume * 0.25f, baseVolume * 0.25f)
    }

    private fun abandonDuckFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        // Always restore to the level you actually set - never to 1.0f,
        // and never left at the ducked level.
        mediaPlayer?.setVolume(baseVolume, baseVolume)
    }

    // -- notification (required to keep listening while the screen is off / app backgrounded) --

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Voice control", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Car Voice Player")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // -- playback ---------------------------------------------------------------

    private fun loadCurrentTrack(announce: Boolean) {
        trimEndSeconds = 0
        val list = MusicLibrary.all()
        if (currentIndex !in list.indices) {
            nowPlayingCallback?.invoke("No songs found", "", false)
            return
        }
        val song = list[currentIndex]
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@VoiceService, song.uri)
            prepare()
            setVolume(baseVolume, baseVolume)  // always the persisted level, never a hardcoded default
            setOnCompletionListener { next() }
        }
        nowPlayingCallback?.invoke(song.title, song.artist, mediaPlayer?.isPlaying ?: false)
        updateNotification(song.title)
        if (announce) speak(song.title)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
    }

    fun setVolume(level: Float) {
        baseVolume = level.coerceIn(0f, 1f)
        Prefs.setPlaybackVolume(this, baseVolume)
        mediaPlayer?.setVolume(baseVolume, baseVolume)
    }

    fun currentVolume(): Float = baseVolume

    fun play() {
        mediaPlayer?.start()
        nowPlayingCallback?.invoke(currentTitle(), currentArtist(), true)
    }

    fun pause() {
        mediaPlayer?.pause()
        nowPlayingCallback?.invoke(currentTitle(), currentArtist(), false)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    private fun currentTitle(): String = MusicLibrary.all().getOrNull(currentIndex)?.title ?: ""
    private fun currentArtist(): String = MusicLibrary.all().getOrNull(currentIndex)?.artist ?: ""

    fun next() {
        val list = MusicLibrary.all()
        if (list.isEmpty()) return
        val wasPlaying = mediaPlayer?.isPlaying ?: false
        currentIndex = (currentIndex + 1) % list.size  // wraps to the start, matches the Windows app
        loadCurrentTrack(announce = false)
        if (wasPlaying) play()
    }

    fun previous() {
        val list = MusicLibrary.all()
        if (list.isEmpty()) return
        val wasPlaying = mediaPlayer?.isPlaying ?: false
        currentIndex = (currentIndex - 1 + list.size) % list.size
        loadCurrentTrack(announce = false)
        if (wasPlaying) play()
    }

    fun playSongAt(index: Int) {
        val list = MusicLibrary.all()
        if (index !in list.indices) return
        currentIndex = index
        loadCurrentTrack(announce = false)
        play()
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs.coerceIn(0, mediaPlayer?.duration ?: 0))
    }

    private fun status() = speak(currentTitle().ifBlank { "nothing loaded" })

    private fun skip(seconds: Int) {
        val mp = mediaPlayer ?: return
        mp.seekTo((mp.currentPosition + seconds * 1000).coerceIn(0, mp.duration))
    }

    private fun trim(frontSeconds: Int, endSeconds: Int) {
        val mp = mediaPlayer ?: return
        if (frontSeconds > 0) mp.seekTo(frontSeconds * 1000)
        trimEndSeconds = endSeconds
        speak("trim set, $frontSeconds seconds from the start, $endSeconds from the end")
    }

    private fun playSongByTitleKey(titleKey: String) {
        val idx = MusicLibrary.all().indexOfFirst { TitleNormalizer.normalize(it.title) == titleKey }
        if (idx == -1) { speak("couldn't find that song"); return }
        playSongAt(idx)
        speak("playing ${MusicLibrary.all()[idx].title}")
    }

    // -- org.vosk.android.RecognitionListener callbacks --

    override fun onPartialResult(hypothesis: String?) {
        // While the mic is picking up a partial match on the wake word,
        // duck now rather than waiting for the whole phrase to finish -
        // same idea as the Windows app's begin_listening() on a partial.
        val partial = try { JSONObject(hypothesis ?: "").optString("partial", "") } catch (e: Exception) { "" }
        if (partial.isNotBlank()) {
            val wake = Prefs.wakeWord(this); val aliases = Prefs.wakeAliases(this)
            if (CommandParser.wakePhrases(wake, aliases).any { partial.startsWith(it) }) {
                requestDuckFocus()
            }
        }
    }

    override fun onResult(hypothesis: String?) = handleHypothesis(hypothesis)
    override fun onFinalResult(hypothesis: String?) = handleHypothesis(hypothesis)
    override fun onError(exception: Exception?) { log("[!] recognizer error: ${exception?.message}") }
    override fun onTimeout() { abandonDuckFocus() }

    private fun handleHypothesis(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = try { JSONObject(hypothesis).optString("text", "") } catch (e: Exception) { "" }
        abandonDuckFocus()  // whatever happens next, volume goes back to normal first
        if (text.isBlank()) return
        log("heard: \"$text\"")

        val wake = Prefs.wakeWord(this)
        val aliases = Prefs.wakeAliases(this)
        val matchedWake = CommandParser.findMatchingWake(text, wake, aliases) ?: return
        val remainder = text.removePrefix(matchedWake).trim()
        val titleKeys = MusicLibrary.all().map { TitleNormalizer.normalize(it.title) }.toSet()

        when (val cmd = CommandParser.parse(remainder, titleKeys)) {
            is CommandParser.Command.Simple -> when (cmd.name) {
                "play" -> play()
                "pause" -> pause()
                "next" -> next()
                "previous" -> previous()
                "status" -> status()
                "delete", "undo" -> speak("not supported yet on this build")
            }
            is CommandParser.Command.Rate ->
                speak("rated ${cmd.value}, not saved - rating isn't wired up on this build yet")
            is CommandParser.Command.Skip -> skip(cmd.seconds)
            is CommandParser.Command.Trim -> trim(cmd.frontSeconds, cmd.endSeconds)
            is CommandParser.Command.PlaySong -> playSongByTitleKey(cmd.titleKey)
            null -> { /* not a recognized command - ignore */ }
        }
    }
}
