package com.carvoice.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile

class SettingsActivity : AppCompatActivity() {

    private lateinit var folderListContainer: LinearLayout

    private val folderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // WRITE permission too, not just READ - needed so a song from
            // this folder can actually be deleted later (the long-press
            // "Delete" menu / the now-playing panel's delete button both
            // need this; without it, deleting anything from a
            // manually-added folder would fail).
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.addFolderUri(this, uri.toString())
            renderFolderList()
            rescanInBackground()
        }
    }

    // The in-app browser (see FolderBrowserActivity for why this exists -
    // short version: some devices have no app installed that can handle
    // the OS's own folder-picker intent at all). Comes back with a plain
    // absolute path rather than a content:// tree URI.
    private val folderBrowserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FolderBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                Prefs.addFolderUri(this, path)
                renderFolderList()
                rescanInBackground()
            }
        }
    }

    // Just for coming BACK from the system's "All files access" settings
    // screen - if the person actually flipped the switch while there, go
    // straight into the folder browser rather than making them tap
    // "Add Music Folder" a second time.
    private val allFilesAccessLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (FolderBrowserActivity.hasAccess()) {
            folderBrowserLauncher.launch(Intent(this, FolderBrowserActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val wakeWordInput = findViewById<EditText>(R.id.wakeWordInput)
        val wakeAliasesInput = findViewById<EditText>(R.id.wakeAliasesInput)
        folderListContainer = findViewById(R.id.folderListContainer)

        wakeWordInput.setText(Prefs.wakeWord(this))
        wakeAliasesInput.setText(Prefs.wakeAliases(this).joinToString(", "))

        findViewById<Button>(R.id.applyWakeButton).setOnClickListener {
            val word = wakeWordInput.text.toString().trim().lowercase()
            if (word.isBlank()) {
                Toast.makeText(this, "Wake word can't be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val aliases = wakeAliasesInput.text.toString().split(",")
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            Prefs.setWakeWord(this, word)
            Prefs.setWakeAliases(this, aliases)
            VoiceService.instance?.refreshRecognizer()
            Toast.makeText(this, "Wake words updated", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.addFolderButton).setOnClickListener {
            addFolderClicked()
        }

        val wholeDeviceCheckbox = findViewById<android.widget.CheckBox>(R.id.wholeDeviceCheckbox)
        wholeDeviceCheckbox.isChecked = Prefs.useWholeDeviceLibrary(this)
        wholeDeviceCheckbox.setOnCheckedChangeListener { _, checked ->
            Prefs.setUseWholeDeviceLibrary(this, checked)
            rescanInBackground()
        }

        findViewById<Button>(R.id.rescanButton).setOnClickListener {
            rescanInBackground()
        }

        val themeGroup = findViewById<RadioGroup>(R.id.themeRadioGroup)
        val isDark = Prefs.nightMode(this) == AppCompatDelegate.MODE_NIGHT_YES
        themeGroup.check(if (isDark) R.id.themeDark else R.id.themeLight)
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.themeDark) AppCompatDelegate.MODE_NIGHT_YES
                       else AppCompatDelegate.MODE_NIGHT_NO
            Prefs.setNightMode(this, mode)
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        // SKIP: global, live setting - matches the Windows app's Settings
        // skip control exactly (a discrete 0/5/10/.../60 choice there;
        // a draggable SeekBar snapped to the same 5-second steps here).
        val skipSeekBar = findViewById<android.widget.SeekBar>(R.id.skipSecondsSeekBar)
        val skipLabel = findViewById<TextView>(R.id.skipSecondsLabel)
        val initialSkip = Prefs.skipSeconds(this)
        skipSeekBar.progress = initialSkip
        skipLabel.text = "${initialSkip}s"
        skipSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val snapped = (progress / 5) * 5
                skipLabel.text = "${snapped}s"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val snapped = (sb?.progress ?: 0) / 5 * 5
                sb?.progress = snapped
                VoiceService.instance?.setSkipSeconds(snapped) ?: Prefs.setSkipSeconds(this@SettingsActivity, snapped)
            }
        })

        val autoStartCheckbox = findViewById<android.widget.CheckBox>(R.id.autoStartCheckbox)
        autoStartCheckbox.isChecked = Prefs.autoStartOnBoot(this)
        autoStartCheckbox.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoStartOnBoot(this, checked)
        }

        val crashLogText = findViewById<TextView>(R.id.crashLogText)
        crashLogText.text = CrashLog.lastEntry(this) ?: "None recorded."
        findViewById<Button>(R.id.clearCrashLogButton).setOnClickListener {
            CrashLog.clear(this)
            crashLogText.text = "None recorded."
        }

        renderFolderList()
    }

    /** Tries, in order, everything that could possibly let someone pick a
     * folder on THIS device, since which of these actually works varies a
     * lot across the odd Android boxes this app ends up running on:
     *   1. The normal OS picker - needs no special permission, so try it
     *      first for the (probably-common) case where it's just there.
     *   2. This app's own in-app browser (FolderBrowserActivity) - works
     *      on any device, but needs "All files access" granted first, so
     *      send the person to that system settings screen if it isn't yet.
     *   3. If EVEN the system's own "All files access" settings screen
     *      doesn't exist on this particular ROM - vanishingly rare, but
     *      seen on some stripped-down car units - fall back to just
     *      saying so plainly, since there's genuinely nothing left to try.
     */
    private fun addFolderClicked() {
        try {
            folderPickerLauncher.launch(null)
            return
        } catch (e: android.content.ActivityNotFoundException) {
            // No OS picker app on this device - fall through to the
            // in-app browser below instead.
        }

        if (FolderBrowserActivity.hasAccess()) {
            folderBrowserLauncher.launch(Intent(this, FolderBrowserActivity::class.java))
            return
        }

        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            allFilesAccessLauncher.launch(intent)
            Toast.makeText(
                this,
                "This device has no folder-picker app, so Car Voice Player needs " +
                    "to browse storage itself instead. Turn on \"Allow access to manage " +
                    "all files\" on the screen that just opened, then come back here.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(
                this,
                "This device has no way to browse folders at all, so a specific " +
                    "folder can't be added. Use \"Whole device library\" below instead - " +
                    "it finds all music without browsing folders.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun rescanInBackground() {
        val statusText = findViewById<TextView>(R.id.scanStatusText)
        val rescanButton = findViewById<Button>(R.id.rescanButton)
        rescanButton.isEnabled = false
        rescanButton.text = "Scanning..."
        statusText.text = "Starting scan..."
        Thread {
            MusicLibrary.rescan(this) { seen, added ->
                // Called from this background thread deliberately (matches
                // Windows' progress_cb) - hop to main here since this
                // touches a TextView, unlike rescan()'s own listener
                // notifications which already hop for you.
                runOnUiThread { statusText.text = "Scanning... $seen files checked, $added found so far" }
            }
            runOnUiThread {
                rescanButton.isEnabled = true
                rescanButton.text = "Rescan Library"
                statusText.text = "Found ${MusicLibrary.all().size} song(s)."
            }
        }.start()
    }

    private fun renderFolderList() {
        folderListContainer.removeAllViews()
        val uris = Prefs.folderUris(this)
        if (uris.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No extra folders added - using the device's whole music library."
            empty.textSize = 12f
            folderListContainer.addView(empty)
            return
        }
        for (uriString in uris) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val label = TextView(this)
            label.text = friendlyFolderName(uriString)
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(label)

            val removeButton = Button(this)
            removeButton.text = "Remove"
            removeButton.setOnClickListener {
                Prefs.removeFolderUri(this, uriString)
                renderFolderList()
                rescanInBackground()
            }
            row.addView(removeButton)

            folderListContainer.addView(row)
        }
    }

    private fun friendlyFolderName(uriString: String): String {
        if (!uriString.startsWith("content://")) {
            // A plain absolute path from FolderBrowserActivity - just the
            // last path segment is the friendliest label.
            return java.io.File(uriString).name.ifBlank { uriString }
        }
        return try {
            val uri = Uri.parse(uriString)
            DocumentFile.fromTreeUri(this, uri)?.name ?: DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            uriString
        }
    }
}
