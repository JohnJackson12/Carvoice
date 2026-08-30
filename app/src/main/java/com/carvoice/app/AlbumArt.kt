package com.carvoice.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache

/** Loads whatever embedded cover art a song's own file carries (ID3/FLAC/
 * MP4 "picture" frame) via MediaMetadataRetriever. Used for the two
 * artwork panels above the song list (see activity_main's layout-land):
 * "Now Playing" on the left, "Up Next" on the right.
 *
 * Reading embedded art means opening and parsing the file itself, which is
 * real I/O - never call [load] from the main thread. [loadAsync] handles
 * that hop for callers (MainActivity) that just want a callback on the UI
 * thread once a bitmap (or null, if the file has no embedded art) is
 * ready.
 *
 * A small LRU bitmap cache keyed by URI string avoids re-decoding the same
 * artwork on every track change/UI refresh - cover art can be a few
 * hundred KB of embedded JPEG, and re-parsing it on the main thread's
 * behalf on every single progress tick would be wasteful even off-thread. */
object AlbumArt {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Bitmaps only, not raw bytes - a few full-size decoded covers is a
    // trivial amount of memory for a phone/head-unit, and this avoids
    // re-decoding JPEG bytes into a Bitmap on every request.
    private val cache = object : LruCache<String, Bitmap>(12) {}

    // NoArt is cached too (distinct from "not yet looked up") so a
    // no-artwork file - the common case for folder-ripped mp3s - doesn't
    // get its file re-opened and re-parsed by MediaMetadataRetriever on
    // every single track change just to find out again that it has none.
    private val noArtUris = mutableSetOf<String>()

    fun loadAsync(context: Context, uri: Uri?, onResult: (Bitmap?) -> Unit) {
        loadAsync(context, uri, null, onResult)
    }

    /** [fallbackUri] is a cover.jpg/folder.jpg-style image file found next
     * to the song at scan time (see MusicLibrary.findFolderCoverArt) -
     * tried only if [uri]'s own file has no embedded picture frame. Most
     * folder-ripped libraries carry art this way rather than embedded per
     * track, so this is what actually shows a real image instead of the
     * placeholder icon for those. Cache key includes both URIs so a song
     * that later gains real embedded art (edited tags, re-ripped file)
     * isn't stuck showing a stale cached fallback result. */
    fun loadAsync(context: Context, uri: Uri?, fallbackUri: Uri?, onResult: (Bitmap?) -> Unit) {
        if (uri == null && fallbackUri == null) {
            onResult(null)
            return
        }
        val key = "${uri ?: ""}|${fallbackUri ?: ""}"
        cache.get(key)?.let { onResult(it); return }
        if (key in noArtUris) {
            onResult(null)
            return
        }
        val appContext = context.applicationContext
        Thread {
            val bitmap = (uri?.let { load(appContext, it) })
                ?: fallbackUri?.let { loadFromImageFile(appContext, it) }
            if (bitmap != null) cache.put(key, bitmap) else synchronized(noArtUris) { noArtUris.add(key) }
            mainHandler.post { onResult(bitmap) }
        }.start()
    }

    private fun load(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { /* already gone */ }
        }
    }

    /** Decodes a plain image file (cover.jpg etc., not an audio file) via
     * the content resolver so this works uniformly for both content://
     * (SAF) and file:// (raw-folder) cover URIs. */
    private fun loadFromImageFile(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}
