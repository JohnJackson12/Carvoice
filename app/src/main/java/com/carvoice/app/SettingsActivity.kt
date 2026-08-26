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
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Prefs.addFolderUri(this, uri.toString())
            renderFolderList()
            rescanInBackground()
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
            folderPickerLauncher.launch(null)
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

        renderFolderList()
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
        return try {
            val uri = Uri.parse(uriString)
            DocumentFile.fromTreeUri(this, uri)?.name ?: DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            uriString
        }
    }
}
