package com.music.bitchord.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuildForShuffleStateTest {

    @Test
    fun `a queue built while shuffle stays put goes in as built`() = runBlocking {
        var builds = 0
        val result = buildForShuffleState({ false }, { true }) { shuffled -> builds++; "order:$shuffled" }
        assertEquals(false to "order:false", result)
        assertEquals(1, builds)
    }

    @Test
    fun `shuffle switched on during the build rebuilds the queue shuffled`() = runBlocking {
        var shuffle = false
        val result = buildForShuffleState({ shuffle }, { true }) { shuffled ->
            // Android Auto turns shuffle on while the first build runs.
            if (!shuffled) shuffle = true
            "order:$shuffled"
        }
        assertEquals(true to "order:true", result)
    }

    @Test
    fun `a superseded play installs nothing`() = runBlocking {
        assertNull(buildForShuffleState({ false }, { false }) { "order" })
    }

    @Test
    fun `a button's shuffle request holds even if the listener's setting moves`() = runBlocking {
        val shuffleRequest: Boolean? = true
        var listenerSetting = true
        var builds = 0
        val result = buildForShuffleState({ shuffleRequest ?: listenerSetting }, { true }) { shuffled ->
            builds++
            listenerSetting = false // toggled elsewhere mid-build; the button's request still decides
            "order:$shuffled"
        }
        assertEquals(true to "order:true", result)
        assertEquals(1, builds)
    }
}
