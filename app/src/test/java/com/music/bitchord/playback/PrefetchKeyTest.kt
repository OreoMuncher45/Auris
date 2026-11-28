package com.music.bitchord.playback

import com.music.bitchord.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole-file prefetch writes and checks one entry. */
class PrefetchKeyTest {

    @After fun restore() {
        AutomixAnalysisSource.rendition = { null }
    }

    @Test
    fun underPremiumThePrefetchFillsAndVerifiesThePremiumEntry() = runBlocking {
        AutomixAnalysisSource.rendition = { "premium" }
        val key = AudioCache.prefetchKeyOf("vid")
        assertEquals("vid#premium", key)

        // The bare entry is empty; every write lands under the key it was given.
        val stored = mutableMapOf<String, Long>()
        val fetched = mutableListOf<Long>()
        val whole = AudioCache.cacheRangesOnce(
            key = key,
            total = 10,
            chunk = 4,
            stillWanted = { true },
            cachedAt = { position, length -> if ((stored[key] ?: 0L) >= position + length) length else 0L },
            fetchRange = { position, length ->
                fetched += position
                stored[key] = position + length
            },
        )

        // The whole range, under the Premium key, and verified there.
        assertTrue(whole)
        assertEquals(listOf(0L, 4L, 8L), fetched)
        assertEquals(10L, stored[key])
        assertEquals(null, stored["vid"])
    }

    @Test
    fun theOrdinaryRenditionKeepsTheBareEntry() {
        assertEquals("vid", AudioCache.prefetchKeyOf("vid"))
    }
}
