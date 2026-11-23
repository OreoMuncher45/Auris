package com.music.bitchord.playback.loopmix

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopArmGateTest {
    private fun refusal(
        bitPerfect: Boolean = false,
        automixOn: Boolean = true,
        speed: Float = 1f,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        next: Boolean = true,
        skipSilence: Boolean = false,
    ) = LoopArmGate.refusal(bitPerfect, automixOn, speed, repeatMode, next, skipSilence)

    @Test
    fun armedLoopProceedsWhenNothingChanged() {
        assertNull(refusal())
        assertNull(refusal(repeatMode = Player.REPEAT_MODE_ALL))
    }

    @Test
    fun speedChangedWhileArmedBails() {
        assertEquals("playback speed is not 1x", refusal(speed = 1.25f))
    }

    @Test
    fun repeatOneBails() {
        assertEquals("repeat one is on", refusal(repeatMode = Player.REPEAT_MODE_ONE))
    }

    @Test
    fun nextEntryChangedBails() {
        assertEquals("the next entry changed", refusal(next = false))
    }

    @Test
    fun skipSilenceSwitchedOnBails() {
        assertEquals("skip silence is on", refusal(skipSilence = true))
    }

    @Test
    fun bitPerfectAndAutomixOffBail() {
        assertEquals("bit-perfect is on", refusal(bitPerfect = true))
        assertEquals("Automix was switched off", refusal(automixOn = false))
    }
}
