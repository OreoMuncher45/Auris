package com.music.bitchord

import com.music.bitchord.data.settings.ConnectionWatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The offline verdict, from the default-network callbacks.
 *
 * Home's offline shelf, the player's file-or-stream choice and the request
 * gate all read this verdict, and before this fix it could stay wrong until
 * the process restarted. The system's own "active network" answer lags the
 * callbacks: on an emulator, the second airplane-mode toggle delivered
 * `onLost(103)` while `activeNetwork` still named 103 as validated, and a
 * verdict that asked the system instead of the callback marked the app
 * online for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionWatchTest {

    private class Rig(initiallyUp: Boolean) {
        val scope = TestScope()
        /** What `ConnectivityManager` answers when asked; lags the callbacks. */
        var systemSaysUp = initiallyUp
        val published = mutableListOf<Boolean>()
        val watch = ConnectionWatch(scope, GRACE, { systemSaysUp }) { published += it }
        val verdict: Boolean? get() = published.lastOrNull()
        fun settle() {
            scope.advanceTimeBy(GRACE * 4)
            scope.runCurrent()
        }
    }

    @Test
    fun `a lost network is offline after the grace even while the system still reports it`() {
        val rig = Rig(initiallyUp = true)
        rig.watch.start()
        rig.watch.onAvailable(NET_A)
        rig.watch.onCapabilities(NET_A, validated = true)
        rig.settle()
        assertEquals(true, rig.verdict)

        // The callback says the network is gone; the system's answer has not
        // caught up yet (what the emulator log showed).
        rig.watch.onLost(NET_A)
        rig.settle()
        rig.systemSaysUp = false
        rig.settle()

        assertEquals(false, rig.verdict)
    }

    @Test
    fun `a validated network after an outage is online even when the system answer lags`() {
        val rig = Rig(initiallyUp = false)
        rig.watch.start()
        rig.settle()
        assertEquals(false, rig.verdict)

        // The network comes back and is validated, but the system still
        // answers from before when each callback asks.
        rig.watch.onAvailable(NET_B)
        rig.watch.onCapabilities(NET_B, validated = false)
        rig.watch.onCapabilities(NET_B, validated = true)
        rig.settle()
        rig.systemSaysUp = true
        rig.settle()

        assertEquals(true, rig.verdict)
    }

    @Test
    fun `a handover shorter than the grace never reads as offline`() {
        val rig = Rig(initiallyUp = true)
        rig.watch.start()
        rig.watch.onAvailable(NET_A)
        rig.watch.onCapabilities(NET_A, validated = true)
        rig.settle()
        rig.published.clear()

        rig.watch.onLost(NET_A)
        rig.scope.advanceTimeBy(GRACE / 2)
        rig.watch.onAvailable(NET_B)
        rig.watch.onCapabilities(NET_B, validated = true)
        rig.settle()

        assertEquals(listOf(true), rig.published.distinct())
    }

    @Test
    fun `no network at launch is offline after the grace`() {
        val rig = Rig(initiallyUp = false)
        rig.watch.start()
        rig.settle()
        assertEquals(false, rig.verdict)
    }

    @Test
    fun `losing a network that is no longer the default changes nothing`() {
        val rig = Rig(initiallyUp = true)
        rig.watch.start()
        rig.watch.onAvailable(NET_A)
        rig.watch.onCapabilities(NET_A, validated = true)
        rig.watch.onAvailable(NET_B)
        rig.watch.onCapabilities(NET_B, validated = true)
        rig.settle()

        rig.watch.onLost(NET_A)
        rig.settle()

        assertEquals(true, rig.verdict)
    }

    private companion object {
        const val GRACE = 750L
        const val NET_A = "net-a"
        const val NET_B = "net-b"
    }
}
