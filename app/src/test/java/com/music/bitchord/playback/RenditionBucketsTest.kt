package com.music.bitchord.playback

import com.music.bitchord.playback.smart.AutomixAnalysisSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenditionBucketsTest {

    @After fun restore() {
        AutomixAnalysisSource.rendition = { null }
    }

    @Test
    fun theSubstituteBucketIsKeyedByRenditionAndStillReadsAsOne() {
        assertEquals("vid#alt2", AudioCache.altKeyOf("vid", ""))
        assertEquals("vid#alt2-noatmos", AudioCache.altKeyOf("vid", "-noatmos"))

        AutomixAnalysisSource.rendition = { "premium" }
        // A YouTube winner filed here under Premium never meets the ordinary one.
        assertEquals("vid#premium#alt2", AudioCache.altKeyOf("vid", ""))
        assertEquals("vid#premium#alt2-noatmos", AudioCache.altKeyOf("vid", "-noatmos"))
        assertTrue(AudioCache.isAltKey(AudioCache.altKeyOf("vid", "")))
        assertTrue(AudioCache.isAltKey(AudioCache.altKeyOf("vid", "-noatmos")))
        assertFalse("vid#alt2" in AudioCache.siblingKeysOf("vid"))
    }

    @Test
    fun aSourceBackedRowIsRemovedUnderTheKeyItsRoundWasAdmittedUnder() {
        // The menu passes the row's `src:` id; admission used `<source>|<track>`.
        assertEquals("addon1|track9", AudioCache.admissionKeyOf("src:addon1::track9"))
        // A YouTube row keeps its id.
        assertEquals("abcdefghijk", AudioCache.admissionKeyOf("abcdefghijk"))
    }

    @Test
    fun anOpusReadCarriesTheRenditionItWasKeyedUnder() {
        AutomixAnalysisSource.rendition = { "premium" }
        val asked = AutomixAnalysisSource.opusUri("vid")
        assertTrue(asked.contains("automix_rendition=premium"))
        assertTrue(AutomixAnalysisSource.renditionIsCurrent("premium"))

        // Premium switched off before the read resolved: it is refused.
        AutomixAnalysisSource.rendition = { null }
        assertFalse(AutomixAnalysisSource.renditionIsCurrent("premium"))
        assertTrue(AutomixAnalysisSource.renditionIsCurrent(null))
        assertFalse(AutomixAnalysisSource.opusUri("vid").contains("automix_rendition"))
    }
}
