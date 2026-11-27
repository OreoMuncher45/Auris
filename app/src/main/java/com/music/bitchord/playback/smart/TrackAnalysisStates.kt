package com.music.bitchord.playback.smart

import com.music.bitchord.data.settings.TrackAnalysisState

/** Where one track's analysis stands, for the stats line and the marker's guard. */
internal object TrackAnalysisStates {
    /**
     * Usable first, and a pass in flight *second*. The other order was right
     * up to the point a head-only result started arriving before the
     * whole-track one: a track measured off its opening reads as analysed,
     * then finishes caching, then has the full pass run over it to replace the
     * provisional numbers, and reported "analysing" again throughout. Going
     * backwards from analysed reads as something having broken, when what is
     * happening is a better answer being computed.
     *
     * A [provisional] result is refining even while the whole-track pass waits
     * on bytes rather than runs: it is not the finished answer yet.
     *
     * A recorded-but-unusable ([ready]) result is the analyzer saying it tried,
     * got nothing and will not try again. A track nothing has looked at yet has
     * no status at all, which is the only case that is still merely waiting.
     */
    fun of(usable: Boolean, ready: Boolean, running: Boolean, provisional: Boolean): TrackAnalysisState = when {
        usable -> if (running || provisional) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        running -> TrackAnalysisState.ANALYSING
        ready -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }
}
