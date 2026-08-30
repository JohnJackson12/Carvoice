package com.carvoice.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache

/** Loads whatever embedded cover art a song's own file carries. Used for
 * the two artwork panels above the song list (see activity_main's
 * layout-land): "Now Playing" on the left, "Up Next" on the right.
 *
 * For MP3/FLAC specifically, embedded art is read via EmbeddedArt (direct
 * byte-level ID3v2/FLAC parsing) rather than MediaMetadataRetriever -
 * MediaMetadataRetriever's embeddedPicture is what this used to rely on
 * exclusively, and it is known to be unreliable across the various
 * ID3v2.2/2.3/2.4 and FLAC METADATA_BLOCK_PICTURE layouts different
 * ripping/tagging tools produce (this is exactly why a file can show its
 * cover instantly in another player - which ships its own tag parser -
 * and show nothing here). MediaMetadataRetriever is kept as the path for
 * every other container (M4A/AAC's 'covr' atom, etc.), where it's fine.
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

    fun loadAsync(context: Context, uri: Uri?, onResult: (Bitmap?) -> Unit) {
        loadAsync(context, uri, null, onResult)
    }

    /** [fallbackUri] is a cover.jpg/folder.jpg-style image file found next
     * to the song at scan time (see MusicLibrary.pickBestCoverArtFromNames) -
     * tried only if [uri]'s own file has no embedded picture. Most
     * folder-ripped libraries with genuinely no embedded art at all carry
     * it this way instead, so this is what shows a real image for those. */
    fun loadAsync(context: Context, uri: Uri?, fallbackUri: Uri?, onResult: (Bitmap?) -> Unit) {
        if (uri == null && fallbackUri == null) {
            onResult(null)
            return
        }
        val key = "${uri ?: ""}|${fallbackUri ?: ""}"
        cache.get(key)?.let { onResult(it); return }
        val appContext = context.applicationContext
        Thread {
            // "Now playing" art is requested for a song at the exact same
            // moment VoiceService's MediaPlayer opens that same URI to
            // start it playing - a genuine, structural race, not a rare
            // fluke, and it used to be able to make the FIRST read of a
            // given song's art fail (a transient, momentary contention on
            // the same file handle) and get treated as "this file has no
            // art" - permanently, for the rest of the app session, since
            // that used to be cached as a negative result too. There's no
            // such permanent negative cache anymore - a real "no art"
            // result just isn't cached at all, and it's cheap enough not
            // to be - and this retries once after a brief pause so a
            // one-off collision like that doesn't need a whole app
            // restart to resolve itself.
            var bitmap = uri?.let { loadEmbedded(appContext, it) }
            if (bitmap == null && uri != null) {
                Thread.sleep(200)
                bitmap = loadEmbedded(appContext, uri)
            }
            if (bitmap == null) bitmap = fallbackUri?.let { loadFromImageFile(appContext, it) }
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post { onResult(bitmap) }
        }.start()
    }

    private fun loadEmbedded(context: Context, uri: Uri): Bitmap? {
        EmbeddedArt.extract(context, uri)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        // Anything EmbeddedArt doesn't handle by extension (M4A/AAC's
        // 'covr' atom, etc.) - or where the direct parse didn't turn up a
        // usable frame - falls back to MediaMetadataRetriever.
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
