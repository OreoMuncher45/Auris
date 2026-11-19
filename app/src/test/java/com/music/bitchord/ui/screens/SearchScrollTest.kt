package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchScrollTest {

    @Test
    fun `the first composition leaves the restored position alone`() {
        assertEquals(SearchScroll.STAY, searchScrollFor(previous = null, current = ""))
    }

    @Test
    fun `typing a query goes to the top of the filtered list`() {
        assertEquals(SearchScroll.TOP, searchScrollFor(previous = "", current = "d"))
        assertEquals(SearchScroll.TOP, searchScrollFor(previous = "d", current = "do"))
    }

    @Test
    fun `clearing a query goes back to the page's place`() {
        assertEquals(SearchScroll.RESTORE, searchScrollFor(previous = "donate", current = ""))
    }

    @Test
    fun `spaces are no query at all`() {
        // Nothing is filtered, so neither the top nor a restore.
        assertEquals(SearchScroll.STAY, searchScrollFor(previous = "", current = " "))
        assertEquals(SearchScroll.STAY, searchScrollFor(previous = " ", current = "  "))
        // Clearing a real query down to spaces is still clearing it.
        assertEquals(SearchScroll.RESTORE, searchScrollFor(previous = "donate", current = "  "))
    }
}
