package com.music.bitchord.data.lyrics

/**
 * Turns parsed lines back into the text of an LRC file.
 *
 * The counterpart to [LrcLib.parseLrc], and the only writer in this package —
 * everything else here reads. It exists for the download path, which embeds
 * what it gets into the saved file's own metadata (see
 * `com.music.bitchord.download.LyricsTag`), and LRC is what that field is read
 * as: whatever a track's lyrics arrived as, they leave here as `[mm:ss.xx]`
 * stamps, which is the one synced-lyric syntax every other player understands.
 *
 * **Word timings are deliberately dropped.** Three of the four providers carry
 * them and the player uses them, but the way to keep them in a file is the
 * "enhanced" A2 extension — `<mm:ss.xx>` runs inside the line — and a reader
 * that doesn't implement it does not ignore those stamps, it *shows* them, so
 * every line reads as angle-bracket noise with words scattered through it.
 * Line stamps degrade to plain text in the same reader; word stamps corrupt it.
 * The file is for other players, so it gets the syntax they all read.
 *
 * Nothing writes the `[ti:]`/`[ar:]`/`[al:]` header tags either. The container's
 * own title, artist and album atoms sit beside this field and already say all
 * three; repeating them here would only be visible in the readers that *don't*
 * parse LRC, which are exactly the ones that can least afford three more lines
 * of markup at the top.
 */
internal fun List<LyricLine>.toLrc(): String {
    if (isEmpty()) return ""
    // Sorted here rather than assumed: the providers each sort their own output,
    // but this is one line of insurance against a file whose stamps run
    // backwards — which every reader renders as lyrics that jump about.
    return sortedBy { it.timeMs }.joinToString("\n") { line -> stamp(line.timeMs) + line.text }
}

/**
 * `[mm:ss.xx]`, in centiseconds — the two-digit fraction, which is the form
 * with the widest support. [LrcLib.parseLrc] reads three digits too, but
 * writing them is a millisecond of precision bought at the cost of the readers
 * that only accept two.
 *
 * Assembled with [padStart] rather than `String.format`, which is not a style
 * preference: `%02d` formats through the default locale, and under a locale
 * with its own numerals — Arabic, Bengali, several Indic ones — that emits
 * digits no LRC parser on earth matches, including this package's own. The
 * output has to be ASCII wherever the device is.
 *
 * Minutes are not wrapped at 99. A stamp that long is a DJ set rather than a
 * song, but truncating it would silently move the line to the wrong place,
 * where overflowing to three digits at worst loses one reader the timing.
 */
private fun stamp(timeMs: Long): String {
    // A negative stamp is not something to sort or write; nothing produces one,
    // and clamping is cheaper than a parser somewhere deciding what "[-1:.." is.
    val total = timeMs.coerceAtLeast(0L)
    val minutes = (total / 60_000).toString().padStart(2, '0')
    val seconds = (total % 60_000 / 1_000).toString().padStart(2, '0')
    val centiseconds = (total % 1_000 / 10).toString().padStart(2, '0')
    return "[$minutes:$seconds.$centiseconds]"
}
