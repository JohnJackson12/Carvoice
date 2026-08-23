package com.carvoice.app

/**
 * Same command set as the Windows app's voice_control.py, ported to
 * Kotlin. Kept as pure logic (no Android APIs touched here) so it's easy
 * to reason about independent of the recognizer and player.
 */
object CommandParser {
    const val WAKE = "john"
    val WAKE_ALIASES = listOf("sam")

    private val TRIM_WORDS = mapOf(0 to "zero", 10 to "ten", 20 to "twenty",
        30 to "thirty", 40 to "forty", 50 to "fifty", 60 to "sixty")
    private val TRIM_WORD_TO_SECONDS = TRIM_WORDS.entries.associate { (k, v) -> v to k }
    private val TRIM_STEPS = TRIM_WORDS.keys.toList()

    private val SKIP_WORDS = mapOf(5 to "five", 10 to "ten", 15 to "fifteen",
        20 to "twenty", 25 to "twenty five", 30 to "thirty", 35 to "thirty five",
        40 to "forty", 45 to "forty five", 50 to "fifty", 55 to "fifty five", 60 to "sixty")
    private val SKIP_STEPS = SKIP_WORDS.keys.toList()
    private val MINUTE_STEPS = listOf(1, 2, 3, 4, 5)
    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    /** Every phrase that should trigger listening: the wake word and each
     * alias, each usable bare or with "hey" in front - so "sam" and "hey
     * sam" both work interchangeably. Longest-first so a prefix match
     * prefers "hey john" over the shorter "john" when both would match. */
    fun wakePhrases(wake: String = WAKE, aliases: List<String> = WAKE_ALIASES): List<String> {
        val names = (listOf(wake) + aliases).distinct()
        val phrases = mutableListOf<String>()
        for (n in names) {
            phrases.add(n)
            phrases.add("hey $n")
        }
        return phrases.distinct().sortedByDescending { it.length }
    }

    fun findMatchingWake(text: String, wake: String = WAKE, aliases: List<String> = WAKE_ALIASES): String? {
        for (phrase in wakePhrases(wake, aliases)) {
            if (text == phrase || text.startsWith("$phrase ")) return phrase
        }
        return null
    }

    private fun wordsToNumber(text: String): Int? {
        val t = text.trim()
        t.toIntOrNull()?.let { return it }
        NUMBER_WORDS[t]?.let { return it }
        for ((seconds, word) in SKIP_WORDS) if (word == t) return seconds
        return null
    }

    private fun trimNumber(token: String): Int? {
        TRIM_WORD_TO_SECONDS[token]?.let { return it }
        return token.toIntOrNull()
    }

    /** Grammar phrases fed to Vosk so it only ever tries to match these -
     * mirrors _build_grammar_phrases() in the desktop app's
     * voice_control.py. songTitleKeys are normalized titles (see
     * TextUtils.normalizeTitle) from whatever's actually in the library. */
    fun grammarPhrases(songTitleKeys: List<String> = emptyList(), wakeWord: String = WAKE, aliases: List<String> = WAKE_ALIASES): List<String> {
        val phrases = mutableListOf<String>()
        for (wake in wakePhrases(wakeWord, aliases)) {
            phrases.add(wake)
            for (cmd in listOf("delete", "undo", "next", "previous", "pause", "play", "status")) {
                phrases.add("$wake $cmd")
            }
            for ((seconds, word) in SKIP_WORDS) {
                phrases.add("$wake skip $word")
                phrases.add("$wake skip $seconds")
                phrases.add("$wake skip $word seconds")
            }
            for (m in MINUTE_STEPS) {
                val word = NUMBER_WORDS.entries.firstOrNull { it.value == m }?.key ?: m.toString()
                phrases.add("$wake skip $word minute" + if (m != 1) "s" else "")
            }
            for (front in TRIM_STEPS) for (end in TRIM_STEPS) {
                if (front != 0 || end != 0) {
                    phrases.add("$wake trim ${TRIM_WORDS[front]} ${TRIM_WORDS[end]}")
                }
            }
            for (n in 1..5) {
                val word = NUMBER_WORDS.entries.firstOrNull { it.value == n }?.key ?: n.toString()
                phrases.add("$wake $word")
                phrases.add("$wake $n")
            }
            for (titleKey in songTitleKeys) phrases.add("$wake play $titleKey")
        }
        phrases.add("[unk]")
        return phrases.distinct()
    }

    sealed class Command {
        data class Simple(val name: String) : Command()          // play, pause, next, previous, status, delete, undo
        data class Rate(val value: Int) : Command()
        data class Skip(val seconds: Int) : Command()
        data class Trim(val frontSeconds: Int, val endSeconds: Int) : Command()
        data class PlaySong(val titleKey: String) : Command()
    }

    /** remainder = whatever came after the matched wake phrase, already
     * lowercased/trimmed. songTitleKeys lets "play <title>" resolve -
     * without it, only the bare "play" (resume) matches. */
    fun parse(remainder: String, songTitleKeys: Set<String> = emptySet()): Command? {
        if (remainder.isBlank()) return null
        val simple = setOf("delete", "undo", "next", "previous", "pause", "play", "status")
        if (remainder in simple) return Command.Simple(remainder)

        if (remainder.startsWith("play ") && songTitleKeys.isNotEmpty()) {
            val key = remainder.removePrefix("play ").trim()
            if (key in songTitleKeys) return Command.PlaySong(key)
            return null
        }

        val trimMatch = Regex("^trim (\\S+) (\\S+)$").find(remainder)
        if (trimMatch != null) {
            val front = trimNumber(trimMatch.groupValues[1])
            val end = trimNumber(trimMatch.groupValues[2])
            if (front != null && end != null && front in TRIM_STEPS && end in TRIM_STEPS
                && (front != 0 || end != 0)) {
                return Command.Trim(front, end)
            }
            return null
        }

        val minutesMatch = Regex("^skip (.+) minutes?$").find(remainder)
        if (minutesMatch != null) {
            val minutes = wordsToNumber(minutesMatch.groupValues[1].trim())
            if (minutes != null && minutes in MINUTE_STEPS) return Command.Skip(minutes * 60)
            return null
        }

        val secondsMatch = Regex("^skip (.+) seconds?$").find(remainder)
        if (secondsMatch != null) {
            val seconds = wordsToNumber(secondsMatch.groupValues[1].trim())
            if (seconds != null && seconds in SKIP_STEPS) return Command.Skip(seconds)
            return null
        }

        val bareSkipMatch = Regex("^skip (.+)$").find(remainder)  // bare "skip 30" = seconds
        if (bareSkipMatch != null) {
            val seconds = wordsToNumber(bareSkipMatch.groupValues[1].trim())
            if (seconds != null && seconds in SKIP_STEPS) return Command.Skip(seconds)
            return null
        }

        val rating = wordsToNumber(remainder)
        if (rating != null && rating in 1..5) return Command.Rate(rating)
        return null
    }
}
