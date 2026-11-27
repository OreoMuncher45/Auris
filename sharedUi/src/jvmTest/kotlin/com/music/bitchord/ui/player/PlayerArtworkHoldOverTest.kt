package com.music.bitchord.ui.player

import coil3.PlatformContext
import coil3.memory.MemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerArtworkHoldOverTest {
    private val previous = MemoryCache.Key("content://media/external/audio/albumart/1")

    @Test
    fun theNextCoverLoadsOverTheLastOne() {
        val art = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, 600, previous)
        assertEquals(previous, art.request.placeholderMemoryCacheKey)
    }

    @Test
    fun aCoverSkippedBeforeItLoadedPassesTheLastOneOn() {
        val skipped = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, 600, previous)
        assertEquals(previous, skipped.lastCover)
    }

    @Test
    fun theFirstCoverHasNothingToStandOn() {
        val art = PlayerArtwork("content://media/external/audio/albumart/1", PlatformContext.INSTANCE, 600)
        assertNull(art.request.placeholderMemoryCacheKey)
        assertNull(art.lastCover)
    }
}
