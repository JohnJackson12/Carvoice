package com.carvoice.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/** Single shared source of truth for "what songs exist" - both
 * MainActivity's song list and VoiceService's recognizer grammar /
 * playback read from this, so they can never disagree about what's
 * playable. Runs in-process (VoiceService isn't a separate process),
 * so a plain singleton object is enough - no IPC needed.
 *
 * Built with large libraries (many folders, tens of thousands of songs)
 * in mind: rescan() does real IPC-bound folder walking and MUST be called
 * from a background thread - it is NOT safe to call from onCreate()/UI
 * code directly. Listener notifications are always dispatched on the
 * main thread regardless of which thread called rescan(), so anything
 * touching a MediaPlayer or a View in response stays thread-safe without
 * every listener needing to remember to hop threads itself.
 */
object MusicLibrary {
    private val songs = mutableListOf<Song>()
    private val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val CACHE_FILE = "car_voice_library_cache"
    private const val CACHE_KEY = "songs_json"

    // ALWAYS returns alphabetical-by-title order, defensively re-sorted
    // here regardless of how `songs` was populated - not just relying on
    // every call site (rescan, loadCache) to have sorted it correctly
    // beforehand. This is what guarantees the list can never appear to
    // "change sort order" between a cache load and a real rescan, or
    // after some future code path adds to `songs` without remembering to
    // sort. Cheap enough to do on every call for realistic library sizes.
    fun all(): List<Song> = songs.sortedBy { it.title.lowercase() }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Instantly populates from whatever was found last time, without
     * touching disk/SAF at all - call this first at startup so something
     * is usually already playable/browsable while rescan() (slow, real
     * I/O) runs in the background afterward. Does not notify listeners -
     * the caller just checks all() right after calling this. */
    fun loadCache(context: Context) {
        if (songs.isNotEmpty()) return  // already populated this process run
        try {
            val raw = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null) ?: return
            val arr = JSONArray(raw)
            val loaded = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                loaded.add(Song(Uri.parse(obj.getString("uri")), obj.getString("title"), obj.getString("artist")))
            }
            songs.clear()
            // Sorted here too (not just trusting the cache file was saved
            // in order) - a cache written by an older build with different
            // scan/sort logic, or any other source of a stale ordering,
            // gets normalized immediately on load rather than showing a
            // wrong order until the next rescan corrects it.
            songs.addAll(loaded.sortedBy { it.title.lowercase() })
        } catch (e: Exception) {
            // A corrupt or missing cache just means "start empty until the
            // real rescan finishes" - not worth crashing over.
        }
    }

    private fun saveCache(context: Context) {
        try {
            val arr = JSONArray()
            for (song in songs) {
                val obj = JSONObject()
                obj.put("uri", song.uri.toString())
                obj.put("title", song.title)
                obj.put("artist", song.artist)
                arr.put(obj)
            }
            context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
                .edit().putString(CACHE_KEY, arr.toString()).apply()
        } catch (e: Exception) {
            // Caching is a speed-up, not a requirement - a failed save just
            // means the next cold start rescans from scratch instead of
            // starting from cache. Not worth surfacing as an error.
        }
    }

    /** Rebuilds the song list from whatever folders you've explicitly
     * added via "Add Music Folder" in Settings - PLUS the whole-device
     * MediaStore library, but only if you've turned that on (it's off by
     * default, on purpose: nothing shows up until you actually pick a
     * folder, instead of silently pulling in every audio file on the
     * device).
     *
     * This does real disk/SAF I/O and can take a while for a large,
     * multi-folder library - ALWAYS call this from a background thread,
     * never from onCreate()/UI code directly.
     *
     * progressCb(filesSeen, songsAdded), if given, is called periodically
     * during a folder scan (matches the Windows app's scan(progress_cb=))
     * so a large scan can show visible "still working" feedback instead
     * of looking hung - called from whatever thread rescan() itself runs
     * on, same as the final listener notification's thread-hop applies
     * only to that, not to this. */
    fun rescan(context: Context, progressCb: ((seen: Int, added: Int) -> Unit)? = null) {
        val result = mutableListOf<Song>()
        if (Prefs.useWholeDeviceLibrary(context)) {
            result.addAll(scanMediaStore(context))
        }
        var seen = 0
        for (uriString in Prefs.folderUris(context)) {
            try {
                result.addAll(scanFolderTree(context, Uri.parse(uriString)) { s ->
                    seen = s
                    progressCb?.invoke(seen, result.size)
                })
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
        saveCache(context)
        progressCb?.invoke(seen, deduped.size)
        // Listeners often touch a MediaPlayer or a View - always hand
        // that off to the main thread, regardless of which thread called
        // rescan() (Settings' button already uses a background thread;
        // VoiceService's startup rescan does too).
        mainHandler.post { listeners.forEach { it() } }
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

    private fun scanFolderTree(context: Context, treeUri: Uri, onProgress: ((Int) -> Unit)? = null): List<Song> {
        // DocumentFile.listFiles() is one IPC round-trip per directory (SAF
        // has no bulk recursive query), so a deep tree with many folders
        // is inherently slow - this is exactly why rescan() must never run
        // on the main thread for a large library.
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val found = mutableListOf<Song>()
        var seen = 0
        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else {
                    seen++
                    if (seen % 200 == 0) onProgress?.invoke(seen)
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
        onProgress?.invoke(seen)
        return found
    }
}
