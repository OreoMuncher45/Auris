package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two curves that carry the title from the player into the mini player.
 *
 * The drawing is Compose and out of reach here, but the curves are what went
 * wrong last time: the title was faded with the player's own fade, which is
 * zero for the whole of the trip, so the text vanished and popped back at the
 * end. These are the numbers that say the text is there for the whole journey
 * and hands over only once it is home.
 */
class CreditsHandoverTest {

    @Test
    fun theTitleIsNeverMissingWhileTheSheetIsMoving() {
        // The whole trip, except the very last moment, when it is dissolving
        // into the bar's own title.
        assertEquals(1f, creditsHandoverAlpha(0f), 1e-6f)
        assertEquals(1f, creditsHandoverAlpha(0.25f), 1e-6f)
        assertEquals(1f, creditsHandoverAlpha(0.5f), 1e-6f)
        assertEquals(1f, creditsHandoverAlpha(0.85f), 1e-6f)
    }

    @Test
    fun theTitleHandsOverAtTheEndAndNotBefore() {
        assertTrue(
            creditsHandoverAlpha(0.9f) < 0.9f,
            "the dissolve should have started by ninety per cent of the trip",
        )
        assertTrue(
            creditsHandoverAlpha(0.97f) < 0.15f,
            "and it should be nearly handed over by the end of it",
        )
        assertEquals(0f, creditsHandoverAlpha(1f), 1e-6f)
    }

    @Test
    fun theTitleIsHomeBeforeItHandsOver() {
        // It has to arrive before it starts to fade, or the two titles are in
        // different places while one of them is dissolving into the other.
        assertEquals(1f, creditsTravel(0.85f), 1e-6f)
        assertEquals(0f, creditsTravel(0f), 1e-6f)
    }

    @Test
    fun theTitleTravelsAtAnEvenPaceRatherThanJumping() {
        val quarter = creditsTravel(0.25f)
        val half = creditsTravel(0.5f)
        val threeQuarter = creditsTravel(0.75f)
        assertTrue(quarter in 0.05f..0.35f, "it should have started moving")
        assertTrue(half in 0.35f..0.75f, "it should be under way")
        assertTrue(threeQuarter in 0.75f..1f, "it should be most of the way home")
    }
}
