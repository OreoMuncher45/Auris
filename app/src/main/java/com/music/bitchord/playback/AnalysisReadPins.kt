package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The stream an analysis round's light read resolved to, per row (a Spotify
 * row's id, or a source track's `<source>|<track>`), so every open in one
 * round reads the same stream.
 *
 * The light read (see [com.music.bitchord.playback.smart.AutomixAnalysisSource.analysisRead])
 * is kept off the player's pin, so without this each open of the round (the
 * measurement, the head, the grown head, the tail) resolved again, and a later
 * resolve that answered with another encoding appended its bytes to the entry
 * holding the first, and recorded its own length on it. A round's first open
 * resolves and pins; the next round starts by dropping the pin, so a retry
 * asks again rather than reusing a URL that may have expired.
 */
internal object AnalysisReadPins {
    private val pins = ConcurrentHashMap<String, SourceStream>()

    /** [id]'s stream for this round: the pinned one, or [resolve]'s answer, pinned. */
    inline fun streamFor(id: String, resolve: () -> SourceStream?): SourceStream? =
        get(id) ?: resolve()?.also { put(id, it) }

    /**
     * [id]'s stream for this round: the pinned one, else the capped answer,
     * else [fallback] (the player's own copy, when nothing answers at the
     * capped rate). Whichever stream the round reads is the one pinned, the
     * fallback included, so a later open cannot land on the other.
     */
    inline fun streamFor(
        id: String,
        capped: () -> SourceStream?,
        fallback: () -> SourceStream?,
    ): SourceStream? = get(id) ?: (capped() ?: fallback())?.also { put(id, it) }

    /**
     * [streamFor], for a light read that must not be served any other way:
     * neither answering fails the read. The ordinary resolve it would otherwise
     * fall through to (an upgrade, another resolution) is not pinned for the
     * round, and a later open could then read a different stream and append it
     * to the bytes already written. Failing ends the round short; its retry
     * resolves afresh.
     */
    inline fun lightRead(
        id: String,
        capped: () -> SourceStream?,
        fallback: () -> SourceStream?,
    ): SourceStream = streamFor(id, capped, fallback)
        ?: throw java.io.IOException("no copy answered the analysis read for $id; the round retries")

    fun get(id: String): SourceStream? = pins[id]

    fun put(id: String, stream: SourceStream) {
        pins[id] = stream
    }

    /** Called as a round starts, and when the row's bytes are dropped. */
    fun forget(id: String) {
        pins.remove(id)
    }
}
