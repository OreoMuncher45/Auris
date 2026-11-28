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

    @Test
    fun aCoverDecodedBehindTheRevealIsNotTheNextOnesPlaceholder() {
        val b = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, 600, previous)
        val bKey = MemoryCache.Key("content://media/external/audio/albumart/2")
        // B decodes while the reveal still shows A in its place.
        b.noteDecoded(bKey, displayed = false)
        assertEquals(previous, b.lastCover, "a skip mid-reveal must stand on A")
        // The reveal ends and B's painter is drawn: now B is what's on screen.
        b.noteVisible()
        assertEquals(bKey, b.lastCover)
    }

    @Test
    fun aCoverDecodedOnScreenIsTheNextOnesPlaceholder() {
        val b = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE, 600, previous)
        val bKey = MemoryCache.Key("content://media/external/audio/albumart/2")
        b.noteDecoded(bKey, displayed = true)
        assertEquals(bKey, b.lastCover)
    }
}
