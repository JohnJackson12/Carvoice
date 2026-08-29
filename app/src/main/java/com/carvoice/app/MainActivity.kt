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
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var elapsedTime: TextView
    private lateinit var remainingTime: TextView
    private var logText: TextView? = null  // only present in the landscape layout
    private var logScrollView: ScrollView? = null  // ditto
    private var nowPlayingArt: ImageView? = null  // landscape-only artwork panels
    private var upNextArt: ImageView? = null
    private var nowPlayingPin: View? = null  // landscape-only "still playing, tap to jump" bar
    private var nowPlayingPinText: TextView? = null
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
        elapsedTime = findViewById(R.id.elapsedTime)
        remainingTime = findViewById(R.id.remainingTime)
        logText = findViewById(R.id.logText)  // null on portrait, that's fine
        logScrollView = findViewById(R.id.logScrollView)
        nowPlayingArt = findViewById(R.id.nowPlayingArt)
        upNextArt = findViewById(R.id.upNextArt)
        nowPlayingPin = findViewById(R.id.nowPlayingPin)
        nowPlayingPinText = findViewById(R.id.nowPlayingPinText)

        adapter = SongAdapter(
            emptyList(),
            onClick = { index -> playFilteredIndex(index) },
            onLongClick = { index -> showSongContextMenu(index) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Keeps the now-playing pin's shown/hidden state in sync with
        // actual scroll position - see updateNowPlayingPinVisibility().
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateNowPlayingPinVisibility()
        })
        nowPlayingPin?.setOnClickListener { scrollToPlayingRow(smooth = true) }

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
                refreshArtworkPanels()
            }
        }
        VoiceService.progressCallback = { position, duration ->
            runOnUiThread {
                if (!userIsDraggingSeekBar) {
                    seekBar.max = duration.coerceAtLeast(1)
                    seekBar.progress = position
                }
                // Previously the seek bar gave no sense at all of how far
                // into the song you were - elapsed counts up, remaining
                // counts down to zero, same convention as most players.
                elapsedTime.text = TimeFormat.format(position)
                remainingTime.text = "-" + TimeFormat.format((duration - position).coerceAtLeast(0))
            }
        }
        VoiceService.ratingCallback = { rating ->
            runOnUiThread {
                setRatingStars(rating)
                // The list's own Rating column would otherwise show a
                // stale value until the next full refresh.
                adapter.refreshRating(VoiceService.instance?.currentSong()?.uri)
            }
        }
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
        refreshArtworkPanels()
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
        refreshArtworkPanels()
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
        updateNowPlayingPinVisibility()
    }

    /** Loads the "Now Playing" / "Up Next" artwork panels above the song
     * list (landscape only - both views are null on portrait). Safe to
     * call often; AlbumArt caches decoded bitmaps so repeat calls for the
     * same song are cheap. */
    private fun refreshArtworkPanels() {
        val svc = VoiceService.instance
        val current = svc?.currentSong()
        val upNext = svc?.peekNext()
        nowPlayingArt?.let { iv ->
            AlbumArt.loadAsync(this, current?.uri) { bitmap ->
                if (bitmap != null) iv.setImageBitmap(bitmap)
                else iv.setImageResource(android.R.drawable.ic_media_play)
            }
        }
        upNextArt?.let { iv ->
            AlbumArt.loadAsync(this, upNext?.uri) { bitmap ->
                if (bitmap != null) iv.setImageBitmap(bitmap)
                else iv.setImageResource(android.R.drawable.ic_media_next)
            }
        }
    }

    /** Shows/hides the "still playing X, tap to jump to it" pinned bar
     * over the middle of the song list. The real now-playing row can
     * scroll out of view the moment you browse elsewhere in the list -
     * this pin is what keeps the current track visible/reachable no
     * matter where you've scrolled to, without permanently reserving
     * space for it when it's already on-screen. Landscape-only (the pin
     * view is null on portrait). */
    private fun updateNowPlayingPinVisibility() {
        val pin = nowPlayingPin ?: return
        val playingIndex = adapter.playingIndex
        if (playingIndex < 0) {
            pin.visibility = View.GONE
            return
        }
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        val firstVisible = layoutManager?.findFirstCompletelyVisibleItemPosition() ?: -1
        val lastVisible = layoutManager?.findLastCompletelyVisibleItemPosition() ?: -1
        val isFullyVisible = playingIndex in firstVisible..lastVisible
        if (isFullyVisible) {
            pin.visibility = View.GONE
        } else {
            nowPlayingPinText?.text = filteredSongs.getOrNull(playingIndex)?.title ?: nowPlayingTitle.text
            pin.visibility = View.VISIBLE
        }
    }

    /** Scrolls the song list so the currently-playing row lands roughly in
     * the middle of the visible area, rather than merely "somewhere on
     * screen" - matches what the now-playing pin promises ("tap to jump
     * to it") and what item 5 of the redesign asked for: the current
     * track should land back in the middle of view, not at whatever edge
     * a plain scrollToPosition() would leave it at. */
    private fun scrollToPlayingRow(smooth: Boolean) {
        val playingIndex = adapter.playingIndex
        if (playingIndex < 0) return
        recyclerView.post {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@post
            val offset = (recyclerView.height / 2) - (recyclerView.height / (layoutManager.childCount.coerceAtLeast(1) * 2))
            if (smooth) {
                val smoothScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this) {
                    override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                        return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
                    }
                }
                smoothScroller.targetPosition = playingIndex
                layoutManager.startSmoothScroll(smoothScroller)
            } else {
                layoutManager.scrollToPositionWithOffset(playingIndex, offset.coerceAtLeast(0))
            }
        }
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

    /** Appends a timestamped line to the Activity panel (landscape only -
     * a no-op on portrait, where logText is null). Kept separate from the
     * voice-command log lines (VoiceService.logCallback) so the wording
     * here can be specific to what MainActivity itself just did, but both
     * end up in the exact same panel/scrollback. */
    private fun logActivity(message: String) {
        logText?.append("[${logTimeFormat.format(Date())}] $message\n")
        logScrollView?.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun performDelete(song: Song) {
        val label = if (song.artist.isNotBlank()) "\"${song.title}\" by ${song.artist}" else "\"${song.title}\""
        resolveOutcome(SongDeleter.delete(this, song)) { outcome ->
            when (outcome) {
                is SongDeleter.Outcome.Done -> {
                    val wasCurrentSong = VoiceService.instance?.currentSong()?.uri == song.uri
                    MusicLibrary.removeSong(this, song.uri)
                    val toastMessage = if (outcome.undoable)
                        "Deleted. Say \"${Prefs.wakeWord(this)} undo\" to bring it back."
                    else "Deleted."
                    Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
                    // Previously deleting gave essentially no lasting
                    // feedback about WHAT was removed - a Toast alone
                    // disappears in a couple seconds and there was no
                    // record of it afterward. This puts a permanent,
                    // detailed line in the Activity panel: what was
                    // deleted, whether it can still be undone, and
                    // (when it was the playing track) what took over.
                    logActivity(
                        "Deleted $label" +
                            if (outcome.undoable) " (undoable - say \"${Prefs.wakeWord(this)} undo\")" else " (permanent)"
                    )
                    if (wasCurrentSong) {
                        VoiceService.instance?.next()
                        Handler(Looper.getMainLooper()).postDelayed({
                            val next = VoiceService.instance?.currentSong()
                            if (next != null) logActivity("Now playing \"${next.title}\" (was playing the deleted song)")
                        }, 300)
                    }
                }
                is SongDeleter.Outcome.Failed -> {
                    Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                    logActivity("Delete failed for $label: ${outcome.message}")
                }
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
