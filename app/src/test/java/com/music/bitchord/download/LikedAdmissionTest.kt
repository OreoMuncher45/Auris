package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Liked Music admitted only while the session it was read for is current: Zvm2. */
class LikedAdmissionTest {
    /** A stand-in for Innertube.whileListener: runs the block only while [current] is the stamp. */
    private var current = "A#1"
    private val whileListener: (String, () -> Unit) -> Unit? = { stamp, block -> if (stamp == current) block() else null }

    @Test
    fun likedMusicReadForTheCurrentSessionIsAdmitted() {
        var enqueued = 0
        assertTrue(admitForListener(liked = true, readFor = "A#1", whileListener = whileListener) { enqueued++ })
        assertEquals(1, enqueued)
    }

    @Test
    fun anAccountSwitchBetweenTheReadAndTheAdmissionAdmitsNothing() {
        var enqueued = 0
        val readFor = current
        current = "B#2" // switched after the read, before admission
        assertFalse(admitForListener(liked = true, readFor = readFor, whileListener = whileListener) { enqueued++ })
        assertEquals(0, enqueued)
        // A -> B -> A: the generation moved, so it is still not the session it was read for.
        current = "A#3"
        assertFalse(admitForListener(liked = true, readFor = readFor, whileListener = whileListener) { enqueued++ })
        assertEquals(0, enqueued)
    }

    @Test
    fun aPublicPlaylistIsAdmittedWithoutTheSessionCheck() {
        var enqueued = 0
        current = "B#2"
        assertTrue(admitForListener(liked = false, readFor = "A#1", whileListener = whileListener) { enqueued++ })
        assertEquals(1, enqueued)
    }
}
