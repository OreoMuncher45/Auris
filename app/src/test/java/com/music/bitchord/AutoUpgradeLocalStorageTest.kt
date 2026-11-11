package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the songs kept on the device are upgraded while they play.
 *
 * The rule is two questions and one answer: did the listener say yes, and can
 * the app act on it. An upgrade is a fetch, so an app that is offline cannot
 * make one — whether the offline state is the switch the listener turned or
 * the network having gone away — and a switch left on says nothing about what
 * is possible.
 *
 * The listener's own answer is kept while the upgrade is off. Coming back
 * online restores it; it does not quietly forget a switch that was turned on,
 * and it does not turn one that was turned off back on.
 */
class AutoUpgradeLocalStorageTest {

    private fun allowed(requested: Boolean, offline: Boolean) =
        AppSettings.autoUpgradeLocalStorageAllowed(requested, offline)

    @Test
    fun `the upgrade runs when the listener asked and the app is online`() {
        assertTrue(allowed(requested = true, offline = false))
    }

    @Test
    fun `offline mode turns the upgrade off`() {
        assertFalse(
            "an upgrade is a fetch, and an offline app cannot make one",
            allowed(requested = true, offline = true),
        )
    }

    @Test
    fun `the listener's own answer still rules`() {
        assertFalse(allowed(requested = false, offline = false))
    }

    @Test
    fun `the whole truth table`() {
        val combos = listOf(
            //            requested, offline, allowed?
            Triple(true, false, true),
            Triple(true, true, false),
            Triple(false, false, false),
            Triple(false, true, false),
        )
        for ((requested, offline, expected) in combos) {
            assertTrue(
                "requested=$requested offline=$offline",
                allowed(requested, offline) == expected,
            )
        }
    }
}
