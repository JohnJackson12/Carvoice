package com.carvoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
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

        // Set by MainActivity while it's on screen, so log lines / now-playing
        // show up there too. Left null (and just skipped) when the activity
        // isn't around - the service itself doesn't depend on these at all,
        // which is what lets it keep listening with the screen off.
        var logCallback: ((String) -> Unit)? = null
        var nowPlayingCallback: ((String) -> Unit)? = null
    }

    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<Pair<Long, String>>()  // (mediaStoreId, title)
    private var currentIndex = -1

    // Per-song trim state (front seconds already-baked-in via seek, and an
    // end cutoff enforced by polling position - MediaPlayer has no native
    // "stop at" hook, so this mirrors what the desktop app's player.py
    // does with its own periodic position check).
    private val trimHandler = Handler(Looper.getMainLooper())
    private var trimEndSeconds = 0
    private val trimCheckRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && trimEndSeconds > 0 && mp.isPlaying) {
                val cutoffMs = mp.duration - (trimEndSeconds * 1000)
                if (cutoffMs > 0 && mp.currentPosition >= cutoffMs) {
                    next()
                    return  // next() reschedules this loop for the new track
                }
            }
            trimHandler.postDelayed(this, 500)
        }
    }

    private fun log(msg: String) {
        logCallback?.invoke(msg)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        buildPlaylist()
        loadCurrentTrack(announce = false)

        log("Unpacking speech model (first run only)...")
        StorageService.unpack(
            this, "model", "model",
            { model ->
                val wakeList = CommandParser.wakePhrases().joinToString(" / ")
                log("Model ready - listening for: $wakeList")
                val titleKeys = playlist.map { TitleNormalizer.normalize(it.second) }
                val grammar = org.json.JSONArray(CommandParser.grammarPhrases(titleKeys)).toString()
                val recognizer = Recognizer(model, 16000.0f, grammar)
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
            },
            { exception ->
                log("[!] Couldn't load speech model: ${exception.message}")
            }
        )

        trimHandler.post(trimCheckRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        trimHandler.removeCallbacksAndMessages(null)
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // -- notification (required to keep listening while the screen is off / app backgrounded) --

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Voice control", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Car Voice Player")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    // -- playlist / playback (MediaStore + MediaPlayer, no VLC needed on Android) --

    private fun buildPlaylist() {
        playlist.clear()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " ASC"
        val cursor: Cursor? = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sortOrder
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            while (it.moveToNext()) {
                playlist.add(Pair(it.getLong(idCol), it.getString(titleCol)))
            }
        }
        log("Found ${playlist.size} song(s) on this device.")
        if (playlist.isNotEmpty()) currentIndex = 0
    }

    private fun loadCurrentTrack(announce: Boolean) {
        trimEndSeconds = 0  // a fresh track starts untrimmed until a "trim" command sets it again
        if (currentIndex !in playlist.indices) {
            nowPlayingCallback?.invoke("No songs found")
            return
        }
        val (id, title) = playlist[currentIndex]
        val uri: Uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@VoiceService, uri)
            prepare()
            setOnCompletionListener { next() }
        }
        nowPlayingCallback?.invoke(title)
        updateNotification(title)
        if (announce) speak(title)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun play() {
        mediaPlayer?.start()
        speak("playing")
    }

    private fun pause() {
        mediaPlayer?.pause()
        speak("paused")
    }

    private fun next() {
        if (playlist.isEmpty()) return
        // Always wraps to the first song at the end of the list, same as
        // player.py's next() on the Windows app - no separate "repeat"
        // setting needed on this build.
        currentIndex = (currentIndex + 1) % playlist.size
        val wasPlaying = mediaPlayer?.isPlaying ?: false
        loadCurrentTrack(announce = false)
        if (wasPlaying) mediaPlayer?.start()
        speak("next")
    }

    private fun previous() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        val wasPlaying = mediaPlayer?.isPlaying ?: false
        loadCurrentTrack(announce = false)
        if (wasPlaying) mediaPlayer?.start()
        speak("previous")
    }

    private fun status() {
        val title = if (currentIndex in playlist.indices) playlist[currentIndex].second else "nothing loaded"
        speak(title)
    }

    private fun skip(seconds: Int) {
        val mp = mediaPlayer ?: return
        val targetMs = (mp.currentPosition + seconds * 1000).coerceIn(0, mp.duration)
        mp.seekTo(targetMs)
        speak("skipped $seconds seconds")
    }

    private fun trim(frontSeconds: Int, endSeconds: Int) {
        val mp = mediaPlayer ?: return
        if (frontSeconds > 0) mp.seekTo(frontSeconds * 1000)
        trimEndSeconds = endSeconds
        speak("trim set, $frontSeconds seconds from the start, $endSeconds from the end")
    }

    private fun playSongByTitleKey(titleKey: String) {
        val idx = playlist.indexOfFirst { TitleNormalizer.normalize(it.second) == titleKey }
        if (idx == -1) {
            speak("couldn't find that song")
            return
        }
        currentIndex = idx
        loadCurrentTrack(announce = false)
        mediaPlayer?.start()
        speak("playing ${playlist[idx].second}")
    }

    // -- org.vosk.android.RecognitionListener callbacks --

    override fun onPartialResult(hypothesis: String?) {
        // Not used for commands (only finished phrases trigger an action),
        // but useful to see in the log while tuning the wake word / mic.
    }

    override fun onResult(hypothesis: String?) {
        handleHypothesis(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        handleHypothesis(hypothesis)
    }

    override fun onError(exception: Exception?) {
        log("[!] recognizer error: ${exception?.message}")
    }

    override fun onTimeout() {
        // SpeechService restarts listening on its own after this.
    }

    private fun handleHypothesis(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            JSONObject(hypothesis).optString("text", "")
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) return
        log("heard: \"$text\"")

        val wake = CommandParser.findMatchingWake(text) ?: return
        val remainder = text.removePrefix(wake).trim()
        val titleKeys = playlist.map { TitleNormalizer.normalize(it.second) }.toSet()

        when (val cmd = CommandParser.parse(remainder, titleKeys)) {
            is CommandParser.Command.Simple -> when (cmd.name) {
                "play" -> play()
                "pause" -> pause()
                "next" -> next()
                "previous" -> previous()
                "status" -> status()
                // "delete"/"undo" aren't wired to file operations on this
                // build (no trash-folder concept on Android yet) - say so
                // instead of silently doing nothing.
                "delete", "undo" -> speak("not supported yet on this build")
            }
            is CommandParser.Command.Rate -> {
                // No persistent rating storage on this build yet (would
                // need writing back to MediaStore tags) - acknowledged by
                // voice so it's clear the word was heard correctly, same
                // spirit as the desktop app's spoken confirmation.
                speak("rated ${cmd.value}, not saved - rating isn't wired up on this build yet")
            }
            is CommandParser.Command.Skip -> skip(cmd.seconds)
            is CommandParser.Command.Trim -> trim(cmd.frontSeconds, cmd.endSeconds)
            is CommandParser.Command.PlaySong -> playSongByTitleKey(cmd.titleKey)
            null -> { /* not a recognized command - ignore */ }
        }
    }
}
