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
    private const val KEY_USE_WHOLE_DEVICE = "use_whole_device_library"  // off by default - see MusicLibrary.rescan()
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

    /** Off by default - MusicLibrary.rescan() shows nothing until you
     * either add a folder or turn this on. */
    fun useWholeDeviceLibrary(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_WHOLE_DEVICE, false)
    fun setUseWholeDeviceLibrary(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_USE_WHOLE_DEVICE, value).apply()

    fun nightMode(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_MODE, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    fun setNightMode(context: Context, mode: Int) =
        prefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply()

    /** SKIP: a single GLOBAL, live value (seconds) - how far into EVERY
     * song playback starts, current and future - not a per-song thing.
     * Matches the Windows app's config.json "skip_seconds" / Player's
     * set_skip_seconds(). Distinct from a song's own TRIM front-cut point
     * (SongMetadataStore.trimFront) - see VoiceService.effectiveStart(),
     * which takes whichever of the two is larger. */
    private const val KEY_SKIP_SECONDS = "skip_seconds"
    fun skipSeconds(context: Context): Int = prefs(context).getInt(KEY_SKIP_SECONDS, 0)
    fun setSkipSeconds(context: Context, seconds: Int) =
        prefs(context).edit().putInt(KEY_SKIP_SECONDS, seconds.coerceIn(0, 60)).apply()

    /** Whether to auto-launch the app when the device finishes booting -
     * for a tablet mounted in a car, that's effectively "open when the
     * car turns on". Off by default: auto-starting anything on boot is
     * worth an explicit opt-in rather than assuming it's wanted. */
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    fun autoStartOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, false)
    fun setAutoStartOnBoot(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, value).apply()

    /** Where playback was when the app last closed (gracefully OR abruptly -
     * a car's ignition turning off is exactly the abrupt case, so this is
     * saved periodically during playback, not just on a clean exit - see
     * VoiceService's progress tick). Read back on next startup to resume
     * right where it left off, automatically, with no taps needed. */
    private const val KEY_LAST_SONG_URI = "last_song_uri"
    private const val KEY_LAST_POSITION_MS = "last_position_ms"
    fun lastSongUri(context: Context): String? = prefs(context).getString(KEY_LAST_SONG_URI, null)
    fun lastPositionMs(context: Context): Int = prefs(context).getInt(KEY_LAST_POSITION_MS, 0)
    fun setLastPlayback(context: Context, songUri: String, positionMs: Int) {
        prefs(context).edit()
            .putString(KEY_LAST_SONG_URI, songUri)
            .putInt(KEY_LAST_POSITION_MS, positionMs)
            .apply()
    }
}
