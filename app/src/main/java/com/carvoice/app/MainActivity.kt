package com.carvoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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

        adapter = SongAdapter(emptyList()) { index -> playFilteredIndex(index) }
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
