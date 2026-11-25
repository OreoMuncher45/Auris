package com.music.bitchord.ui.player

import coil3.Image
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.AutoMixArtworkWindow
import com.music.bitchord.playback.AutoMixVisualPhase
import com.music.bitchord.playback.AutoMixVisualState
import com.music.bitchord.playback.AutoMixVisualTrack
import org.jetbrains.skia.Canvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ported from BitChord, without its Canvas cases. */
class AutoMixCoverTest {
    private val outgoing = QueueEntryIdentity("a", null, 0)
    private val incoming = QueueEntryIdentity("b", null, 0)
    private val visual = AutoMixVisualState(
        1L, AutoMixVisualTrack(outgoing, 0, null), AutoMixVisualTrack(incoming, 1, null),
        phase = AutoMixVisualPhase.MIXING, swapSeconds = 0.5,
        artworkWindow = AutoMixArtworkWindow(1.0, 3.0), audioSeconds = 0.5,
    )

    @Test
    fun missingThumbnailAtOverlapUsesFallbackAndCannotJoinLater() {
        val base = cover()
        var prepared: DecodedAutoMixCover? = null
        val state = AutoMixCoverState({ base }, { prepared })
        state.bind(incoming, null, visual)
        state.update(visual)
        assertNull(state.framesFor(incoming))
        prepared = cover()
        state.update(visual.copy(audioSeconds = 0.75))
        state.update(visual.copy(audioSeconds = 1.5, artworkProgress = 0.25f))
        assertNull(state.framesFor(incoming))
    }

    @Test
    fun preparedThumbnailFollowsTheClockAndPromotesOnCompletion() {
        val base = cover()
        val next = cover()
        val state = AutoMixCoverState({ base }, { next })
        state.bind(incoming, null, visual)
        state.update(visual)
        assertSame(base, state.framesFor(incoming)?.base)
        assertSame(next, state.framesFor(incoming)?.incoming)
        assertEquals(0f, state.framesFor(incoming)?.progress())
        state.update(visual.copy(audioSeconds = 1.5, artworkProgress = 0.25f))
        val frames = assertNotNull(state.framesFor(incoming))
        assertSame(next, frames.incoming)
        assertEquals(0.25f, frames.progress())
        state.update(visual.copy(phase = AutoMixVisualPhase.COMPLETED))
        assertSame(next, state.framesFor(incoming)?.base)
        assertNull(state.framesFor(incoming)?.incoming)
    }

    @Test
    fun missingDeadlineOrCancellationCannotStartTheSameGenerationLater() {
        val base = cover()
        var prepared: DecodedAutoMixCover? = null
        val state = AutoMixCoverState({ base }, { prepared })
        state.bind(outgoing, null, visual)
        state.update(visual)
        state.update(visual.copy(audioSeconds = 1.1, artworkProgress = 0.05f))
        prepared = cover()
        state.update(visual.copy(audioSeconds = 1.2, artworkProgress = 0.1f))
        assertNull(state.framesFor(outgoing))
        state.update(visual.copy(phase = AutoMixVisualPhase.CANCELLED))
        state.update(visual.copy(audioSeconds = 1.5, artworkProgress = 0.25f))
        assertNull(state.framesFor(outgoing))
    }

    @Test
    fun alreadyPromotedPixelsSurviveMetadataArrivingBeforeTheNextCollectorTick() {
        val base = cover()
        val next = cover()
        val last = cover()
        val state = AutoMixCoverState({ if (it.generation == 1L) base else null }, { if (it == 1L) next else last })
        state.bind(incoming, null, visual)
        state.update(visual)
        state.update(visual.copy(phase = AutoMixVisualPhase.COMPLETED))
        val reverse = visual.copy(generation = 2L, outgoing = visual.incoming, incoming = visual.outgoing)
        state.bind(outgoing, null, reverse)
        state.update(reverse)
        val frames = assertNotNull(state.framesFor(outgoing))
        assertSame(next, frames.base)
        assertSame(last, frames.incoming)
        assertEquals(2L, frames.generation)
    }

    @Test
    fun artworkPreferencesAreIndependentUnderTheAutoMixParent() {
        val defaults = AutoMixArtworkPreferences()
        // Both artwork transitions are opt-in.
        assertFalse(defaults.gate(automix = true).allows(1L))
        assertFalse(defaults.gate(automix = true, mini = true).allows(1L))
        for (autoMix in listOf(false, true)) for (art in listOf(false, true)) for (mini in listOf(false, true)) {
            val preferences = defaults.copy(artTransition = art, miniPlayerTransition = mini)
            assertEquals(autoMix && art, preferences.gate(automix = autoMix).allows(1L))
            assertEquals(autoMix && mini, preferences.gate(automix = autoMix, mini = true).allows(1L))
            assertEquals(mini, preferences.miniPlayerTransition)
        }
    }

