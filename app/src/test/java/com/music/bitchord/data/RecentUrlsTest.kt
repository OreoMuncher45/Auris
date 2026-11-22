package com.music.bitchord.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentUrlsTest {

    @Test
    fun keepsOnlyTheMostRecentlySeenUrls() {
        val urls = RecentUrls(2)
        urls.add("a")
        urls.add("b")
        // Seeing "a" again makes "b" the oldest.
        assertTrue(urls.contains("a"))
        urls.add("c")
        assertTrue(urls.contains("a"))
        assertFalse(urls.contains("b"))
        assertTrue(urls.contains("c"))
    }
}
