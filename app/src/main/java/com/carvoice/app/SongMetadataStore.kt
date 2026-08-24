package com.carvoice.app

import android.content.Context

/** Per-song rating and trim points, keyed by the song's URI string (as
 * text, since that's what's stable across a rescan and what VoiceService
 * already has on hand via song.uri.toString()). Persisted in the app's
 * own storage - not written into the audio file's tags, see SETUP.md for
 * why - so it survives restarts, applies automatically next time a song
 * loads, and is identical whether set by voice command or from the GUI's
 * star row / trim buttons, since both call through this same store. */
object SongMetadataStore {
    private const val FILE = "car_voice_song_meta"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun raw(context: Context, uriKey: String): Triple<Int, Int, Int> {
        val value = prefs(context).getString(uriKey, null) ?: return Triple(0, 0, 0)
        val parts = value.split(",").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple(0, 0, 0)
    }

    private fun saveRaw(context: Context, uriKey: String, rating: Int, front: Int, end: Int) {
        prefs(context).edit().putString(uriKey, "$rating,$front,$end").apply()
    }

    fun rating(context: Context, uriKey: String): Int = raw(context, uriKey).first
    fun trimFront(context: Context, uriKey: String): Int = raw(context, uriKey).second
    fun trimEnd(context: Context, uriKey: String): Int = raw(context, uriKey).third

    fun setRating(context: Context, uriKey: String, value: Int) {
        val (_, front, end) = raw(context, uriKey)
        saveRaw(context, uriKey, value.coerceIn(0, 5), front, end)
    }

    fun setTrim(context: Context, uriKey: String, front: Int, end: Int) {
        val (rating, _, _) = raw(context, uriKey)
        saveRaw(context, uriKey, rating, front, end)
    }

    fun clearTrim(context: Context, uriKey: String) {
        val (rating, _, _) = raw(context, uriKey)
        saveRaw(context, uriKey, rating, 0, 0)
    }
}
