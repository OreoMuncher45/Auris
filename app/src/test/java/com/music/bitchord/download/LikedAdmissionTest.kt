package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import com.music.bitchord.data.innertube.Innertube
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @After
    fun signOut() {
        Innertube.cookie = null
    }

    /**
     * The real session lock: an account switch attempted while a Liked Music
     * admission is running waits for it to finish, and the admission after the
     * switch admits nothing.
     */
    @Test
    fun anAccountSwitchDuringTheAdmissionWaitsUntilItCompletes() {
        Innertube.cookie = "SID=A"
        val readFor = Innertube.listenerStamp()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var admitted = false
        val admission = Thread {
            admitted = admitForListener(liked = true, readFor = readFor, whileListener = Innertube::whileListener) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                order += "admitted"
            }
        }
        admission.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val switch = Thread {
            Innertube.cookie = "SID=B"
            order += "switched"
        }
        switch.start()
        // The switch is parked on the lock the admission holds, and has changed nothing.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (switch.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(Thread.State.BLOCKED, switch.state)
        assertEquals(readFor, Innertube.listenerStamp())
        assertTrue(order.isEmpty())

        release.countDown()
        admission.join(5_000)
        switch.join(5_000)
        assertTrue(admitted)
        assertEquals(listOf("admitted", "switched"), order.toList())
        // Once the switch has landed, what was read for A is not admitted.
        var late = 0
        assertFalse(admitForListener(liked = true, readFor = readFor, whileListener = Innertube::whileListener) { late++ })
        assertEquals(0, late)
    }
}
