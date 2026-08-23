package com.carvoice.app

import android.content.Context
import android.content.SharedPreferences

/** Everything persisted about how this app is configured - one place so
 * MainActivity, SettingsActivity, and VoiceService all agree on where
 * these values live and what the defaults are. */
object Prefs {
    private const val FILE = "car_voice_prefs"
    private const val KEY_WAKE_WORD = "wake_word"
    private const val KEY_WAKE_ALIASES = "wake_aliases"
    private const val KEY_VOLUME = "playback_volume"       // 0.0-1.0, the level you actually chose
    private const val KEY_FOLDER_URIS = "folder_uris"      // SAF tree URIs added via "Add Music Folder"
    private const val KEY_NIGHT_MODE = "night_mode"        // AppCompatDelegate mode int, as a string

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun wakeWord(context: Context): String = prefs(context).getString(KEY_WAKE_WORD, CommandParser.WAKE) ?: CommandParser.WAKE
    fun setWakeWord(context: Context, value: String) = prefs(context).edit().putString(KEY_WAKE_WORD, value).apply()

    fun wakeAliases(context: Context): List<String> =
        (prefs(context).getString(KEY_WAKE_ALIASES, CommandParser.WAKE_ALIASES.joinToString(",")) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun setWakeAliases(context: Context, aliases: List<String>) =
        prefs(context).edit().putString(KEY_WAKE_ALIASES, aliases.joinToString(",")).apply()

    /** The volume you actually chose - everything else (ducking for voice
     * commands, TTS) is a temporary multiplier applied on TOP of this and
     * always restored back to exactly this value afterward. Never
     * hardcode 1.0f anywhere else in the app - always read this. */
    fun playbackVolume(context: Context): Float = prefs(context).getFloat(KEY_VOLUME, 0.85f)
    fun setPlaybackVolume(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_VOLUME, value.coerceIn(0f, 1f)).apply()

    fun folderUris(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FOLDER_URIS, emptySet()) ?: emptySet()
    fun addFolderUri(context: Context, uri: String) {
        val current = folderUris(context).toMutableSet()
        current.add(uri)
        prefs(context).edit().putStringSet(KEY_FOLDER_URIS, current).apply()
    }
    fun removeFolderUri(context: Context, uri: String) {
        val current = folderUris(context).toMutableSet()
        current.remove(uri)
        prefs(context).edit().putStringSet(KEY_FOLDER_URIS, current).apply()
    }

    fun nightMode(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_MODE, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    fun setNightMode(context: Context, mode: Int) =
        prefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply()
}
