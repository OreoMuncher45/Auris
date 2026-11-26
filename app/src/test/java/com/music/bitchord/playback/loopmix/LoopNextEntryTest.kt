package com.music.bitchord.playback.loopmix

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopNextEntryTest {
    /** An entry: its queue identity and the stream it plays. */
    private data class Entry(val id: String, val stream: String)

    private val samePlayable = { a: Entry, b: Entry -> a == b }
    private val armed = Entry("entry-2", "https://stream/a")

    @Test
    fun theArmedEntryStillNextKeepsTheLoop() {
        assertTrue(LoopNextEntry.stillArmed(Player.REPEAT_MODE_OFF, armed, armed, samePlayable))
        assertTrue(LoopNextEntry.stillArmed(Player.REPEAT_MODE_ALL, armed, armed, samePlayable))
    }

    @Test
    fun repeatOneTurnedOnAfterTheTakeoverEndsTheMix() {
        // The player's next index ignores Repeat One, so the armed entry still looks next.
        assertFalse(LoopNextEntry.stillArmed(Player.REPEAT_MODE_ONE, armed, armed, samePlayable))
    }

    @Test
    fun theNextEntrysStreamReplacedInPlaceEndsTheMix() {
        // A download finished while the decks built: same entry, new file.
        val replaced = armed.copy(stream = "file:///downloads/a.opus")
        assertFalse(LoopNextEntry.stillArmed(Player.REPEAT_MODE_OFF, replaced, armed, samePlayable))
    }

    @Test
    fun anotherEntryOrNoneNextEndsTheMix() {
        assertFalse(LoopNextEntry.stillArmed(Player.REPEAT_MODE_OFF, Entry("entry-3", "https://stream/a"), armed, samePlayable))
        assertFalse(LoopNextEntry.stillArmed(Player.REPEAT_MODE_OFF, null, armed, samePlayable))
        assertFalse(LoopNextEntry.stillArmed<Entry>(Player.REPEAT_MODE_OFF, armed, null, samePlayable))
    }
}
