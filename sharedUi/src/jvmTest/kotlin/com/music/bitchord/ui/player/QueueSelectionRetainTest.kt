package com.music.bitchord.ui.player

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tick on a row without an entry id stays with that row, not its key. */
class QueueSelectionRetainTest {

    private fun song(id: String, entry: String? = null) =
        Song(videoId = id, title = id, artist = "a", thumbnailUrl = null, queueEntryId = entry)

    @Test
    fun aTickOnAnEntryLessCopyIsDroppedWhenAnEarlierCopyLeaves() {
        val selection = QueueSelectionState()
        selection.start()
        // Two copies of "x" with no entry ids: keys x#0 and x#1. The second is ticked.
        val second = QueueTrack(5, song("x"), "c_x#1")
        selection.toggle(second)
        // The first copy leaves; the old second copy is now x#0 at index 4,
        // and a third copy further down takes over the key x#1.
        selection.retain(listOf(QueueTrack(4, song("x"), "c_x#0"), QueueTrack(8, song("x"), "c_x#1")))
        assertTrue(selection.keys.isEmpty(), "the tick did not move to the copy below")
    }

    @Test
    fun aTickOnARowWithAnEntryIdSurvivesAMove() {
        val selection = QueueSelectionState()
        selection.start()
        selection.toggle(QueueTrack(5, song("x", "e1"), "c_e1"))
        selection.retain(listOf(QueueTrack(2, song("x", "e1"), "c_e1")))
        assertEquals(setOf("c_e1"), selection.keys.toSet())
    }
}
