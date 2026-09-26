package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pulling an artist and a song back out of an upload title, which is what Genius has to be asked
 * with. A port of what Android's `GeniusTest` covers with noisy titles.
 */
class DesktopGeniusQueryTest {

    @Test
    fun `a plain title asks once, as itself`() {
        val attempts = DesktopLyricsClient.geniusAttempts("Bohemian Rhapsody", "Queen")
        assertEquals("Bohemian Rhapsody" to "Queen", attempts.first())
    }

    @Test
    fun `an artist-dash-title upload is split on the dash`() {
        val attempts = DesktopLyricsClient.geniusAttempts("GEJLON - USA", "Gejlon")
        assertEquals("USA" to "GEJLON", attempts.first())
    }

    @Test
    fun `brackets are dropped in a later attempt rather than the first`() {
        val attempts = DesktopLyricsClient.geniusAttempts(
            "GEJLON - USA [OFFICIAL MUSIC VIDEO] Prod. Jake Angel Beats",
            "Gejlon",
        )
        assertEquals("USA [OFFICIAL MUSIC VIDEO] Prod. Jake Angel Beats" to "GEJLON", attempts.first())
        assertTrue(
            attempts.any { it.first == "USA Prod. Jake Angel Beats" },
            "the de-bracketed title should be asked for too: $attempts",
        )
    }

    @Test
    fun `the last resort asks for the song with no credit at all`() {
        val attempts = DesktopLyricsClient.geniusAttempts("Queen - Bohemian Rhapsody (Official Video)", "Queen")
        assertTrue(attempts.any { it.second.isBlank() }, "expected an uncredited attempt: $attempts")
    }

    @Test
    fun `the same query is never asked twice`() {
        val attempts = DesktopLyricsClient.geniusAttempts("Shape of You", "Ed Sheeran")
        assertEquals(attempts.size, attempts.distinct().size, "duplicate attempts: $attempts")
    }
}
