package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Test

/**
 * Where Automix's analysis bytes come from, and what may not stop them.
 *
 * Two rules, and both of them were broken in a way that made the Automix
 * panel look broken rather than make it look wrong:
 *
 *  - **The analysis copy is its own.** It is fetched under a marker playback
 *    never sends, so the bytes being measured are never the bytes being
 *    heard, and a source substitute cannot change what the measurement means.
 *  - **YouTube switched off does not stop the measurement.** The switch is a
 *    promise about what reaches the speaker. The analysis copy never reaches
 *    the speaker, and the listener's own Automix switch is what stops the work
 *    being queued. If the source switch also stopped the analysis, every
 *    panel opened on "Waiting for analysis" for exactly the listener who had
 *    turned YouTube off.
 */
class AutomixAnalysisSourceTest {

    private val before = SourceRegistry.configs.value

    @After
    fun restore() {
        SourceRegistry.configs.value = before
    }

    @Test
    fun `analysis URI asks the resolver to bypass playback substitutions`() {
        assertTrue(AutomixAnalysisSource.opusUri("video").contains("v=video&automix_opus=1"))
        assertTrue(AutomixAnalysisSource.requestsYouTubeOpus("1"))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus(null))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus("0"))
    }

    @Test
    fun `the analysis copy is marked and the audible one is not`() {
        val analysis = AutomixAnalysisSource.opusUri("video")
        val audible = "bitchord://watch?v=video"
        // The marker is what separates the two copies: it is on the analysis
        // URI and off the audible one, and the resolver reads it off the
        // query rather than off the address.
        assertTrue(AutomixAnalysisSource.requestsYouTubeOpus("1"))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus(null))
        assertTrue(analysis.contains(AutomixAnalysisSource.OPUS_QUERY_PARAMETER))
        assertFalse(audible.contains(AutomixAnalysisSource.OPUS_QUERY_PARAMETER))
        // Both name the same recording. What differs is only the marker, so
        // the two copies are of one thing and can be compared.
        assertTrue(analysis.contains("v=video"))
        assertTrue(audible.contains("v=video"))
    }

    @Test
    fun `youtube switched off still leaves the analysis reachable`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.YOUTUBE, enabled = false),
        )
        // What the listener hears: nothing from YouTube. The switch means
        // exactly that, and this is the promise it makes.
        assertFalse(SourceRegistry.youtubeEnabled())
        // What the analysis may ask for: still YouTube's own audio, because
        // it is fetched under the analysis marker and never reaches the
        // speaker. The panel has to be able to answer "waiting" with a
        // measurement, and the listener who turned YouTube off is the one
        // whose panel was worst off before this.
        assertTrue(SourceRegistry.youtubeAnalysisEnabled())
    }

    @Test
    fun `no youtube row means no analysis either`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.ADDON),
        )
        // Reachability, not preference: with no YouTube row there is no
        // YouTube to ask, and the analysis has nothing of its own to fall
        // back on.
        assertFalse(SourceRegistry.youtubeAnalysisEnabled())
        assertFalse(SourceRegistry.youtubeEnabled())
    }

    @Test
    fun `an addon-served track can still be fetched for analysis`() {
        // The app's own addresses are how a track served by an addon is
        // reached. They used to be refused here, which meant the one branch
        // that fetched bytes for an addon-served track could never fetch any
        // and every such track sat on "Waiting for analysis".
        assertTrue(AutomixAnalysisSource.schemeIsFetchable("bitchord"))
        assertTrue(AutomixAnalysisSource.schemeIsFetchable("http"))
        assertTrue(AutomixAnalysisSource.schemeIsFetchable("https"))
        // A local file has no stream to fetch and is already whole; a
        // platform document is handed over rather than pulled from.
        assertFalse(AutomixAnalysisSource.schemeIsFetchable("file"))
        assertFalse(AutomixAnalysisSource.schemeIsFetchable("content"))
    }

    @Test
    fun `second quality upgrade gets a new rendition marker`() {
        val jio = QualityUpgrade.upgradedUri("bitchord://watch?v=video")
        val lossless = QualityUpgrade.upgradedUri(jio)
        assertTrue(jio.endsWith("q=hifi"))
        assertTrue(lossless.endsWith("q=hifi-2"))
    }
}
