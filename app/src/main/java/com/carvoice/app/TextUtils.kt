package com.carvoice.app

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
