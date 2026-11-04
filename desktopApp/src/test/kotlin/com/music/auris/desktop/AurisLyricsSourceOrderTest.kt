package com.music.auris.desktop

import com.music.bitchord.data.lyrics.LyricsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The source list, as the settings dialog stores it and the lookup reads it back. */
class AurisLyricsSourceOrderTest {

    @Test
    fun theChainIsTheStoredOrderNarrowedToWhatIsTicked() {
        val order = listOf("BiniLyrics", "LRCLIB", "Genius")
        assertEquals(
            listOf("BiniLyrics", "Genius"),
            AurisLyricsClient.enabledSources(order, setOf("Genius", "BiniLyrics")),
        )
    }

    @Test
    fun untickingEverythingLeavesNothingToAsk() {
        assertEquals(emptyList(), AurisLyricsClient.enabledSources(listOf("LRCLIB"), emptySet()))
    }

    @Test
    fun theDesktopOffersThePhonesProvidersInThePhonesOrder() {
        // One provider list for both apps: the lookup is the phone's LyricsRepository.
        val names = AurisLyricsClient.sources.map { it.name }
        assertEquals(LyricsSource.offered.map { it.label }, names)
        assertTrue(names.all { lyricsSourceNamed(it) != null })
        // The unsynced fallback is last by definition.
        assertEquals("Genius", names.last())
    }

    @Test
    fun everySourceOfferedIsOneTheLookupCanActuallyAsk() {
        // The dialog is built from [sources]; a name in it with no provider behind it is a row that
        // silently does nothing when ticked.
        val order = AurisLyricsClient.sources.map { it.name }
        assertEquals(order, AurisLyricsClient.enabledSources(order, order.toSet()))
    }
}
