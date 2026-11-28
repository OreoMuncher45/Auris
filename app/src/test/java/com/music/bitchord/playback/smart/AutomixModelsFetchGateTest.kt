package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.AutomixModels.FetchGate
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomixModelsFetchGateTest {

    private fun gate(explicit: Boolean, automixOn: Boolean, offline: Boolean = false, allowed: Boolean = true) =
        AutomixModels.fetchGate(explicit, automixOn, offline) { allowed }

    @Test
    fun `a tracker looking for a model with Automix off does not fetch`() {
        // After Delete, Automix is off; an analysis pass calling path() must not
        // bring the models straight back.
        assertEquals(FetchGate.HOLD, gate(explicit = false, automixOn = false))
    }

    @Test
    fun `Automix on fetches when the connection allows it`() {
        assertEquals(FetchGate.FETCH, gate(explicit = false, automixOn = true))
        assertEquals(FetchGate.HOLD, gate(explicit = false, automixOn = true, allowed = false))
    }

    @Test
    fun `Download or Retry fetches with Automix off, and only offline mode refuses it`() {
        assertEquals(FetchGate.FETCH, gate(explicit = true, automixOn = false, allowed = false))
        assertEquals(FetchGate.OFFLINE, gate(explicit = true, automixOn = false, offline = true))
    }
}
