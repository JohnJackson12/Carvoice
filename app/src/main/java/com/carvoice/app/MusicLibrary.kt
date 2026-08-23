package com.carvoice.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

/** Single shared source of truth for "what songs exist" - both
 * MainActivity's song list and VoiceService's recognizer grammar /
 * playback read from this, so they can never disagree about what's
 * playable. Runs in-process (VoiceService isn't a separate process),
 * so a plain singleton object is enough - no IPC needed.
 */
object MusicLibrary {
    private val songs = mutableListOf<Song>()
    private val listeners = mutableListOf<() -> Unit>()

    fun all(): List<Song> = songs.toList()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Rebuilds the song list from the whole-device MediaStore scan PLUS
     * any folders added via "Add Music Folder" in Settings. Call this at
     * startup and after Settings changes the folder list. */
    fun rescan(context: Context) {
        val result = mutableListOf<Song>()
        result.addAll(scanMediaStore(context))
        for (uriString in Prefs.folderUris(context)) {
            try {
                result.addAll(scanFolderTree(context, Uri.parse(uriString)))
            } catch (e: Exception) {
                // A folder can vanish (SD card removed, permission revoked) -
                // skip it rather than let one bad folder break the whole scan.
            }
        }
        // De-dupe by URI (the same song could technically show up via both
        // MediaStore and a manually-added folder pointing at the same file).
        val deduped = result.distinctBy { it.uri.toString() }.sortedBy { it.title.lowercase() }
        songs.clear()
        songs.addAll(deduped)
        listeners.forEach { it() }
    }

    private fun scanMediaStore(context: Context): List<Song> {
        val found = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
            MediaStore.Audio.Media.TITLE + " ASC"
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                found.add(Song(uri, it.getString(titleCol) ?: "Unknown", it.getString(artistCol) ?: ""))
            }
        }
        return found
    }

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "m4a", "aac", "opus")

    private fun scanFolderTree(context: Context, treeUri: Uri): List<Song> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val found = mutableListOf<Song>()
        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else {
                    val name = child.name ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXTENSIONS) {
                        val title = name.substringBeforeLast('.')
                        found.add(Song(child.uri, title, ""))
                    }
                }
            }
        }
        walk(root)
        return found
    }
}
