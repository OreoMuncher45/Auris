package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The lyrics an installed Mornye `.sflx` extension answers with.
 *
 * A package that declares itself a `lyrics_provider` implements one function —
 * `fetchLyrics(track, artist, album, durationSeconds)` — and answers with the
 * shape the other Mornye host established:
 *
 * ```json
 * {
 *   "lines": [
 *     {"startTimeMs": 15460, "words": "<00:15.46>It <00:15.70>was ", "endTimeMs": 17300}
 *   ],
 *   "syncType": "LINE_SYNCED",
 *   "instrumental": false,
 *   "plainLyrics": "It was\nSittin' in the car",
 *   "provider": "Apple Music"
 * }
 * ```
 *
 * ## What the words field holds
 *
 * Enhanced LRC: a stamp in front of every word, and the text after it. A line
 * with no stamps at all is a plain line that happens to be timed; a line whose
 * stamps are all zero is a line with no timing.
 *
 * The stamp *after* the last word is the end of that word — the writer puts it
 * there so a word's length is known rather than inferred from the next line —
 * so the parser reads the stamps as a run of timed pieces and lets the empty
 * one close the word before it.
 *
 * ## The answering vocal
 *
 * Some lines are sung over a second voice, and the extension writes it as a
 * block of its own under the line:
 *
 * ```
 * <00:15.46>It <00:15.70>was
 * [bg:<00:15.80>oh <00:16.10>oh]
 * ```
 *
 * That is a second voice with its own clock, not part of the line's text, so
 * it becomes the line's [LyricLine.background] — which is what the panel
 * draws underneath.
 *
 * ## The translations
 *
 * The extension can append a translation of every line, and a romanization of
 * it, after a separator line stamped at [SEPARATOR_MS]. They are dropped here:
 * this app has its own translation layer, which translates the original, and
 * translating a translation is how a word ends up in three languages.
 */
object ExtensionLyrics {

    /**
     * The stamp the extension puts on the line that separates the lyrics from
     * their translations.
     *
     * A time no recording reaches, which is how the two blocks are told apart
     * in a list that carries nothing else to say which is which.
     */
    const val SEPARATOR_MS = 999_999_999L

    /** The marker the extension writes the answering vocal under. */
    private const val BACKGROUND = "\n[bg:"

    /** A word stamp: `<mm:ss.xx>`, the extension's own inline LRC. */
    private val STAMP = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>([^<]*)""")

    /**
     * The lines [raw] holds, or null when it holds none.
     *
     * Null rather than an empty list, because an empty answer is a miss: the
     * lookup carries on with the sources behind this one, and a source that
     * answered with nothing would otherwise stop the race.
     */
    fun parse(raw: String): List<LyricLine>? {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return null
        val root = runCatching { lyricsJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null

        val entries = root.objects("lines")
        // Everything after the separator is a translation or a romanization,
        // not a line of the song.
        val separator = entries.indexOfFirst {
            (it.long("startTimeMs") ?: 0L) >= SEPARATOR_MS
        }
        val lead = if (separator >= 0) entries.subList(0, separator) else entries

        // A catalogue that serves plain text stamps every line at zero and
        // says so; the plain text is the better answer than a line of zeroes.
        val synced = root.string("syncType")?.equals("LINE_SYNCED", ignoreCase = true) == true
        if (synced) {
            val lines = lead.mapNotNull { entry ->
                lineOf(
                    words = entry.string("words").orEmpty(),
                    startMs = entry.long("startTimeMs") ?: 0L,
                    endMs = entry.long("endTimeMs") ?: 0L,
                )
            }
            if (lines.isNotEmpty()) return lines
        }

        if (root.bool("instrumental") == true) return null
        val plain = root.string("plainLyrics").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (plain.isEmpty()) return null
        // Unsynced: every line at zero, which is how the panel is told there
        // is nothing to follow.
        return plain.map { LyricLine(0L, it) }
    }

    /**
     * One line of the extension's answer.
     *
     * The line's own end is kept — it is the only thing that says when the
     * singing stops — and the answering vocal, if the line carries one, is
     * split off with its own clock rather than run into the lead's text.
     */
    private fun lineOf(words: String, startMs: Long, endMs: Long): LyricLine? {
        if (words.isBlank()) return null
        val backgroundAt = words.indexOf(BACKGROUND)
        val leadText = if (backgroundAt >= 0) words.substring(0, backgroundAt) else words
        val backingText = if (backgroundAt >= 0) {
            words.substring(backgroundAt + BACKGROUND.length).removeSuffix("]")
        } else {
            ""
        }

        val timed = wordsFromRuns(runsOf(leadText, endMs), spacingIsExplicit = true)
        val backing = if (backingText.isBlank()) {
            null
        } else {
            val bgWords = wordsFromRuns(runsOf(backingText, endMs), spacingIsExplicit = true)
            if (bgWords.isEmpty()) {
                null
            } else {
                LyricLine(
                    timeMs = bgWords.first().startMs,
                    text = bgWords.joinToString(" ") { it.text },
                    words = bgWords,
                    sungUntilMs = endMs.takeIf { it > bgWords.last().endMs },
                )
            }
        }

        if (timed.isEmpty()) {
            // A line with no word stamps: timed, but with nothing to sweep.
            val plain = leadText.trim()
            if (plain.isEmpty()) return null
            return LyricLine(
                timeMs = startMs,
                text = plain,
                sungUntilMs = endMs.takeIf { it > startMs },
                background = backing,
            )
        }
        return LyricLine(
            // The line's own stamp, or the first word's — whichever is earlier,
            // because a line may open before its first word is sung.
            timeMs = minOf(startMs, timed.first().startMs),
            text = timed.joinToString(" ") { it.text },
            words = timed,
            sungUntilMs = endMs.takeIf { it > timed.last().endMs },
            background = backing,
        )
    }

    /**
     * The timed pieces [content] is written as.
     *
     * Each stamp opens a piece, and the piece runs until the next stamp — the
     * empty stamp the writer leaves after the last word closes that word
     * rather than starting a new one.
     *
     * The pieces are read with the spacing taken as stated: the writer says
     * where a word ends by leaving the space at the end of it, and the
     * absence of one says the word carries on. That is the only reading that
     * holds for a line that is one word — "Hel" and "lo" are one word split
     * in two, and a line that never says where a word ends says that it has
     * not ended.
     */
    private fun runsOf(content: String, lineEndMs: Long): List<TimedRun> {
        val matches = STAMP.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        val times = matches.map { stamp(it) }
        return matches.mapIndexed { index, match ->
            val start = times[index]
            val end = times.getOrNull(index + 1) ?: lineEndMs
            TimedRun(start, maxOf(start, end), match.groupValues[4])
        }
    }

    /** `<mm:ss.xx>` in milliseconds. Two fraction digits are centiseconds. */
    private fun stamp(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            3 -> fraction.toLongOrNull() ?: 0L
            else -> (fraction.toLongOrNull() ?: 0L) * 10
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }

    // ── The answer, read ───────────────────────────────────────────────────

    /**
     * A field, by name, however it was spelled.
     *
     * The extension writes `startTimeMs`; the Go host that established the
     * contract read `starttimems`, and a package written against either
     * answers in its own spelling. Case is the only difference between them,
     * so it is the only difference looked for.
     */
    private fun JsonObject.value(name: String): JsonElement? =
        this[name] ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun JsonObject.objects(name: String): List<JsonObject> =
        (value(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.long(name: String): Long? =
        (value(name) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.string(name: String): String? =
        (value(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(name: String): Boolean? =
        (value(name) as? JsonPrimitive)?.booleanOrNull
}
