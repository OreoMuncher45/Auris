package com.music.bitchord.playback.smart

/**
 * The analysis-only request for YouTube's own Opus copy of a recording.
 *
 * Playback may deliberately use a source substitute (including JioSaavn) or a
 * lossless upgrade, and those cache entries are not interchangeable with the
 * one this marker writes: asking for YouTube Opus explicitly keeps the bytes it
 * fetches separate from whatever the listener is hearing.
 *
 * The marker is not a requirement, though. Analysis accepts any complete copy
 * of the recording — a substitute's `#alt`, an upgrade's `#hifi`, a source
 * track's own entry — as long as its duration agrees; see `TrackAnalyzer`.
 * This URI names the lightest such copy when YouTube is available, nothing
 * more.
 */
object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    /**
     * A private playback URI for analysis-only reads.
     *
     * A normal `watch?v=` URI is allowed to reuse the source race already
     * pinned for audible playback. That is exactly the wrong thing for the
     * explicit Opus request: its independently cached copy must be YouTube
     * Opus, even while playback is JioSaavn or a lossless upgrade.
     */
    fun opusUri(videoId: String): String = "bitchord://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"

    fun requestsYouTubeOpus(marker: String?): Boolean = marker == "1"
}
