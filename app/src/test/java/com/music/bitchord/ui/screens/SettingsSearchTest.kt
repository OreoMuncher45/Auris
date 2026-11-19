package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    @Test
    fun `a blank query finds nothing missing on a page with no rows`() {
        // The Auris Pass page: no searchable rows, so nothing is ever asked.
        assertFalse(SettingsSearch("").foundNothing)
        assertFalse(SettingsSearch("   ").foundNothing)
    }

    @Test
    fun `a blank query finds nothing missing on a page with rows`() {
        val search = SettingsSearch("")
        search.matches("Support", "donate")
        assertFalse(search.foundNothing)
    }

    @Test
    fun `a typed query that matches no row has found nothing`() {
        val search = SettingsSearch("zzz")
        search.matches("Support", "donate")
        search.matches("Theme", "dark mode")
        assertTrue(search.foundNothing)
    }

    @Test
    fun `a typed query that matches only the last group asked has found something`() {
        // The Support group is asked last; a hit there still counts.
        val search = SettingsSearch(" donate ")
        search.matches("Theme", "dark mode")
        search.matches("Support", "donate")
        assertFalse(search.foundNothing)
    }
}
