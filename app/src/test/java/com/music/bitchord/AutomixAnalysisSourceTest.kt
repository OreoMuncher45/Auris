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
