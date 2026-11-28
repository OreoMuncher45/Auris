package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A stream URL resolved under one Premium audio setting is never served after
 * the setting changes, however the resolve and the change interleave.
 */
class StreamUrlGenerationTest {

    @Test
    fun aUrlResolvedUnderTheCurrentSettingsIsServed() {
        val generation = StreamResolver.currentGeneration()
        StreamResolver.remember("gen-a", "https://example/a", generation)
        assertEquals("https://example/a", StreamResolver.rememberedUrl("gen-a"))
    }

    @Test
    fun aUrlRememberedBeforeASettingsChangeIsNotServedAfterIt() {
        val generation = StreamResolver.currentGeneration()
        StreamResolver.remember("gen-b", "https://example/b", generation)
        StreamResolver.forgetResolved()
        assertNull(StreamResolver.rememberedUrl("gen-b"))
    }

    @Test
    fun aResolveThatStartedBeforeTheChangeAndFinishesAfterItIsNotKept() {
        val generation = StreamResolver.currentGeneration()
        // The setting changes while the resolve is in flight.
        StreamResolver.forgetResolved()
        StreamResolver.remember("gen-c", "https://example/c", generation)
        assertNull(StreamResolver.rememberedUrl("gen-c"))
    }
}
