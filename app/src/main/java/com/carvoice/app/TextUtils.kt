package com.carvoice.app

import java.util.Locale

/** Formats a millisecond duration as "m:ss" (or "h:mm:ss" past an hour) for
 * the elapsed/remaining labels around the now-playing seek bar. Negative
 * or nonsensical input (e.g. duration not known yet) just shows "0:00"
 * rather than a confusing negative or garbage string. */
object TimeFormat {
    fun format(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** Mirrors text_utils.py's normalize_title() on the Windows side - lowercase,
 * strip punctuation to spaces, collapse whitespace. Used both to build the
 * "<wake> play <title>" grammar and to match a recognized phrase back to an
 * actual song in the playlist. */
object TitleNormalizer {
    fun normalize(text: String?): String {
        if (text == null) return ""
        val lowered = text.lowercase()
        val stripped = lowered.replace(Regex("[^a-z0-9 ]+"), " ")
        return stripped.replace(Regex("\\s+"), " ").trim()
    }
}
