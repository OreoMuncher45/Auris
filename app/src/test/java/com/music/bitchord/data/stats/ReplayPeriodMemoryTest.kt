package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Replay opens on This year the first time, then on whatever was picked last. */
class ReplayPeriodMemoryTest {

    @Test
    fun `nothing stored opens on This year`() {
        assertEquals(ReplayPeriod.THIS_YEAR, ReplayPeriod.named(null))
        assertEquals(ReplayPeriod.THIS_YEAR, ReplayPeriod.DEFAULT)
    }

    @Test
    fun `a stored period is read back`() {
        ReplayPeriod.entries.forEach { assertEquals(it, ReplayPeriod.named(it.name)) }
    }

    @Test
    fun `a name this build doesn't know falls back to This year`() {
        assertEquals(ReplayPeriod.THIS_YEAR, ReplayPeriod.named("LIFETIME"))
        assertEquals(ReplayPeriod.THIS_YEAR, ReplayPeriod.named(""))
    }

    @Test
    fun `the page shows the picked period and the Library cards stay on This year`() {
        assertEquals(ReplayPeriod.THIS_MONTH, ReplayPeriod.shown(pageOpen = true, picked = ReplayPeriod.THIS_MONTH))
        assertEquals(ReplayPeriod.THIS_YEAR, ReplayPeriod.shown(pageOpen = false, picked = ReplayPeriod.THIS_MONTH))
        assertEquals(ReplayPeriod.ALL_TIME, ReplayPeriod.shown(pageOpen = true, picked = ReplayPeriod.ALL_TIME))
    }
}
