package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Following Liked Music past the page budget, which is the only way a track beyond it reads as
 * liked. A port of Android's `LikedMusicSyncTest`.
 */
class DesktopLikedSyncTest {

    private fun pages(vararg entries: Pair<String, DesktopSearchClient.LikedPage>) = entries.toMap()

    @Test
    fun `it follows the chain to exhaustion`() = runBlocking {
        val chain = pages(
            "t0" to DesktopSearchClient.LikedPage(setOf("a", "b"), "t1"),
            "t1" to DesktopSearchClient.LikedPage(setOf("c"), "t2"),
            "t2" to DesktopSearchClient.LikedPage(setOf("d"), null),
        )
        val seen = mutableSetOf<String>()
        DesktopSearchClient.syncLikedIds("t0", { seen += it }, { chain[it] })
        assertEquals(setOf("a", "b", "c", "d"), seen)
    }

    @Test
    fun `it stops when a continuation points back at a page already read`() = runBlocking {
        val chain = pages(
            "t0" to DesktopSearchClient.LikedPage(setOf("a"), "t1"),
            "t1" to DesktopSearchClient.LikedPage(setOf("b"), "t0"),
        )
        val seen = mutableSetOf<String>()
        DesktopSearchClient.syncLikedIds("t0", { seen += it }, { chain[it] })
        assertEquals(setOf("a", "b"), seen)
    }

    @Test
    fun `a page that will not load ends the sync rather than the session`() = runBlocking {
        val chain = pages("t0" to DesktopSearchClient.LikedPage(setOf("a"), "gone"))
        val seen = mutableSetOf<String>()
        DesktopSearchClient.syncLikedIds("t0", { seen += it }, { chain[it] })
        assertEquals(setOf("a"), seen)
    }
}
