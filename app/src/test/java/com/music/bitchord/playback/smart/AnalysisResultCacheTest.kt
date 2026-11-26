package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisResultCacheTest {

    private val none: (String) -> Boolean = { false }

    private fun analysis(id: String) = TrackAnalysis(trackId = id)

    @Test
    fun theOldestResultMakesRoomForANewOne() {
        val cache = AnalysisResultCache(maxEntries = 3)
        listOf("a", "b", "c").forEach { assertTrue(cache.put(it, analysis(it), none).isEmpty()) }

        assertEquals(listOf("a"), cache.put("d", analysis("d"), none))
        assertEquals(3, cache.size)
        assertNull(cache["a"])
        assertNotNull(cache["d"])
    }

    @Test
    fun rewritingAResultMakesItNewest() {
        val cache = AnalysisResultCache(maxEntries = 2)
        cache.put("a", analysis("a"), none)
        cache.put("b", analysis("b"), none)
        cache.put("a", analysis("a"), none)

        assertEquals(listOf("b"), cache.put("c", analysis("c"), none))
    }

    @Test
    fun aPinnedResultIsNeverEvicted() {
        val cache = AnalysisResultCache(maxEntries = 2)
        cache.put("running", analysis("running"), none)
        cache.put("b", analysis("b"), none)

        assertEquals(listOf("b"), cache.put("c", analysis("c")) { it == "running" })
        assertNotNull(cache["running"])
    }

    @Test
    fun allPinnedLeavesTheCacheOverItsBoundRatherThanSpinning() {
        val cache = AnalysisResultCache(maxEntries = 1)
        cache.put("a", analysis("a"), none)

        assertTrue(cache.put("b", analysis("b")) { true }.isEmpty())
        assertEquals(2, cache.size)
    }

    @Test
    fun putIfAbsentKeepsWhatIsThere() {
        val cache = AnalysisResultCache(maxEntries = 4)
        val first = analysis("a")
        cache.put("a", first, none)

        val (cached, evicted) = cache.putIfAbsent("a", analysis("a"), none)
        assertSame(first, cached)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun memoryPressureTrimsToTheNewest() {
        val cache = AnalysisResultCache(maxEntries = 24)
        (1..10).forEach { cache.put("t$it", analysis("t$it"), none) }

        assertEquals((1..6).map { "t$it" }, cache.trimTo(4, none))
        assertEquals(4, cache.size)
        assertNotNull(cache["t10"])
    }

    @Test
    fun aNewEntryIsNeverEvictedToMakeRoomForItself() {
        val cache = AnalysisResultCache(maxEntries = 1)
        cache.put("pinned", analysis("pinned"), none)

        val (cached, evicted) = cache.putIfAbsent("new", analysis("new")) { it == "pinned" }
        assertTrue(evicted.isEmpty())
        assertSame(cached, cache["new"])
    }

    @Test
    fun evictionCleanupRunsInsideTheEvictingCall() {
        val seen = mutableListOf<String>()
        lateinit var cache: AnalysisResultCache
        cache = AnalysisResultCache(maxEntries = 1) { evicted ->
            // Still inside the cache's lock: the evicted id is already gone.
            evicted.forEach { assertNull(cache[it]) }
            seen += evicted
        }
        cache.put("a", analysis("a"), none)
        cache.put("b", analysis("b"), none)
        assertEquals(listOf("a"), seen)
    }
}
