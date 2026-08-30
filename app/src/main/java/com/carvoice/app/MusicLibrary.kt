package com.carvoice.app

import android.content.Context
import android.media.MediaMetadataRetriever
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

    /** Called right after a song's underlying file was actually deleted
     * from storage (see MainActivity's delete flow) - updates the
     * in-memory list and the on-disk cache immediately so it disappears
     * from the song list right away, rather than waiting for the next
     * full rescan to notice it's gone. */
    fun removeSong(context: Context, uri: Uri) {
        val uriKey = uri.toString()
        songs.removeAll { it.uri.toString() == uriKey }
        saveCache(context)
        mainHandler.post { listeners.forEach { it() } }
    }

    /** The other half of [removeSong] - puts a song back after
     * SongDeleter.undoLast() restores its underlying file, without
     * waiting on a full rescan() (which would also work, just far more
     * slowly for a large library, and rescan() isn't guaranteed to see a
     * just-restored file immediately on every storage backend). */
    fun restoreSong(context: Context, song: Song) {
        if (songs.none { it.uri.toString() == song.uri.toString() }) {
            songs.add(song)
        }
        saveCache(context)
        mainHandler.post { listeners.forEach { it() } }
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
                val coverStr = obj.optString("coverUri", "")
                loaded.add(
                    Song(
                        Uri.parse(obj.getString("uri")),
                        obj.getString("title"),
                        obj.getString("artist"),
                        obj.optString("folder", ""),
                        obj.optInt("folderIndex", 0),
                        if (coverStr.isNotEmpty()) Uri.parse(coverStr) else null,
                    )
                )
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
                obj.put("folder", song.folder)
                obj.put("folderIndex", song.folderIndex)
                obj.put("coverUri", song.coverUri?.toString() ?: "")
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
     * Two phases, both still on whatever background thread the caller
     * used to call rescan() itself (never the main thread):
     *   1. FAST - just enumerates files and titles (what folder listing
     *      alone already gives you, no per-file I/O) and publishes that
     *      immediately - titles/browsing/playback are usable right away.
     *   2. SLOWER, runs right after - reads each song's actual artist tag
     *      (real per-file I/O, this is the part that used to make the
     *      whole scan feel slow) and republishes incrementally as it
     *      goes, so artists visibly fill in afterward instead of the
     *      whole song list staying invisible until every tag is read.
     * Cover art doesn't need its own phase - see pickBestCoverArtFromNames's
     * comment for why it's effectively free and gets included in phase 1.
     *
     * progressCb(filesSeen, songsAdded), if given, is called periodically
     * during a folder scan (matches the Windows app's scan(progress_cb=))
     * so a large scan can show visible "still working" feedback instead
     * of looking hung - called from whatever thread rescan() itself runs
     * on, same as the final listener notification's thread-hop applies
     * only to that, not to this. */
    fun rescan(context: Context, progressCb: ((seen: Int, added: Int) -> Unit)? = null) {
        try {
            val result = mutableListOf<Song>()
            val toEnrich = mutableListOf<PendingArtist>()
            if (Prefs.useWholeDeviceLibrary(context)) {
                try {
                    result.addAll(scanMediaStore(context))
                } catch (e: Exception) {
                    // A revoked READ_MEDIA_AUDIO permission (Android can
                    // auto-revoke unused permissions after months of
                    // inactivity) used to throw here UNCAUGHT - on a
                    // background thread, that kills the entire app process
                    // with no trace. Log it via CrashLog instead and just
                    // skip the whole-device part of the scan.
                    CrashLog.record(context, "scanMediaStore failed: ${e}")
                }
            }
            var seen = 0
            for (uriString in Prefs.folderUris(context)) {
                try {
                    val folderSongs = if (uriString.startsWith("content://")) {
                        scanFolderTree(context, Uri.parse(uriString), toEnrich) { s ->
                            seen = s
                            progressCb?.invoke(seen, result.size)
                        }
                    } else {
                        // A plain absolute path, added via
                        // FolderBrowserActivity's in-app browser (used on
                        // devices with no OS folder-picker app at all).
                        scanRawFolderTree(java.io.File(uriString), toEnrich) { s ->
                            seen = s
                            progressCb?.invoke(seen, result.size)
                        }
                    }
                    result.addAll(folderSongs)
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
            // PHASE 1 published - titles/cover art/browsing/playback all
            // usable now. Listeners often touch a MediaPlayer or a View -
            // always hand that off to the main thread, regardless of
            // which thread called rescan().
            mainHandler.post { listeners.forEach { it() } }

            enrichArtists(context, toEnrich)
        } catch (e: Exception) {
            // Last-resort net for this whole function - whatever wasn't
            // already caught above still must never take the app down.
            CrashLog.record(context, "MusicLibrary.rescan failed: ${e}")
        }
    }

    /** What phase 2 needs to go read one song's real artist tag - either
     * a SAF document (needs Context + Uri) or a plain filesystem path. */
    private sealed class PendingArtist {
        abstract val uriKey: String
        data class Saf(val uri: Uri) : PendingArtist() { override val uriKey get() = uri.toString() }
        data class Raw(val path: String, val uri: Uri) : PendingArtist() { override val uriKey get() = uri.toString() }
    }

    /** Phase 2 - reads each pending song's real artist tag (the slow,
     * per-file I/O part) and republishes into `songs` incrementally, so a
     * large library's artists visibly fill in over a few seconds rather
     * than the whole scan being invisible until the very last file is
     * read. Batched (every 40 songs, not every single one) so this isn't
     * hammering the main thread / disk with a notification+save per file. */
    private fun enrichArtists(context: Context, pending: List<PendingArtist>) {
        if (pending.isEmpty()) return
        var sinceLastPublish = 0
        for (item in pending) {
            val artist = when (item) {
                is PendingArtist.Saf -> readArtistTag(context, item.uri)
                is PendingArtist.Raw -> readArtistTag(item.path)
            }
            if (artist.isEmpty()) continue  // nothing to update - leave the placeholder "" as-is
            val idx = songs.indexOfFirst { it.uri.toString() == item.uriKey }
            if (idx >= 0) songs[idx] = songs[idx].copy(artist = artist)
            sinceLastPublish++
            if (sinceLastPublish >= 40) {
                sinceLastPublish = 0
                saveCache(context)
                mainHandler.post { listeners.forEach { it() } }
            }
        }
        if (sinceLastPublish > 0) {
            saveCache(context)
            mainHandler.post { listeners.forEach { it() } }
        }
    }

    private fun scanMediaStore(context: Context): List<Song> {
        // (uri, title, artist, folder) - folderIndex isn't known yet here,
        // since MediaStore returns rows title-first, not folder-grouped.
        // Assigned in a second pass below, once every row for a given
        // folder is known, same idea as the two SAF/raw-file scanners.
        data class Raw(val uri: Uri, val title: String, val artist: String, val folder: String)
        val found = mutableListOf<Raw>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
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
            // BUCKET_DISPLAY_NAME (the containing folder's name) is present
            // on every API level this app supports, unlike RELATIVE_PATH
            // (API 29+ only) - safe to read unconditionally.
            val folderCol = it.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                val folder = if (folderCol >= 0) it.getString(folderCol) ?: "" else ""
                found.add(Raw(uri, it.getString(titleCol) ?: "Unknown", it.getString(artistCol) ?: "", folder))
            }
        }
        // Number each song by its position (alphabetical by title) within
        // its own folder - matches how the two folder-tree scanners below
        // number songs, so the "Song#" column means the same thing
        // regardless of which source found the track.
        return found
            .groupBy { it.folder }
            .flatMap { (_, songsInFolder) ->
                songsInFolder.sortedBy { it.title.lowercase() }
                    .mapIndexed { i, raw -> Song(raw.uri, raw.title, raw.artist, raw.folder, i + 1) }
            }
    }

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "m4a", "aac", "opus")

    // Filenames that conventionally carry a folder's album art when it
    // isn't embedded in each individual track. Matched as a SUBSTRING
    // (case-insensitive), not an exact name - real-world rips use all
    // sorts of variations ("Folder.jpg", "AlbumArtSmall.jpg", "01 -
    // Cover.jpg"), and an exact-name-only check was missing almost all of
    // them, which is why art kept showing up blank even for folders that
    // clearly had a cover image sitting right there.
    private val COVER_KEYWORDS = listOf("cover", "folder", "album", "front", "artwork", "art")
    private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** Reads the ID3/FLAC/MP4 "artist" tag straight out of the file itself
     * via MediaMetadataRetriever - real file I/O, only ever called from
     * rescan()'s background thread. Folder-scanned songs used to get an
     * empty artist ("" hardcoded, never actually looked at the file) -
     * this is what actually populates it. Returns "" (never null/throws)
     * on anything unreadable, so one bad/corrupt file can't take the rest
     * of the scan down with it. */
    private fun readArtistTag(context: Context, uri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            try { retriever.release() } catch (e: Exception) { /* already gone */ }
        }
    }

    /** Same as above, for a plain filesystem path (scanRawFolderTree) -
     * MediaMetadataRetriever can take a path string directly, no Context
     * or content-resolver round trip needed for these. */
    private fun readArtistTag(path: String): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            try { retriever.release() } catch (e: Exception) { /* already gone */ }
        }
    }

    /** Picks the folder's cover art out of a listing you ALREADY have in
     * hand (the same dir.listFiles()/File.listFiles() call the walk below
     * already made to find the audio files) - not a second directory
     * query. Cover-art detection used to call dir.listFiles() again by
     * itself, which was a second, entirely avoidable IPC round-trip (for
     * SAF folders) on every single folder in the scan for no reason. */
    private fun pickBestCoverArtFromNames(images: List<Pair<String, Uri>>): Uri? {
        if (images.isEmpty()) return null
        val keywordMatch = images.firstOrNull { (name, _) ->
            val base = name.substringBeforeLast('.', name).lowercase()
            COVER_KEYWORDS.any { kw -> base.contains(kw) }
        }
        if (keywordMatch != null) return keywordMatch.second
        // No obviously-named cover file, but if there's exactly one image
        // in the folder at all, it's almost certainly the cover - don't
        // guess among several unrelated images (liner notes, band photos),
        // but a lone image next to a batch of tracks isn't a coincidence.
        return if (images.size == 1) images[0].second else null
    }

    private fun isImageFileName(name: String?): Boolean {
        if (name == null) return false
        return name.substringAfterLast('.', "").lowercase() in COVER_EXTENSIONS
    }

    private fun scanFolderTree(
        context: Context,
        treeUri: Uri,
        toEnrich: MutableList<PendingArtist>,
        onProgress: ((Int) -> Unit)? = null,
    ): List<Song> {
        // DocumentFile.listFiles() is one IPC round-trip per directory (SAF
        // has no bulk recursive query), so a deep tree with many folders
        // is inherently slow - this is exactly why rescan() must never run
        // on the main thread for a large library.
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val found = mutableListOf<Song>()
        var seen = 0
        fun walk(dir: DocumentFile) {
            // Collected per-directory (not appended straight into `found`)
            // so each song can be numbered by its alphabetical position
            // within THIS folder specifically - the "Song#" list column -
            // rather than some running total across the whole tree.
            val folderName = dir.name ?: ""
            val inThisFolder = mutableListOf<Pair<String, DocumentFile>>()
            val imagesInFolder = mutableListOf<Pair<String, Uri>>()
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else {
                    seen++
                    if (seen % 200 == 0) onProgress?.invoke(seen)
                    val name = child.name ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXTENSIONS) {
                        inThisFolder.add(name.substringBeforeLast('.') to child)
                    } else if (isImageFileName(name)) {
                        imagesInFolder.add(name to child.uri)
                    }
                }
            }
            if (inThisFolder.isEmpty()) return
            val coverUri = pickBestCoverArtFromNames(imagesInFolder)
            inThisFolder.sortedBy { it.first.lowercase() }.forEachIndexed { i, (title, doc) ->
                // Artist left blank here on purpose - phase 1 is titles-only
                // for speed; MusicLibrary.enrichArtists() fills it in right
                // after, from `toEnrich`, without a second directory walk.
                found.add(Song(doc.uri, title, "", folderName, i + 1, coverUri))
                toEnrich.add(PendingArtist.Saf(doc.uri))
            }
        }
        walk(root)
        onProgress?.invoke(seen)
        return found
    }

    /** Same job as scanFolderTree() above, but for a folder added via
     * FolderBrowserActivity's in-app browser - a plain filesystem path
     * rather than a SAF tree URI, so plain java.io.File calls (not
     * DocumentFile/IPC) do the walking. Only reachable at all because
     * MANAGE_EXTERNAL_STORAGE was granted before that folder could be
     * picked in the first place - see FolderBrowserActivity. */
    private fun scanRawFolderTree(
        root: java.io.File,
        toEnrich: MutableList<PendingArtist>,
        onProgress: ((Int) -> Unit)? = null,
    ): List<Song> {
        val found = mutableListOf<Song>()
        var seen = 0
        fun walk(dir: java.io.File) {
            val children = dir.listFiles() ?: return
            // Same per-directory numbering approach as scanFolderTree above.
            val inThisFolder = mutableListOf<Pair<String, java.io.File>>()
            val imagesInFolder = mutableListOf<Pair<String, Uri>>()
            for (child in children) {
                if (child.isDirectory) {
                    walk(child)
                } else {
                    seen++
                    if (seen % 200 == 0) onProgress?.invoke(seen)
                    val ext = child.name.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXTENSIONS) {
                        inThisFolder.add(child.name.substringBeforeLast('.') to child)
                    } else if (isImageFileName(child.name)) {
                        imagesInFolder.add(child.name to Uri.fromFile(child))
                    }
                }
            }
            if (inThisFolder.isEmpty()) return
            val coverUri = pickBestCoverArtFromNames(imagesInFolder)
            inThisFolder.sortedBy { it.first.lowercase() }.forEachIndexed { i, (title, file) ->
                val uri = Uri.fromFile(file)
                found.add(Song(uri, title, "", dir.name, i + 1, coverUri))
                toEnrich.add(PendingArtist.Raw(file.absolutePath, uri))
            }
        }
        walk(root)
        onProgress?.invoke(seen)
        return found
    }
}
