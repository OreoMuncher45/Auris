package com.music.bitchord.data.settings

/**
 * Stream bitrate ceiling on the YouTube fallback path — MEDIUM, HIGH and
 * LOSSLESS all mean "whatever the best available Opus format is" there; what
 * actually tells them apart is which other sources are allowed to answer
 * *before* YouTube gets asked. That part is [permits], and the rungs read:
 *
 * - [DOLBY] — the immersive rung: addons and modules are asked for their
 *   Dolby Atmos rendition first, then the whole [LOSSLESS] ladder below it.
 * - [LOSSLESS] — every enabled source asked, for its bit-exact copy where it
 *   has one.
 * - [HIGH] and [MEDIUM] — every enabled source asked, for its best copy. The
 *   difference is what each source is asked *for*: the lossless tier on the
 *   Lossless rung, the best lossy rendition on these two.
 * - [LOW] — YouTube alone, capped at [maxKbps]. 64kbps is below the floor any
 *   configured source can be asked for — the lowest tier they speak is
 *   128kbps — so letting one answer here would spend twice the data the rung
 *   exists to save, for a copy that is no better.
 *
 * [DOLBY] is a *choice*, not a ceiling that refuses anything above it: an
 * immersive mix is lossy E-AC-3, so a track with no Atmos master still plays
 * exactly as a [LOSSLESS] track would. That is also why it sits last in the
 * enum — the picker renders `entries.reversed()`, best first.
 *
 * [hourly] is what the ceiling costs in data over an hour of listening, which
 * is the only part of this a user actually cares about on a metered plan.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download", "29 MB/hr"),
    MEDIUM(Int.MAX_VALUE, "Medium", "Best available · ~171 kbps Opus", "77 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "JioSaavn up to 320kbps, YouTube fallback", "144 MB/hr"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Your addons + JioSaavn, bit-exact where available", "300+ MB/hr"),
    DOLBY(Int.MAX_VALUE, "Dolby Atmos", "Immersive E-AC-3 where held, lossless below it", "~350 MB/hr"),
    ;
}

/** The surface that was last open inside the expanded player. */
enum class LastPlayerScreen {
    MAIN,
    LYRICS,
    QUEUE,
}
