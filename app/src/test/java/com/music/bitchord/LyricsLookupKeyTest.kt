package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.ui.LyricsLookupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The player's lyrics are looked up again when a setting that picks the answer changes. */
class LyricsLookupKeyTest {
    private val order = LyricsSource.entries.take(2)
    private val sources = order.toSet()

    private fun key(order: List<LyricsSource> = this.order, prioritize: Boolean = false) =
        LyricsLookupKey("abcdefghijk", sources, order, prioritize)

    @Test
    fun `the same track under the same settings is not looked up twice`() {
        assertEquals(key(), key())
    }

    @Test
    fun `reordering sources or switching syllable sync is a new lookup`() {
        assertNotEquals(key(), key(order = order.reversed()))
        assertNotEquals(key(), key(prioritize = true))
    }
}
