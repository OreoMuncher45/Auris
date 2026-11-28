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
internal fun checkCapturedRouting(uri: Uri, heavyTierNow: () -> Boolean = ::heavyPlaybackTier) {
    val renditionMark = uri.getQueryParameter(AutomixAnalysisSource.RENDITION_PARAMETER)
    val readMark = uri.getQueryParameter(AutomixAnalysisSource.ANALYSIS_READ_PARAMETER)
    if (renditionMark == null && readMark == null) return
    if (!AutomixAnalysisSource.routingMatches(renditionMark, readMark, heavyTierNow)) {
        throw java.io.IOException("a read admitted under another rendition or tier; its round retries")
    }
}
