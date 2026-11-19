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

    @Test
    fun `the catalogue switch answers to the words people search for`() {
        // The words the row answered to under Playback, plus the ones its
        // new home adds. Typing any of them must still turn it up.
        listOf(
            "provider", "spotify", "youtube", "search", "library",
            "Spotify", "YouTube Music", "catalogue", "catalog", "source",
        ).forEach { query ->
            val search = SettingsSearch(query)
            assertTrue(query, search.matches("Spotify or YouTube Music", *MUSIC_CATALOGUE_KEYWORDS))
        }
    }

    @Test
    fun `the catalogue switch keeps out of unrelated searches`() {
        val search = SettingsSearch("equalizer")
        assertFalse(search.matches("Spotify or YouTube Music", *MUSIC_CATALOGUE_KEYWORDS))
    }
}
