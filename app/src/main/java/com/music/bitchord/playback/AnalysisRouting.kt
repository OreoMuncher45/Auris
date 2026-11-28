package com.music.bitchord.playback

import android.net.Uri
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.smart.AutomixAnalysisSource

// A file of its own: [PlaybackStreamResolver]'s file facade pulls in the stream
// extractors when it loads, and the guard below must load without them.

/** Whether the listener's playback tier is lossless or immersive, which an analysis read is answered lighter than. */
internal fun heavyPlaybackTier(): Boolean = SourceResolver.requestForNow().let { it.wantsLossless || it.wantsAtmos }

/**
 * Refuses a read whose captured routing (see
 * [AutomixAnalysisSource.routingMatches]) no longer matches the settings: a
 * rendition or a playback tier that changed since its analysis round or
 * read-ahead pass began. The refusal fails the open; the round or pass ends
 * short, and its retry is admitted afresh under the current routing.
 */
internal fun checkCapturedRouting(
    uri: Uri,
    heavyTierNow: () -> Boolean = ::heavyPlaybackTier,
    substitutingNow: () -> Boolean = SourceResolver::canSubstituteForYouTube,
) {
    val renditionMark = uri.getQueryParameter(AutomixAnalysisSource.RENDITION_PARAMETER)
    val readMark = uri.getQueryParameter(AutomixAnalysisSource.ANALYSIS_READ_PARAMETER)
    val routeMark = uri.getQueryParameter(ROUTE_PARAMETER)
    if (renditionMark == null && readMark == null && routeMark == null) return
    if (!AutomixAnalysisSource.routingMatches(renditionMark, readMark, heavyTierNow)) {
        throw java.io.IOException("a read admitted under another rendition or tier; its round retries")
    }
    // A read-ahead fill keyed for YouTube's own copy must not be raced to a
    // source that came up meanwhile (or the other way round).
    if (routeMark != null && (routeMark == ROUTE_SUBSTITUTED) != substitutingNow()) {
        throw java.io.IOException("a read-ahead admitted under another route; its pass retries")
    }
}

/**
 * The route a read-ahead pass was admitted under: whether a source ranked
 * above YouTube could serve the track ([ROUTE_SUBSTITUTED]), or YouTube's own
 * copy alone.
 */
internal const val ROUTE_PARAMETER = "automix_route"
internal const val ROUTE_SUBSTITUTED = "sub"
private const val ROUTE_YOUTUBE = "yt"

/** [uri] carrying the read-ahead route it was admitted under. */
internal fun withRoute(uri: String, substituted: Boolean): String =
    if ("$ROUTE_PARAMETER=" in uri) {
        uri
    } else {
        uri + (if ('?' in uri) "&" else "?") + "$ROUTE_PARAMETER=${if (substituted) ROUTE_SUBSTITUTED else ROUTE_YOUTUBE}"
    }
