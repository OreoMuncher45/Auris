package com.music.bitchord.playback.smart

import com.music.bitchord.data.settings.TrackAnalysisState
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAnalysisStatesTest {
    @Test
    fun aHeadOnlyResultWaitingOnBytesIsNotFinished() {
        // The head pass is done, the whole-track one is not running yet.
        assertEquals(
            TrackAnalysisState.REFINING,
            TrackAnalysisStates.of(usable = true, ready = true, running = false, provisional = true),
        )
    }

    @Test
    fun aWholeTrackResultIsAnalysedAndRefinesOnlyWhileAPassRuns() {
        assertEquals(TrackAnalysisState.ANALYSED, TrackAnalysisStates.of(true, true, running = false, provisional = false))
        assertEquals(TrackAnalysisState.REFINING, TrackAnalysisStates.of(true, true, running = true, provisional = false))
    }

    @Test
    fun noUsableResultIsAnalysingFailedOrWaiting() {
        assertEquals(TrackAnalysisState.ANALYSING, TrackAnalysisStates.of(false, false, running = true, provisional = false))
        assertEquals(TrackAnalysisState.FAILED, TrackAnalysisStates.of(false, true, running = false, provisional = false))
        assertEquals(TrackAnalysisState.WAITING, TrackAnalysisStates.of(false, false, running = false, provisional = false))
    }
}
