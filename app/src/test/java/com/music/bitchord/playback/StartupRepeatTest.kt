package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRepeatTest {

    @Test
    fun `the first value is skipped when nothing changed since startup`() {
        val repeat = StartupRepeat { "shuffle off, track A" }
        assertTrue(repeat.skip("shuffle off, track A"))
    }

    @Test
    fun `the first value is drawn when the inputs changed in between`() {
        val repeat = StartupRepeat { "shuffle off, track A" }
        assertFalse(repeat.skip("shuffle on, track A"))
    }

    @Test
    fun `only the first value can be skipped`() {
        val repeat = StartupRepeat { "shuffle off, track A" }
        assertTrue(repeat.skip("shuffle off, track A"))
        // Back to the startup inputs later on is a real change, and is drawn.
        assertFalse(repeat.skip("shuffle off, track A"))
    }

    @Test
    fun `nothing is skipped before startup has recorded its inputs`() {
        val repeat = StartupRepeat<String> { null }
        assertFalse(repeat.skip("shuffle off, track A"))
    }
}
