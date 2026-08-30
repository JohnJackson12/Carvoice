package com.carvoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import org.vosk.Model
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
        // Fired whenever the current song's rating/trim is known (right
        // after a track loads, and after either one is changed) - lets
        // the UI show what's already saved for THIS song rather than
        // stale values left over from the previous one.
        var ratingCallback: ((Int) -> Unit)? = null
        var trimCallback: ((frontSeconds: Int, endSeconds: Int) -> Unit)? = null

        // Simple way for MainActivity's transport buttons to reach the
        // running service without binding - the service posts itself
        // here once alive.
        var instance: VoiceService? = null

        // Set by MainActivity while it's around - lets a voice
        // "<wake> delete" / "<wake> undo" command complete an OS consent
        // dialog (RecoverableSecurityException, or a MediaStore
        // trash/untrash request), which is a hard platform requirement
        // that only a real Activity can show. Left null (and just
        // reported back as a SongDeleter.Outcome.Failed - see
        // runDeleteOutcome()) when MainActivity isn't currently around -
        // the service keeps listening/playing regardless, same as every
        // other MainActivity-optional callback here, but a delete that
        // genuinely needs a tap can't silently happen with no one able to
        // tap it.
        var consentResolver: ((SongDeleter.Outcome.NeedsConsent, (SongDeleter.Outcome) -> Unit) -> Unit)? = null
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

    // "Skip to vocals" (see VocalIntroDetector) - keyed by song uriKey so
    // a song already analyzed this session (whether by the auto-skip
    // setting or a manual voice command) doesn't get re-decoded every
    // time it comes up again. A key present with a null value means
    // "already tried, found nothing confident" - also not worth retrying.
    private val introSecondsCache = mutableMapOf<String, Int?>()
    private var introAnalysisRequestId = 0
    // SKIP: a single GLOBAL, live value - matches the Windows app's
    // config.json "skip_seconds" / Player.set_skip_seconds(). NOT a
    // relative fast-forward (that's what this used to be here, which
    // never actually matched what "skip" does on Windows even before
    // that app's own v19 rework - see effectiveStart() below).
    private var skipSeconds: Int = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private var lastResumeSaveAt = 0L
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    progressCallback?.invoke(mp.currentPosition, mp.duration)
                    // Saved periodically, not just on a clean exit - a
                    // car's ignition turning off is an abrupt kill with no
                    // chance for onDestroy()/onTaskRemoved() to run first,
                    // so this is what actually makes "resume where I left
                    // off" reliable in the real use case this is for.
                    val now = System.currentTimeMillis()
                    if (mp.isPlaying && now - lastResumeSaveAt > 3000) {
                        persistResumeState()
                        lastResumeSaveAt = now
                    }
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

    private val libraryListener: () -> Unit = { onLibraryChanged() }

    private fun log(msg: String) = logCallback?.invoke(msg)

    /** Loads from wherever playback was left off last time (survives both
     * a clean exit AND an abrupt one - a car's ignition turning off never
     * gives an app a graceful shutdown, so this relies on the periodic
     * save in progressRunnable, not just onDestroy) and starts playing
     * immediately, with no taps needed - matches the point of auto-open
     * on boot: minimal touch after setup. Reads from the instantly-loaded
     * cache, not the (slower) fresh rescan, so this doesn't wait on a
     * large library's folder scan to finish first. Falls back to the
     * first song, cued but NOT auto-played, if there's no saved position
     * yet (first run) or the saved song isn't in the cache (e.g. it was
     * removed from the library since). */
    private fun resumeLastPlaybackOrDefault() {
        val list = MusicLibrary.all()
        if (list.isEmpty()) return
        val savedUri = Prefs.lastSongUri(this)
        val savedIndex = if (savedUri != null) list.indexOfFirst { it.uri.toString() == savedUri } else -1
        if (savedIndex == -1) {
            currentIndex = 0
            loadCurrentTrack(announce = false)
            return
        }
        currentIndex = savedIndex
        loadCurrentTrack(announce = false)
        // loadCurrentTrack() can fall back to a DIFFERENT song if the
        // saved one failed to load (stale file, revoked permission) - in
        // that case currentIndex no longer points at savedIndex, and the
        // saved position/trim belong to a song we're no longer loading,
        // so only apply the resume-seek if we actually got the song we
        // meant to resume. The fallback song just plays from its own
        // normal effective-start instead, which loadCurrentTrack() already
        // set up on its own.
        if (currentIndex != savedIndex || mediaPlayer == null) {
            if (mediaPlayer != null) play()  // fallback song loaded fine - still auto-play it
            return
        }
        val uriKey = list[savedIndex].uri.toString()
        val effectiveStartMs = maxOf(skipSeconds, SongMetadataStore.trimFront(this, uriKey)) * 1000
        val resumeAtMs = maxOf(Prefs.lastPositionMs(this), effectiveStartMs)
        mediaPlayer?.seekTo(resumeAtMs)
        play()
    }

    private fun persistResumeState() {
        val uriKey = currentUriKey() ?: return
        val position = mediaPlayer?.currentPosition ?: return
        Prefs.setLastPlayback(this, uriKey, position)
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        baseVolume = Prefs.playbackVolume(this)
        skipSeconds = Prefs.skipSeconds(this)
        setUpAutomaticMicRouting()

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

        MusicLibrary.addListener(libraryListener)
        // Folder scanning (especially SAF folder trees) is IPC-bound and
        // can genuinely take a while for a large, multi-folder library -
        // doing this on the main thread inside onCreate() risks an ANR.
        // A cached list loads instantly so something's usually already
        // playable while the real rescan runs in the background.
        MusicLibrary.loadCache(this)
        resumeLastPlaybackOrDefault()
        Thread {
            MusicLibrary.rescan(this)
        }.start()

        log("Unpacking speech model (first run only)...")
        // StorageService.unpack() re-runs its own internal "is this
        // already unpacked" comparison EVERY time it's called, even when
        // the model directory is already fully there from a previous
        // launch - and something in that comparison could throw a
        // NullPointerException on a second call (StorageService.java:79,
        // inside the library itself - confirmed from an actual logcat,
        // and traced to the model's "uuid" marker file: the CI build used
        // to write an EMPTY one, which made the library's own uuid check
        // compare against null. Fixed at the source in
        // .github/workflows/build-apk.yml). The real fix here, on top of
        // that, is to just not call unpack() again once the model's
        // already unpacked: load it straight from the already-unpacked
        // directory instead, which also starts up faster since there's no
        // redundant copy/verify pass every single launch.
        //
        // IMPORTANT: StorageService.unpack()/sync() always writes to
        // getExternalFilesDir(null)/<targetPath>/<sourcePath> - here that's
        // getExternalFilesDir(null)/model/model, since both are "model"
        // below - NEVER to filesDir. Checking filesDir (as this used to)
        // never matched anything real, so this "skip if already unpacked"
        // branch never actually fired, and unpackModelFresh() ran on
        // EVERY launch regardless - silently defeating the fix above and
        // the whole reason this comparison sat in the crash's path at all.
        val modelDir = java.io.File(getExternalFilesDir(null), "model/model")
        if (modelDir.exists() && (modelDir.listFiles()?.isNotEmpty() == true)) {
            Thread {
                try {
                    val model = Model(modelDir.absolutePath)
                    progressHandler.post { startRecognizer(model) }
                } catch (e: Exception) {
                    CrashLog.record(this, "Loading already-unpacked model failed: ${e}")
                    // Fall back to a fresh unpack in case the existing
                    // directory is actually corrupt/incomplete, not just
                    // "already there".
                    progressHandler.post { unpackModelFresh() }
                }
            }.start()
        } else {
            unpackModelFresh()
        }

        progressHandler.post(progressRunnable)
    }

    private fun unpackModelFresh() {
        StorageService.unpack(
            this, "model", "model",
            { model -> startRecognizer(model) },
            { exception ->
                log("[!] Couldn't load speech model: ${exception.message}")
                CrashLog.record(this, "StorageService.unpack failed: ${exception}")
            }
        )
    }

    // "<wake> play <song title>" adds one grammar phrase per song per wake
    // variant. Vosk's grammar-locked recognizer is built for hundreds, maybe
    // low thousands of phrases - a very large library (tens of thousands of
    // songs) would blow that up to tens of thousands of phrases, risking a
    // slow/fragile recognizer or degraded accuracy for everything, not just
    // the title-matching. Past this many songs, "play <title>" by voice is
    // disabled rather than risk the whole recognizer - browsing/search in
    // the app still works normally regardless of library size.
    private val MAX_VOICE_TITLES = 400

    private fun startRecognizer(model: org.vosk.Model) {
        val wake = Prefs.wakeWord(this)
        val aliases = Prefs.wakeAliases(this)
        val wakeList = CommandParser.wakePhrases(wake, aliases).joinToString(" / ")
        val allTitleKeys = MusicLibrary.all().map { TitleNormalizer.normalize(it.title) }
        val titleKeys = if (allTitleKeys.size > MAX_VOICE_TITLES) {
            log("Library has ${allTitleKeys.size} songs - \"play <song name>\" by voice is " +
                "off above $MAX_VOICE_TITLES songs to keep recognition reliable. Everything " +
                "else (play/pause/next/skip/trim/rating, and browsing/search in the app) still works.")
            emptyList()
        } else allTitleKeys
        log("Model ready - listening for: $wakeList")
        // Building the recognizer's decoding graph from the grammar is
        // real native work, not free even at a capped phrase count - keep
        // it off the main thread so a slow build can never cause an ANR.
        // Also genuinely uncaught before this fix - any failure here
        // (a malformed grammar, a native-layer issue) killed the whole
        // app with zero trace, on a thread with no default handling.
        Thread {
            try {
                val grammar = org.json.JSONArray(CommandParser.grammarPhrases(titleKeys, wake, aliases)).toString()
                val recognizer = Recognizer(model, 16000.0f, grammar)
                speechService?.stop()
                speechService?.shutdown()
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
                loadedModel = model
            } catch (e: Exception) {
                CrashLog.record(this, "startRecognizer failed: ${e}")
                log("[!] Couldn't start the recognizer: ${e.message}")
            }
        }.start()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Fires specifically when the app is swiped away from the recent-apps
     * list - i.e. "closed/exited", as distinct from the screen just
     * turning off or you switching to another app for a moment, neither
     * of which call this. That distinction matters here: turning the mic
     * off on every screen-off would defeat the whole point of this app
     * (listening while the tablet is mounted and the screen is dark
     * while driving) - it should only stop when you've actually chosen
     * to close it. Also switched onStartCommand above to START_NOT_STICKY
     * so Android doesn't auto-restart (and silently turn the mic back on)
     * after this. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        log("App closed - stopping voice control and playback.")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        persistResumeState()
        instance = null
        tearDownAutomaticMicRouting()
        MusicLibrary.removeListener(libraryListener)
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

    // -- automatic mic routing - no manual device picker, matches the
    //    Windows app NOT having one either; unlike desktop though, Android
    //    doesn't let an app "pick" an input device the way PortAudio does -
    //    the OS decides which mic is "current" based on what's connected,
    //    and the fix here is making sure a fresh AudioRecord gets created
    //    (which then follows the OS's current choice) whenever that
    //    changes, rather than the recognizer staying stuck on whatever mic
    //    was active when it first started listening. ---------------------

    private var scoActive = false
    private var micRoutingRestartPending = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handleInputDeviceChange(addedDevices)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handleInputDeviceChange(removedDevices)
        }
    }

    private fun handleInputDeviceChange(devices: Array<out AudioDeviceInfo>) {
        val inputDevices = devices.filter { it.isSource }
        if (inputDevices.isEmpty()) return
        log("Mic device change detected (${inputDevices.joinToString { it.productName?.toString() ?: it.type.toString() }}) - switching to it.")
        val hasBluetoothMic = inputDevices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (hasBluetoothMic) startBluetoothScoIfAvailable()
        // A single physical plug/unplug event can fire this callback more
        // than once in quick succession - debounce so a Bluetooth
        // headset's mic connecting doesn't restart the recognizer 3 times
        // in one second.
        if (!micRoutingRestartPending) {
            micRoutingRestartPending = true
            Handler(Looper.getMainLooper()).postDelayed({
                micRoutingRestartPending = false
                refreshRecognizer()
            }, 1200)
        }
    }

    private fun startBluetoothScoIfAvailable() {
        try {
            if (!scoActive && audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                scoActive = true
                log("Bluetooth mic available - routing audio input through it.")
            }
        } catch (e: SecurityException) {
            // Some OEMs gate this behind a Bluetooth permission this app
            // doesn't request (it's not required on stock Android for
            // AudioManager's SCO methods specifically) - if that happens,
            // the built-in/wired mic keeps working normally regardless;
            // only the Bluetooth-specific auto-routing is skipped.
            log("[!] Couldn't start Bluetooth mic routing (permission): ${e.message}")
        }
    }

    private fun setUpAutomaticMicRouting() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        // Cover the case where a Bluetooth/wired/USB mic is ALREADY
        // connected before the service even starts (e.g. it was paired
        // and left connected from a previous drive) - onAudioDevicesAdded
        // only fires for devices connecting AFTER registration.
        val alreadyConnected = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (alreadyConnected.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
            startBluetoothScoIfAvailable()
        }
    }

    private fun tearDownAutomaticMicRouting() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        if (scoActive) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            scoActive = false
        }
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

    private fun loadCurrentTrack(announce: Boolean, fallbackDepth: Int = 0) {
        trimEndSeconds = 0
        val list = MusicLibrary.all()
        if (currentIndex !in list.indices) {
            nowPlayingCallback?.invoke("No songs found", "", false)
            return
        }
        val song = list[currentIndex]
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@VoiceService, song.uri)
                prepare()
                setVolume(baseVolume, baseVolume)  // always the persisted level, never a hardcoded default
                setOnCompletionListener { next() }
            }
        } catch (e: Exception) {
            // A saved/resumed song's file can go stale between sessions -
            // moved, deleted, an SD card unplugged, a revoked SAF
            // permission. This used to be an UNCAUGHT exception here,
            // which crashed the whole app on the very next launch after
            // it happened (this is why it "worked the first time" - a
            // freshly-scanned song is always valid - "crashed the second
            // time" - resuming whatever was saved from the first session,
            // which had since gone stale). Never let a single bad file
            // take the whole app down: log it, clear any resume state
            // pointing at it so this doesn't repeat forever, and fall
            // back to a different song instead of crashing.
            log("[!] Couldn't load \"${song.title}\": ${e.message}")
            if (Prefs.lastSongUri(this) == song.uri.toString()) {
                Prefs.setLastPlayback(this, "", 0)
            }
            mediaPlayer = null
            if (fallbackDepth < 1 && list.size > 1) {
                currentIndex = if (currentIndex == 0) 1 else 0
                loadCurrentTrack(announce, fallbackDepth + 1)
            } else {
                nowPlayingCallback?.invoke("Couldn't load any songs", "", false)
            }
            return
        }
        // Pick up whatever trim points were saved for THIS song previously
        // (same idea as the Windows app storing trim_start/trim_end per
        // song) - a fresh MediaPlayer always starts at 0, so re-apply the
        // saved front-trim seek here rather than only when a new "trim"
        // voice command / GUI change happens. Matches Windows'
        // _effective_start(): whichever of the global SKIP setting and
        // this song's own TRIM front-cut is LARGER wins, so a global skip
        // never gets silently ignored just because a song also has its
        // own trim point, and vice versa.
        val uriKey = song.uri.toString()
        val savedFront = SongMetadataStore.trimFront(this, uriKey)
        val savedEnd = SongMetadataStore.trimEnd(this, uriKey)
        val startAt = maxOf(skipSeconds, savedFront)
        if (startAt > 0) mediaPlayer?.seekTo(startAt * 1000)
        trimEndSeconds = savedEnd
        trimCallback?.invoke(savedFront, savedEnd)
        ratingCallback?.invoke(SongMetadataStore.rating(this, uriKey))

        nowPlayingCallback?.invoke(song.title, song.artist, mediaPlayer?.isPlaying ?: false)
        updateNotification(song.title)
        if (announce) speak(song.title)

        if (Prefs.autoSkipIntro(this)) {
            maybeAutoSkipIntro(uriKey, currentIndex)
        }
    }

    /** Runs VocalIntroDetector for [uriKey] (or reuses a cached result from
     * earlier this session) and, if it finds a confident guess, seeks past
     * it - same as the manual "<wake> skip to vocals" command, just
     * automatic. Guarded by [expectedIndex]/[introAnalysisRequestId] so a
     * slow analysis for a song the user has since skipped away from can't
     * land a late seek on whatever's playing by the time it finishes. */
    private fun maybeAutoSkipIntro(uriKey: String, expectedIndex: Int) {
        val cached = introSecondsCache[uriKey]
        if (introSecondsCache.containsKey(uriKey)) {
            if (cached != null) applyIntroSkip(uriKey, expectedIndex, cached)
            return
        }
        val requestId = ++introAnalysisRequestId
        val uri = MusicLibrary.all().getOrNull(expectedIndex)?.uri ?: return
        VocalIntroDetector.detectIntroEndSeconds(this, uri) { seconds ->
            introSecondsCache[uriKey] = seconds
            if (requestId == introAnalysisRequestId && seconds != null) {
                applyIntroSkip(uriKey, expectedIndex, seconds)
            }
        }
    }

    private fun applyIntroSkip(uriKey: String, expectedIndex: Int, seconds: Int) {
        if (currentIndex != expectedIndex || currentUriKey() != uriKey) return  // moved on already
        val mp = mediaPlayer ?: return
        val savedFront = SongMetadataStore.trimFront(this, uriKey)
        val startAt = maxOf(skipSeconds, savedFront, seconds)
        try {
            if (mp.currentPosition < startAt * 1000) mp.seekTo(startAt * 1000)
        } catch (e: IllegalStateException) {
            // MediaPlayer torn down between the analysis finishing and this landing - nothing to seek.
        }
    }

    /** "<wake> skip to vocals" - one-off manual jump for whatever's
     * playing RIGHT NOW, regardless of the auto-skip setting. Reuses a
     * cached result if this song was already analyzed this session. */
    private fun skipToVocalsForCurrentSong() {
        val song = currentSong()
        val uriKey = song?.uri?.toString()
        if (song == null || uriKey == null) { speak("nothing playing"); return }
        val expectedIndex = currentIndex
        val cached = introSecondsCache[uriKey]
        if (introSecondsCache.containsKey(uriKey)) {
            if (cached != null) {
                applyIntroSkip(uriKey, expectedIndex, cached)
                speak("skipping the intro")
            } else {
                speak("couldn't find a clear intro to skip on this one")
            }
            return
        }
        speak("checking for an intro")
        VocalIntroDetector.detectIntroEndSeconds(this, song.uri) { seconds ->
            introSecondsCache[uriKey] = seconds
            if (seconds != null) {
                applyIntroSkip(uriKey, expectedIndex, seconds)
                speak("skipping the intro")
            } else {
                speak("couldn't find a clear intro to skip on this one")
            }
        }
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

    private fun currentUriKey(): String? = MusicLibrary.all().getOrNull(currentIndex)?.uri?.toString()

    /** The actual Song object at currentIndex - public so both the voice
     * delete command and MainActivity's now-playing trash icon identify
     * "the current song" the exact same way (by list identity, via
     * currentIndex), rather than MainActivity's old approach of matching
     * the now-playing title TEXT back against the song list, which could
     * pick the wrong song whenever two songs shared a title. */
    fun currentSong(): Song? = MusicLibrary.all().getOrNull(currentIndex)

    /** Whatever next() would load right now, without actually advancing
     * playback - used to show "Up Next" artwork/info in the GUI so the
     * second artwork panel always reflects what's really coming next,
     * including the wraparound back to the top of the list. Null only
     * when the library is empty. */
    fun peekNext(): Song? {
        val list = MusicLibrary.all()
        if (list.isEmpty() || currentIndex !in list.indices) return list.firstOrNull()
        return list[(currentIndex + 1) % list.size]
    }

    /** Public so both the voice "rate" command and the GUI's star row call
     * the exact same save path - can't disagree about what "rated" means. */
    fun setRating(value: Int) {
        val uriKey = currentUriKey() ?: return
        SongMetadataStore.setRating(this, uriKey, value)
        ratingCallback?.invoke(value)
    }

    fun currentRating(): Int {
        val uriKey = currentUriKey() ?: return 0
        return SongMetadataStore.rating(this, uriKey)
    }

    /** Public so both the voice "trim" command and the GUI's trim sliders
     * call the exact same save+apply path. */
    fun setTrim(frontSeconds: Int, endSeconds: Int) {
        val mp = mediaPlayer ?: return
        val uriKey = currentUriKey() ?: return
        SongMetadataStore.setTrim(this, uriKey, frontSeconds, endSeconds)
        // Matches Windows' set_trim_start(): seek to whichever of the
        // global SKIP setting and this new trim front-cut is LARGER, not
        // just the raw trim value - a global skip shouldn't get silently
        // undone by setting a smaller per-song trim point.
        val startAt = maxOf(skipSeconds, frontSeconds)
        if (startAt > 0) mp.seekTo(startAt * 1000)
        trimEndSeconds = endSeconds
        trimCallback?.invoke(frontSeconds, endSeconds)
    }

    fun currentTrim(): Pair<Int, Int> {
        val uriKey = currentUriKey() ?: return 0 to 0
        return SongMetadataStore.trimFront(this, uriKey) to SongMetadataStore.trimEnd(this, uriKey)
    }

    private fun status() = speak(currentTitle().ifBlank { "nothing loaded" })

    /** SKIP: sets the GLOBAL live start-position (seconds) applied to
     * EVERY song, current and future - matches Windows' Player.
     * set_skip_seconds() exactly, called by both the voice "skip" command
     * and the Settings skip control, same as there. If a song is
     * currently loaded, it's immediately re-seeked to the new effective
     * start (paused songs stay paused, just cued further in). */
    fun setSkipSeconds(seconds: Int) {
        skipSeconds = seconds.coerceIn(0, 60)
        Prefs.setSkipSeconds(this, skipSeconds)
        val mp = mediaPlayer ?: return
        val uriKey = currentUriKey() ?: return
        val wasPlaying = mp.isPlaying
        val startAt = maxOf(skipSeconds, SongMetadataStore.trimFront(this, uriKey))
        mp.seekTo(startAt * 1000)
        if (!wasPlaying) mp.pause()
    }

    fun currentSkipSeconds(): Int = skipSeconds

    /** "<wake> skip 30" - a one-time absolute jump to that position in
     * whatever's playing RIGHT NOW. Not persisted anywhere (not in
     * Prefs' global setting, not in SongMetadataStore for this song) -
     * next time this same song plays, it starts from its normal effective
     * start again as if this had never happened. Distinct from both the
     * global "skip all songs" setting and from "trim", which DOES persist
     * a per-song start point. */
    fun skipCurrentSongOnly(seconds: Int) {
        val mp = mediaPlayer ?: return
        mp.seekTo(seconds.coerceIn(0, 60) * 1000)
    }

    // -- voice delete / undo ---------------------------------------------------
    // Shares SongDeleter with MainActivity's manual delete paths (see that
    // class for the full per-storage-type breakdown) so voice and manual
    // delete can never disagree about what actually happened to a file.

    /** Drives a SongDeleter.Outcome to a terminal result, handing off to
     * consentResolver (MainActivity, if it's around) for any step that
     * needs a real one-tap OS consent dialog. */
    private fun runDeleteOutcome(outcome: SongDeleter.Outcome, onFinished: (SongDeleter.Outcome) -> Unit) {
        if (outcome is SongDeleter.Outcome.NeedsConsent) {
            val resolver = consentResolver
            if (resolver != null) {
                resolver(outcome) { next -> runDeleteOutcome(next, onFinished) }
            } else {
                onFinished(SongDeleter.Outcome.Failed(
                    "that needs the app open to confirm - open Car Voice Player and try again"))
            }
        } else {
            onFinished(outcome)
        }
    }

    /** "<wake> delete" - always acts on whatever's currently loaded, same
     * as the Windows app's player.current_song(). No confirmation dialog
     * (unlike the manual trash-icon/long-press paths) - a voice command
     * you spoke on purpose IS the confirmation, matching how every other
     * voice command here (rate, trim, skip) applies immediately too. */
    private fun voiceDeleteCurrentSong() {
        val song = currentSong()
        if (song == null) { speak("nothing playing to delete"); return }
        val label = if (song.artist.isNotBlank()) "\"${song.title}\" by ${song.artist}" else "\"${song.title}\""
        runDeleteOutcome(SongDeleter.delete(this, song)) { outcome ->
            when (outcome) {
                is SongDeleter.Outcome.Done -> {
                    MusicLibrary.removeSong(this, song.uri)
                    speak(if (outcome.undoable) "deleted, say undo to bring it back" else "deleted")
                    // Same detailed Activity-panel line the manual delete
                    // paths write (see MainActivity.performDelete) - a
                    // voice delete shouldn't leave a less complete trail
                    // than tapping the trash icon does.
                    log("Deleted $label" + if (outcome.undoable) " (undoable - say \"undo\")" else " (permanent)")
                    next()
                }
                is SongDeleter.Outcome.Failed -> {
                    speak(outcome.message)
                    log("Delete failed for $label: ${outcome.message}")
                }
                else -> {}
            }
        }
    }

    /** "<wake> undo" - restores the most recently voice- or manually-
     * deleted song, if that deletion landed somewhere undoable (this
     * app's own trash folder, or MediaStore's real trash on Android 11+ -
     * see SongDeleter). A permanent delete (older Android, or a song
     * added via "Add Music Folder") has nothing here to undo. */
    private fun voiceUndo() {
        if (!SongDeleter.hasUndo()) { speak("nothing to undo"); return }
        runDeleteOutcome(SongDeleter.undoLast(this)) { outcome ->
            when (outcome) {
                is SongDeleter.Outcome.Done -> {
                    MusicLibrary.restoreSong(this, outcome.song)
                    speak("restored ${outcome.song.title}")
                    log("Restored \"${outcome.song.title}\"")
                }
                is SongDeleter.Outcome.Failed -> {
                    speak(outcome.message)
                    log("Undo failed: ${outcome.message}")
                }
                else -> speak("nothing to undo")
            }
        }
    }

    private fun playSongByTitleKey(titleKey: String) {
        val idx = MusicLibrary.all().indexOfFirst { TitleNormalizer.normalize(it.title) == titleKey }
        if (idx == -1) { speak("couldn't find that song"); return }
        playSongAt(idx)
        speak("playing ${MusicLibrary.all()[idx].title}")
    }

    // -- org.vosk.android.RecognitionListener callbacks --

    // Debounce for the partial-match duck below: how many consecutive
    // partial-result callbacks in a row have matched a wake phrase.
    // Ducking on a single partial match (the old behavior here) meant one
    // brief mis-hearing - a stray "john"/"sam"-ish sound in song lyrics or
    // background noise - was enough to dim the volume with no real
    // command following, which read as "volume just dims for no reason".
    // Matches the Windows app's identical fix (PARTIAL_WAKE_DEBOUNCE=2 in
    // voice_control.py).
    private var partialWakeStreak = 0
    private val PARTIAL_WAKE_DEBOUNCE = 2

    override fun onPartialResult(hypothesis: String?) {
        val partial = try { JSONObject(hypothesis ?: "").optString("partial", "") } catch (e: Exception) { "" }
        val wake = Prefs.wakeWord(this); val aliases = Prefs.wakeAliases(this)
        val matches = partial.isNotBlank() && CommandParser.wakePhrases(wake, aliases).any { partial.startsWith(it) }
        if (matches) {
            partialWakeStreak++
            if (partialWakeStreak >= PARTIAL_WAKE_DEBOUNCE) requestDuckFocus()
        } else {
            partialWakeStreak = 0
        }
    }

    override fun onResult(hypothesis: String?) = handleHypothesis(hypothesis)
    override fun onFinalResult(hypothesis: String?) = handleHypothesis(hypothesis)
    override fun onError(exception: Exception?) { log("[!] recognizer error: ${exception?.message}") }
    override fun onTimeout() { abandonDuckFocus() }

    private fun handleHypothesis(hypothesis: String?) {
        partialWakeStreak = 0
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
                "delete" -> voiceDeleteCurrentSong()
                "undo" -> voiceUndo()
            }
            is CommandParser.Command.Rate -> {
                setRating(cmd.value)
                speak("rated ${cmd.value}, saved")
            }
            is CommandParser.Command.Skip -> {
                // CURRENT SONG ONLY - a one-time jump, not persisted, not
                // applied to other songs or future plays of this one.
                // (Corrected per explicit instruction - this used to set
                // the global skip here, which was wrong: that's what
                // "skip all songs N" and the Settings control are for.)
                skipCurrentSongOnly(cmd.seconds)
                speak("skipped to ${cmd.seconds} seconds")
            }
            is CommandParser.Command.SkipAllSongs -> {
                setSkipSeconds(cmd.seconds)
                speak("skip set to ${cmd.seconds} seconds for all songs")
            }
            is CommandParser.Command.Trim -> {
                setTrim(cmd.frontSeconds, cmd.endSeconds)
                speak("trim set, ${cmd.frontSeconds} seconds from the start, ${cmd.endSeconds} from the end, saved")
            }
            is CommandParser.Command.PlaySong -> playSongByTitleKey(cmd.titleKey)
            is CommandParser.Command.SkipToVocals -> skipToVocalsForCurrentSong()
            null -> { /* not a recognized command - ignore */ }
        }
    }
}
