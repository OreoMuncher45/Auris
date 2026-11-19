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

    /**
     * Whether a track's URI has bytes worth fetching for an analysis.
     *
     * A local file has no stream to fetch and is already whole; a content URI
     * is a document handed over by the platform, not an address the cache can
     * pull from with the source's own headers.
     *
     * Everything else is fetchable, and that includes the app's own
     * `bitchord://` addresses — those are how an addon-served track is
     * reached, and refusing them is what left every such track sitting on
     * "Waiting for analysis" for as long as it was queued: the one branch
     * that existed to fetch bytes for an addon-served track could not fetch
     * any.
     */
    fun schemeIsFetchable(scheme: String?): Boolean = scheme != "file" && scheme != "content"
}
