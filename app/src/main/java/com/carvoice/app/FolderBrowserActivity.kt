package com.carvoice.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/** A folder picker drawn entirely by this app, reading storage directly via
 * plain java.io.File calls instead of asking Android to hand off to some
 * other app via ACTION_OPEN_DOCUMENT_TREE.
 *
 * WHY THIS EXISTS: that hand-off approach (Storage Access Framework) only
 * works if the device has SOME app installed that can handle it - normally
 * Google's DocumentsUI, which ships on stock Android but is genuinely
 * absent on some bare-bones/car-head-unit boxes. On those devices,
 * ACTION_OPEN_DOCUMENT_TREE has nothing to launch and throws
 * ActivityNotFoundException - see SettingsActivity's addFolderButton
 * handler, which still tries the OS picker FIRST for devices that do have
 * one (it needs no extra permission there), and only falls back to this
 * activity when it doesn't.
 *
 * This works on any device because it never depends on another app -
 * requires MANAGE_EXTERNAL_STORAGE ("All files access"), which lets plain
 * File.listFiles()/File.exists() etc. see all of shared storage directly,
 * the same way a desktop file manager would.
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var pathText: TextView
    private lateinit var listView: ListView
    private lateinit var selectButton: Button

    // null = showing the top-level list of storage volumes (internal
    // storage, SD card, etc). Non-null = browsing inside one of them.
    private var currentDir: File? = null
    private var rowTargets: List<File> = emptyList()
    private var showUpRow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_browser)

        pathText = findViewById(R.id.currentPathText)
        listView = findViewById(R.id.folderListView)
        selectButton = findViewById(R.id.selectFolderButton)

        listView.setOnItemClickListener { _, _, position, _ ->
            if (showUpRow && position == 0) {
                navigateUp()
            } else {
                val targetIndex = if (showUpRow) position - 1 else position
                rowTargets.getOrNull(targetIndex)?.let { navigateInto(it) }
            }
        }

        selectButton.setOnClickListener {
            val dir = currentDir
            if (dir == null) {
                Toast.makeText(this, "Open a folder first, then tap this.", Toast.LENGTH_SHORT).show()
            } else {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_PATH, dir.absolutePath))
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDir == null) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    navigateUp()
                }
            }
        })

        showRoots()
    }

    private fun showRoots() {
        currentDir = null
        val roots = storageRoots(this)
        if (roots.isEmpty()) {
            Toast.makeText(this, "No accessible storage found on this device.", Toast.LENGTH_LONG).show()
        }
        rowTargets = roots
        showUpRow = false
        pathText.text = "Choose a storage location:"
        val labels = roots.map { volumeLabel(it) }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun navigateInto(dir: File) {
        val children = try {
            dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            null
        }
        if (children == null) {
            Toast.makeText(this, "Can't read \"${dir.name}\" - permission may not cover it.", Toast.LENGTH_SHORT).show()
            return
        }
        currentDir = dir
        rowTargets = children
        showUpRow = true
        pathText.text = dir.absolutePath
        val labels = listOf("\u2b06 .. (up one level)") + children.map { it.name }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun navigateUp() {
        val dir = currentDir ?: return
        val parent = dir.parentFile
        val roots = storageRoots(this)
        // Stop ascending once we'd go above whichever storage root this
        // folder lives under - going further up either fails (permission)
        // or leaves the music-relevant part of the filesystem entirely.
        val atARoot = roots.any { it.absolutePath == dir.absolutePath }
        if (parent == null || atARoot) {
            showRoots()
        } else {
            navigateInto(parent)
        }
    }

    companion object {
        const val EXTRA_SELECTED_PATH = "selected_path"

        /** All Files Access (MANAGE_EXTERNAL_STORAGE) is what actually
         * grants read access here - this check just confirms it's been
         * granted before this activity is even launched (see
         * SettingsActivity). */
        fun hasAccess(): Boolean =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        /** Every mounted shared-storage volume this app can read: primary
         * internal storage plus any SD card / USB drive. Deriving these
         * from getExternalFilesDirs() (each is this app's own private
         * directory on that volume) and trimming off the
         * "/Android/data/<package>/files" suffix is a long-standing,
         * broadly-compatible way to find each volume's real root -
         * StorageManager.getStorageVolumes() would be the more "official"
         * API but needs API 24+ and still doesn't hand back a usable root
         * File on every OEM build, so this is the safer bet for a device
         * this unusual. */
        fun storageRoots(context: Context): List<File> {
            val roots = mutableListOf<File>()
            Environment.getExternalStorageDirectory()?.let { if (it.exists()) roots.add(it) }
            try {
                for (dir in ContextCompat.getExternalFilesDirs(context, null)) {
                    if (dir == null) continue
                    val marker = "/Android/data/"
                    val idx = dir.absolutePath.indexOf(marker)
                    if (idx <= 0) continue
                    val root = File(dir.absolutePath.substring(0, idx))
                    if (root.exists() && roots.none { it.absolutePath == root.absolutePath }) {
                        roots.add(root)
                    }
                }
            } catch (e: Exception) {
                // Whatever roots were already found (almost always at
                // least primary storage) are still usable.
            }
            return roots
        }

        private fun volumeLabel(root: File): String =
            if (root.absolutePath == Environment.getExternalStorageDirectory()?.absolutePath) {
                "Internal storage"
            } else {
                "Storage: ${root.name}"
            }
    }
}
