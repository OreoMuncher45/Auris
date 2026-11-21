package com.music.bitchord.playback.smart

/**
 * Keeps Automix analysis on the canonical YouTube Opus rendition.
 *
 * Playback may deliberately use a source substitute (including JioSaavn), but
 * its cache entry is not interchangeable with the YouTube recording that the
 * analysis fetcher owns. The plain video id is the cache key pinned to that
 * YouTube copy; suffixed keys (`#alt`, `#hifi`, ...) belong to substitutes or
 * upgrades.
 */
object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    fun isCanonicalYouTubeRendition(videoId: String?, cacheKey: String): Boolean =
        videoId == null || cacheKey == videoId || cacheKey == analysisCacheKey(videoId)

    /**
     * The key Auris's cache files the analyser's own Opus copy under: a sibling
     * of the player's entry rather than the plain id itself, so the analysis
     * fetch and playback never write one file between them.
     */
    fun analysisCacheKey(videoId: String): String = "$videoId$ANALYSIS_KEY_SUFFIX"

    private const val ANALYSIS_KEY_SUFFIX = "#analysis"

    /**
     * A private playback URI for analysis-only reads.
     *
     * A normal `watch?v=` URI is allowed to reuse the source race already
     * pinned for audible playback. That is exactly the wrong thing for
     * Automix: its independently cached copy must always be YouTube Opus,
     * even while playback is JioSaavn or a lossless upgrade.
     */
    fun opusUri(videoId: String): String = "bitchord://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"

    fun requestsYouTubeOpus(marker: String?): Boolean = marker == "1"
}
