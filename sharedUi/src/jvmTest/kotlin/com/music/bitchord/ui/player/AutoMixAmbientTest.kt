package com.music.bitchord.ui.player

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoMixAmbientTest {
    private var progress = 0f
    private val mix = AutoMixAmbientMix(1L, "next") { progress }

    @Test
    fun theLayerFollowsTheRevealThenHoldsWhereItStoppedUntilItSettles() = runBlocking {
        val layer = AutoMixAmbientLayer()
        layer.begin()
        progress = 0.3f
        assertEquals(0.3f, layer.alpha(mix))
        progress = 0.6f
        assertEquals(0.6f, layer.alpha(mix))
        // The mix has let go and the settle has not started: no frame of a stale value.
        assertEquals(0.6f, layer.alpha(null))
        layer.settleTo(1f, fullMillis = 900, reduceAnimation = true)
        assertEquals(1f, layer.alpha(null))
    }

    @Test
    fun aNewMixStartsTheLayerFromNothing() = runBlocking {
        val layer = AutoMixAmbientLayer()
        layer.begin()
        progress = 0.8f
        layer.alpha(mix)
        layer.settleTo(0f, fullMillis = 900, reduceAnimation = true)
        assertEquals(0f, layer.alpha(null))
        layer.begin()
        assertEquals(0f, layer.lastShown)
        progress = 0.1f
        assertEquals(0.1f, layer.alpha(mix))
    }
}
