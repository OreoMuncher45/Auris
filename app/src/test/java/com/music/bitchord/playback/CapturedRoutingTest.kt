package com.music.bitchord.playback

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import java.io.IOException
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The resolver's one guard for reads with a captured routing: an analysis
 * round's opens and a read-ahead pass's ranges are served the rendition and
 * tier they were admitted under, or refused.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CapturedRoutingTest {

    @After fun restore() {
        AutomixAnalysisSource.rendition = { null }
    }

    private fun refused(uri: String, heavyNow: Boolean): Boolean = try {
        checkCapturedRouting(Uri.parse(uri)) { heavyNow }
        false
    } catch (expected: IOException) {
        true
    }

    private val row = "bitchord://source?s=addon1&t=track9&n=Song"

    @Test
    fun aRoundAdmittedLightIsRefusedOnceTheTierTurnsLossy() {
        // Admitted at lossless: the head probe read the capped copy.
        val open = AutomixAnalysisSource.analysisRead(row, light = true, rendition = null)
        if (refused(open, heavyNow = true)) fail("refused while the tier still matched")
        // Quality switched to lossy before the next open: the playback stream
        // would be another encoding, so the open is refused, not served.
        if (!refused(open, heavyNow = false)) fail("served the next open across a tier change")
    }

    @Test
    fun aRoundAdmittedAtThePlaybackTierIsRefusedOnceTheTierTurnsLossless() {
        val open = AutomixAnalysisSource.analysisRead(row, light = false, rendition = null)
        if (refused(open, heavyNow = false)) fail("refused while the tier still matched")
        if (!refused(open, heavyNow = true)) fail("served the next open across a tier change")
    }

    @Test
    fun aReadAheadRangeIsRefusedOnceTheRenditionChanged() {
        val range = AutomixAnalysisSource.withRendition("bitchord://watch?v=abcdefghijk", null)
        if (refused(range, heavyNow = false)) fail("refused under its own rendition")
        AutomixAnalysisSource.rendition = { "premium" }
        if (!refused(range, heavyNow = false)) fail("served Premium into a pass keyed for the ordinary rendition")
    }

    @Test
    fun aReadWithNoCapturedRoutingIsNeverRefused() {
        AutomixAnalysisSource.rendition = { "premium" }
        if (refused("bitchord://watch?v=abcdefghijk", heavyNow = true)) fail("refused ordinary playback")
    }
}
