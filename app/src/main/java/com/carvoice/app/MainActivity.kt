package com.carvoice.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var trimStartSeekBar: SeekBar
    private lateinit var trimEndSeekBar: SeekBar
    private lateinit var trimLabel: TextView
    private lateinit var ratingRow: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var searchBox: EditText
    private lateinit var recyclerView: RecyclerView
    private var logText: TextView? = null  // only present in the landscape layout

    private lateinit var adapter: SongAdapter
    private var allSongs: List<Song> = emptyList()
    private var filteredSongs: List<Song> = emptyList()
    private var libraryListener: (() -> Unit)? = null
    private var userIsDraggingSeekBar = false

    // Whatever SongDeleter.Outcome.NeedsConsent.onResult should run once
    // the system consent dialog below returns - see resolveOutcome().
    private var pendingConsentResult: ((Boolean) -> Unit)? = null

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val callback = pendingConsentResult
        pendingConsentResult = null
        callback?.invoke(result.resultCode == RESULT_OK)
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            perms.add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For automatic Bluetooth mic routing in Settings - see
                // VoiceService.setUpAutomaticMicRouting(). Not strictly
                // required by AudioManager's own SCO methods on stock
                // Android, but requested defensively for OEM builds that
                // gate it, and to avoid a SecurityException surprising
                // that code path on those devices.
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle)
        nowPlayingArtist = findViewById(R.id.nowPlayingArtist)
        seekBar = findViewById(R.id.seekBar)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        trimStartSeekBar = findViewById(R.id.trimStartSeekBar)
        trimEndSeekBar = findViewById(R.id.trimEndSeekBar)
        trimLabel = findViewById(R.id.trimLabel)
        ratingRow = findViewById(R.id.ratingRow)
        buildRatingStars()
        playPauseButton = findViewById(R.id.playPauseButton)
        searchBox = findViewById(R.id.searchBox)
        recyclerView = findViewById(R.id.songRecyclerView)
        logText = findViewById(R.id.logText)  // null on portrait, that's fine

        adapter = SongAdapter(
            emptyList(),
            onClick = { index -> playFilteredIndex(index) },
            onLongClick = { index -> showSongContextMenu(index) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        volumeSeekBar.max = 100
        volumeSeekBar.progress = (Prefs.playbackVolume(this) * 100).toInt()
        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) VoiceService.instance?.setVolume(progress / 100f)
                    ?: Prefs.setPlaybackVolume(this@MainActivity, progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { userIsDraggingSeekBar = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userIsDraggingSeekBar = false
                VoiceService.instance?.seekTo(sb?.progress ?: 0)
            }
        })

        // Trim start/end - saved (and applied - it seeks past the new
        // start point right away) the moment you let go of the slider,
        // same "drag to set, it's already saved" feel as the Windows
        // app's trim handles. Both sliders share one apply function since
        // the service call and the label always need both values together.
        val applyTrim = {
            VoiceService.instance?.setTrim(trimStartSeekBar.progress, trimEndSeekBar.progress)
        }
        trimStartSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateTrimLabel(progress, trimEndSeekBar.progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyTrim() }
        })
        trimEndSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateTrimLabel(trimStartSeekBar.progress, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyTrim() }
        })

        playPauseButton.setOnClickListener {
            val svc = VoiceService.instance ?: return@setOnClickListener
            if (svc.isPlaying()) svc.pause() else svc.play()
        }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener { VoiceService.instance?.next() }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener { VoiceService.instance?.previous() }
        findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { deleteCurrentSong() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { applyFilter(s?.toString() ?: "") }
        })

        libraryListener = { runOnUiThread { onLibraryChanged() } }
        MusicLibrary.addListener(libraryListener!!)

        VoiceService.logCallback = { msg -> runOnUiThread { logText?.append("$msg\n") } }
        VoiceService.nowPlayingCallback = { title, artist, playing ->
            runOnUiThread {
                nowPlayingTitle.text = title
                nowPlayingArtist.text = artist
                playPauseButton.setImageResource(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
                // Matches the Windows app's title_lbl turning
                // NOWPLAYING_RED while actually playing (not just
                // loaded/paused) - a quick glance tells you playing vs.
                // paused without reading the play/pause icon.
                nowPlayingTitle.setTextColor(
                    if (playing) ContextCompat.getColor(this, R.color.now_playing_text)
                    else defaultTextColor()
                )
                highlightPlayingRow()
            }
        }
        VoiceService.progressCallback = { position, duration ->
            runOnUiThread {
                if (!userIsDraggingSeekBar) {
                    seekBar.max = duration.coerceAtLeast(1)
                    seekBar.progress = position
                }
            }
        }
        VoiceService.ratingCallback = { rating -> runOnUiThread { setRatingStars(rating) } }
        VoiceService.trimCallback = { front, end ->
            runOnUiThread {
                trimStartSeekBar.progress = front
                trimEndSeekBar.progress = end
                updateTrimLabel(front, end)
            }
        }
        // Lets a voice "delete"/"undo" command show the same system
        // consent dialog the manual delete paths below use - see
        // SongDeleter's class doc and VoiceService.consentResolver.
        VoiceService.consentResolver = { needsConsent, onFinished -> resolveOutcome(needsConsent, onFinished) }
    }

    private fun buildRatingStars() {
        ratingRow.removeAllViews()
        for (i in 1..5) {
            val star = TextView(this)
            star.text = "\u2606"  // filled in setRatingStars() based on the saved rating
            star.textSize = 26f
            star.setPadding(6, 0, 6, 0)
            star.setOnClickListener {
                VoiceService.instance?.setRating(i)
            }
            ratingRow.addView(star)
        }
    }

    private fun setRatingStars(rating: Int) {
        for (i in 0 until ratingRow.childCount) {
            (ratingRow.getChildAt(i) as? TextView)?.text = if (i < rating) "\u2605" else "\u2606"
        }
    }

    private fun updateTrimLabel(front: Int, end: Int) {
        trimLabel.text = "Trim: start ${front}s, end ${end}s"
    }

    override fun onResume() {
        super.onResume()
        onLibraryChanged()  // picks up anything Settings changed while we were away
        VoiceService.instance?.let {
            setRatingStars(it.currentRating())
            val (front, end) = it.currentTrim()
            trimStartSeekBar.progress = front
            trimEndSeekBar.progress = end
            updateTrimLabel(front, end)
        }
    }

    override fun onDestroy() {
        libraryListener?.let { MusicLibrary.removeListener(it) }
        VoiceService.logCallback = null
        VoiceService.nowPlayingCallback = null
        VoiceService.progressCallback = null
        VoiceService.ratingCallback = null
        VoiceService.trimCallback = null
        VoiceService.consentResolver = null
        super.onDestroy()
    }

    private fun onLibraryChanged() {
        allSongs = MusicLibrary.all()
        applyFilter(searchBox.text?.toString() ?: "")
    }

    private fun applyFilter(query: String) {
        filteredSongs = if (query.isBlank()) allSongs
        else allSongs.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
        adapter.updateSongs(filteredSongs, -1)
        highlightPlayingRow()
    }

    private fun highlightPlayingRow() {
        val playingTitle = nowPlayingTitle.text.toString()
        val idx = filteredSongs.indexOfFirst { it.title == playingTitle }
        adapter.playingIndex = idx
    }

    private fun defaultTextColor(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        return if (typedValue.resourceId != 0) ContextCompat.getColor(this, typedValue.resourceId) else typedValue.data
    }

    private fun playFilteredIndex(filteredIndex: Int) {
        val song = filteredSongs.getOrNull(filteredIndex) ?: return
        val realIndex = allSongs.indexOf(song)
        if (realIndex >= 0) VoiceService.instance?.playSongAt(realIndex)
    }

    // -- long-press context menu / delete - the Windows app's right-click
    //    menu equivalent. Delete specifically needs real Android platform
    //    APIs, not a quick file-system delete: the OS requires explicit
    //    one-tap user consent to remove a file this app didn't create
    //    itself (scoped storage, API 29+) - that consent step is an
    //    unavoidable OS requirement, not something this app is choosing
    //    to add. The actual delete/undo logic lives in SongDeleter, shared
    //    with VoiceService's "<wake> delete"/"<wake> undo" commands so the
    //    two paths can never disagree about what happened. ---------------

    private fun showSongContextMenu(filteredIndex: Int) {
        val song = filteredSongs.getOrNull(filteredIndex) ?: return
        val options = arrayOf("Play", "Song Info", "Delete")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playFilteredIndex(filteredIndex)
                    1 -> showSongInfo(song)
                    2 -> confirmAndDeleteSong(song)
                }
            }
            .show()
    }

    private fun showSongInfo(song: Song) {
        val uriKey = song.uri.toString()
        val rating = SongMetadataStore.rating(this, uriKey)
        val front = SongMetadataStore.trimFront(this, uriKey)
        val end = SongMetadataStore.trimEnd(this, uriKey)
        val message = buildString {
            append("Title: ${song.title}\n")
            if (song.artist.isNotBlank()) append("Artist: ${song.artist}\n")
            append("Rating: ${if (rating > 0) "$rating/5" else "not rated"}\n")
            append("Trim: ${if (front > 0 || end > 0) "${front}s from start, ${end}s from end" else "none"}\n")
            append("File: ${song.uri}")
        }
        AlertDialog.Builder(this)
            .setTitle("Song Info")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmAndDeleteSong(song: Song) {
        val wake = Prefs.wakeWord(this)
        val message = if (SongDeleter.wouldBeUndoable(song)) {
            "\"${song.title}\" will be removed. If you didn't mean it, say " +
                "\"$wake undo\" (or use Settings while it's still fresh)."
        } else {
            "\"${song.title}\" will be permanently deleted from your device. This can't be undone."
        }
        AlertDialog.Builder(this)
            .setTitle("Delete this song?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> performDelete(song) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Called by the trash-icon button in the now-playing panel - deletes
     * whatever's currently loaded/playing, same confirmation flow as the
     * long-press menu's Delete option. Identifies "the current song" via
     * VoiceService.currentSong() (the actual Song at currentIndex) rather
     * than matching the now-playing title TEXT against allSongs - that
     * text-matching approach could silently pick the wrong song whenever
     * two songs shared a title. */
    private fun deleteCurrentSong() {
        val song = VoiceService.instance?.currentSong()
        if (song == null) {
            Toast.makeText(this, "Nothing loaded to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        confirmAndDeleteSong(song)
    }

    /** Runs [outcome] to completion, showing the system consent dialog
     * (via deleteRequestLauncher) for any NeedsConsent step and feeding
     * its result straight back into SongDeleter - see SongDeleter's class
     * doc for why that retry step matters on API 29 specifically, and why
     * it's a no-op-but-still-correct step on API 30+. */
    private fun resolveOutcome(outcome: SongDeleter.Outcome, onFinished: (SongDeleter.Outcome) -> Unit) {
        if (outcome is SongDeleter.Outcome.NeedsConsent) {
            pendingConsentResult = { approved -> resolveOutcome(outcome.onResult(approved), onFinished) }
            deleteRequestLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
        } else {
            onFinished(outcome)
        }
    }

    private fun performDelete(song: Song) {
        resolveOutcome(SongDeleter.delete(this, song)) { outcome ->
            when (outcome) {
                is SongDeleter.Outcome.Done -> {
                    val wasCurrentSong = VoiceService.instance?.currentSong()?.uri == song.uri
                    MusicLibrary.removeSong(this, song.uri)
                    Toast.makeText(
                        this,
                        if (outcome.undoable) "Deleted. Say \"${Prefs.wakeWord(this)} undo\" to bring it back."
                        else "Deleted.",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (wasCurrentSong) VoiceService.instance?.next()
                }
                is SongDeleter.Outcome.Failed ->
                    Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                else -> {}
            }
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onStart() {
        super.onStart()
        if (hasAllPermissions()) startVoiceService()
        else ActivityCompat.requestPermissions(this, requiredPermissions, 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAllPermissions()) startVoiceService()
        else statusText.text = "Mic + storage + notification permissions are needed to run."
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        val wake = Prefs.wakeWord(this)
        val aliases = Prefs.wakeAliases(this)
        val wakeList = CommandParser.wakePhrases(wake, aliases).joinToString(" / ")
        statusText.text = "Listening for: $wakeList"
        // Give the service a moment to come up before syncing volume/library state.
        Handler(Looper.getMainLooper()).postDelayed({
            VoiceService.instance?.setVolume(Prefs.playbackVolume(this))
            onLibraryChanged()
        }, 800)
    }
}
