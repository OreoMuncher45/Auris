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

    /**
     * Which of YouTube's renditions the resolver serves right now, as a short
     * tag, or null for the ordinary one. A rendition is a different encoding of
     * the recording (Premium's higher Opus rung, for one), so its bytes and its
     * analysis are kept under keys of their own: one entry never holds two
     * renditions, a switch never re-analyses a rendition already measured, and
     * a rendition never reads another's analysis. Set by the app.
     */
    var rendition: () -> String? = { null }

    /** The player's own entry for [videoId] under [rendition]. */
    fun playbackKey(videoId: String, rendition: String? = this.rendition()): String =
        if (rendition == null) videoId else "$videoId#$rendition"

    /**
     * [trackId] scoped to [rendition]: what the analyser files a result under,
     * so each rendition keeps its own.
     */
    fun scopedTrackId(trackId: String, rendition: String? = this.rendition()): String =
        if (rendition == null) trackId else "$trackId#$rendition"

    fun isCanonicalYouTubeRendition(
        videoId: String?,
        cacheKey: String,
        rendition: String? = this.rendition(),
    ): Boolean =
        videoId == null || cacheKey == playbackKey(videoId, rendition) || cacheKey == analysisCacheKey(videoId, rendition)

    /**
     * The key Auris's cache files the analyser's own Opus copy under: a sibling
     * of the player's entry rather than the plain id itself, so the analysis
     * fetch and playback never write one file between them.
     */
    fun analysisCacheKey(videoId: String, rendition: String? = this.rendition()): String =
        "$videoId$ANALYSIS_KEY_SUFFIX" + (rendition?.let { "-$it" } ?: "")

    private const val ANALYSIS_KEY_SUFFIX = "#analysis"

    /**
     * Where the analysis round files the copy it falls back on when YouTube's
     * Opus stream will not come: whatever the plain `watch?v=` address
     * resolves to, which can be a substitute source.
     *
     * Never [analysisCacheKey]. That key names the YouTube recording, and
     * [isCanonicalYouTubeRendition] trusts it by name; a substitute filed under
     * it was taken for the canonical copy, and a retry counted the substitute's
     * partial bytes against the Opus copy's size. Ends in the cache's `#alt2`
     * substitute suffix, so everything that tells substitutes apart by key
     * (the version aligner among them) reads it as one.
     */
    fun analysisSubstituteKey(videoId: String, rendition: String? = this.rendition()): String =
        "${analysisCacheKey(videoId, rendition)}$SUBSTITUTE_KEY_SUFFIX"

    /**
     * Whether the analyser may read [cacheKey] at all: a canonical rendition,
     * or the analysis round's substitute copy. The substitute is a different
     * source and can be a different cut, so a caller must check its length
     * against the track's before using it, and prefer a canonical copy.
     */
    fun isAnalysisRendition(videoId: String?, cacheKey: String): Boolean =
        isCanonicalYouTubeRendition(videoId, cacheKey) || isAnalysisSubstitute(videoId, cacheKey)

    fun isAnalysisSubstitute(videoId: String?, cacheKey: String, rendition: String? = this.rendition()): Boolean =
        videoId != null && cacheKey == analysisSubstituteKey(videoId, rendition)

    /**
     * The video id behind an analysis copy's key ([analysisCacheKey] or
     * [analysisSubstituteKey]), or null for any other key. What lets a restored
     * analysis of a source-backed track find its matched YouTube copy again
     * without a fresh catalogue search.
     */
    fun videoIdOfAnalysisKey(cacheKey: String): String? {
        val base = cacheKey.removeSuffix(SUBSTITUTE_KEY_SUFFIX)
        val at = base.lastIndexOf(ANALYSIS_KEY_SUFFIX)
        if (at <= 0) return null
        // Nothing after the suffix but an optional "-<rendition>".
        val rest = base.substring(at + ANALYSIS_KEY_SUFFIX.length)
        if (rest.isNotEmpty() && (!rest.startsWith("-") || rest.length == 1 || '#' in rest)) return null
        return base.substring(0, at)
    }

    private const val SUBSTITUTE_KEY_SUFFIX = "#alt2"

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
