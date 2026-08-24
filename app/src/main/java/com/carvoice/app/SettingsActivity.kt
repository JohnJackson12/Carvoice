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

        renderFolderList()
    }

    private fun rescanInBackground() {
        Toast.makeText(this, "Rescanning library...", Toast.LENGTH_SHORT).show()
        Thread {
            MusicLibrary.rescan(this)
            runOnUiThread { Toast.makeText(this, "Found ${MusicLibrary.all().size} song(s)", Toast.LENGTH_SHORT).show() }
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
