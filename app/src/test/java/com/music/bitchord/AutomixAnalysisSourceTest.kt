package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.QualityUpgrade
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AutomixAnalysisSourceTest {

    private val before = SourceRegistry.configs.value

    @After
    fun restore() {
        SourceRegistry.configs.value = before
    }

    @Test
    fun `YouTube analysis accepts only its canonical Opus cache key`() {
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video#alt"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video#hifi"))
    }

    @Test
    fun `the analyser's own Opus copy counts as the canonical rendition`() {
        val key = AutomixAnalysisSource.analysisCacheKey("video")
        assertTrue(key == "video#analysis")
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", key))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("other", key))
    }

    @Test
    fun `the analysis round's substitute copy is never taken for the canonical one`() {
        val key = AutomixAnalysisSource.analysisSubstituteKey("video")
        assertFalse(key == AutomixAnalysisSource.analysisCacheKey("video"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", key))
        // Still readable, as a substitute: that is what the fallback is for.
        assertTrue(AutomixAnalysisSource.isAnalysisRendition("video", key))
        assertTrue(AutomixAnalysisSource.isAnalysisSubstitute("video", key))
        assertFalse(AutomixAnalysisSource.isAnalysisSubstitute("video", "video#analysis"))
        assertFalse(AutomixAnalysisSource.isAnalysisRendition("video", "video#hifi"))
        assertFalse(AutomixAnalysisSource.isAnalysisRendition("other", key))
        // And the cache reads it as a substitute too, so the aligner does.
        assertTrue(com.music.bitchord.playback.AudioCache.isAltKey(key))
    }

    @Test
    fun `an analysis copy's key names the video it was fetched for`() {
        assertTrue(AutomixAnalysisSource.videoIdOfAnalysisKey(AutomixAnalysisSource.analysisCacheKey("video")) == "video")
        assertTrue(AutomixAnalysisSource.videoIdOfAnalysisKey(AutomixAnalysisSource.analysisSubstituteKey("video")) == "video")
        assertTrue(AutomixAnalysisSource.videoIdOfAnalysisKey("video") == null)
        assertTrue(AutomixAnalysisSource.videoIdOfAnalysisKey("video#alt2") == null)
        assertTrue(AutomixAnalysisSource.videoIdOfAnalysisKey("#analysis") == null)
    }

    @Test
    fun `youtube switched off still leaves the analysis reachable`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.YOUTUBE, enabled = false),
        )
        assertFalse(SourceRegistry.youtubeEnabled())
        assertTrue(SourceRegistry.youtubeAnalysisEnabled())
    }

    @Test
    fun `no youtube row means no analysis either`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.ADDON),
        )
        assertFalse(SourceRegistry.youtubeAnalysisEnabled())
        assertFalse(SourceRegistry.youtubeEnabled())
    }

    @Test
    fun `analysis URI asks the resolver to bypass playback substitutions`() {
        assertTrue(AutomixAnalysisSource.opusUri("video").contains("v=video&automix_opus=1"))
        assertTrue(AutomixAnalysisSource.requestsYouTubeOpus("1"))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus(null))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus("0"))
    }

    @Test
    fun `second quality upgrade gets a new rendition marker`() {
        val jio = QualityUpgrade.upgradedUri("bitchord://watch?v=video")
        val lossless = QualityUpgrade.upgradedUri(jio)
        assertTrue(jio.endsWith("q=hifi"))
        assertTrue(lossless.endsWith("q=hifi-2"))
    }
}