    @Test
    fun disablingFullArtDoesNotCancelTheMiniPlayersCurrentGeneration() {
        val base = cover()
        val next = cover()
        val mini = AutoMixCoverState({ base }, { next })
        mini.bind(incoming, null, visual)
        mini.update(visual)
        val disabled = AutoMixArtworkPreferences(artTransition = false, miniPlayerTransition = true, skipArtworkThrough = visual.generation)
        assertEquals(false, disabled.allows(automix = true, generation = visual.generation))
        val miniGate = disabled.gate(automix = true, mini = true)
        mini.update(visual, miniGate)
        assertSame(next, mini.framesFor(incoming, miniGate)?.incoming)
        val reenabled = disabled.copy(artTransition = true)
        assertEquals(false, reenabled.allows(automix = true, generation = visual.generation))
        assertTrue(reenabled.allows(automix = true, generation = visual.generation, mini = true))
    }

    @Test
    fun miniOnlyCancellationSettlesSelectedArtworkWithoutReplayingTheMix() {
        val base = cover()
        val next = cover()
        val full = AutoMixCoverState({ base }, { next })
        val mini = AutoMixCoverState({ base }, { next })
        for (surface in listOf(full, mini)) {
            surface.bind(incoming, null, visual)
            surface.update(visual)
            assertSame(next, surface.framesFor(incoming)?.incoming)
        }
        val disabled = AutoMixArtworkPreferences(artTransition = true, miniPlayerTransition = false, skipMiniThrough = visual.generation)
        val miniGate = disabled.gate(automix = true, mini = true)
        assertSame(next, mini.framesFor(incoming, miniGate)?.base)
        assertNull(mini.framesFor(incoming, miniGate)?.incoming)
        mini.update(visual, miniGate)
        val enabled = disabled.copy(miniPlayerTransition = true)
        mini.update(visual, enabled.gate(automix = true, mini = true))
        assertNull(mini.framesFor(incoming)?.incoming)
        assertTrue(enabled.gate(automix = true, mini = true).allows(visual.generation + 1))
        assertSame(next, full.framesFor(incoming, enabled.gate(automix = true))?.incoming)
    }

    @Test
    fun aCoverHeldByTheRevealTellsTheSleeveNotToSlide() {
        val state = AutoMixCoverState({ cover() }, { cover() })
        state.bind(outgoing, null, visual)
        assertFalse(state.holdsCover())
        state.update(visual)
        assertTrue(state.holdsCover())
        state.bind(incoming, null, visual)
        assertTrue(state.holdsCover())
        state.update(visual.copy(phase = AutoMixVisualPhase.CANCELLED))
        // Settled onto the incoming cover without a second reveal.
        assertNull(state.framesFor(incoming)?.incoming)
    }

    @Test
    fun aGateThatSkipsTheGenerationLeavesTheArtworkAlone() {
        val state = AutoMixCoverState({ cover() }, { cover() })
        val skipped = AutoMixArtworkGate(enabled = true, skipThrough = visual.generation)
        state.bind(outgoing, null, visual)
        state.update(visual, skipped)
        assertNull(state.framesFor(outgoing, skipped))
        assertFalse(state.holdsCover())
    }

    @Test
    fun theBackdropsFollowTheCoversRevealAndLetGoWithIt() {
        val state = AutoMixCoverState({ cover() }, { cover() }, ambientUrl = { "next" })
        state.bind(outgoing, null, visual)
        state.update(visual)
        val mix = assertNotNull(state.ambientFor(outgoing))
        assertEquals("next", mix.incomingUrl)
        assertEquals(0f, mix.progress())
        // The same instance on both sides of the track change, for effects keyed on it.
        state.bind(incoming, null, visual)
        assertSame(mix, state.ambientFor(incoming))
        // Another song is none of this mix's business.
        assertNull(state.ambientFor(QueueEntryIdentity("c", null, 0)))
        // The backdrops read the cover's own eased progress.
        state.update(visual.copy(audioSeconds = 2.0, artworkProgress = 0.5f))
        assertEquals(com.music.bitchord.playback.AutoMixArtworkTuning.revealProgress(0.5f), mix.progress())
        // Promoted with the cover at the window's end.
        state.update(visual.copy(audioSeconds = 3.0, artworkProgress = 1f))
        assertNull(state.ambientFor(incoming))
    }

    @Test
    fun theBackdropsLetGoOnCancellationAndOnAGateThatSkipsTheMix() {
        val state = AutoMixCoverState({ cover() }, { cover() }, ambientUrl = { "next" })
        state.bind(outgoing, null, visual)
        state.update(visual)
        assertNotNull(state.ambientFor(outgoing))
        assertNull(state.ambientFor(outgoing, AutoMixArtworkGate(enabled = true, skipThrough = visual.generation)))
        state.update(visual.copy(phase = AutoMixVisualPhase.CANCELLED))
        assertNull(state.ambientFor(outgoing))
    }

    @Test
    fun noPreparedBackdropMeansTheCoverRevealsAlone() {
        val state = AutoMixCoverState({ cover() }, { cover() }, ambientUrl = { null })
        state.bind(outgoing, null, visual)
        state.update(visual)
        assertNotNull(state.framesFor(outgoing)?.incoming)
        assertNull(state.ambientFor(outgoing))
    }

    private fun cover() = DecodedAutoMixCover(object : Image {
        override val width = 1
        override val height = 1
        override val size = 4L
        override val shareable = true
        override fun draw(canvas: Canvas) = error("This test exercises cover ownership, not rendering")
    }, null)
}
