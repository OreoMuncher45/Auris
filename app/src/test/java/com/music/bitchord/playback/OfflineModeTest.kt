package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which copy of a track plays, given the one thing that decides it.
 *
 * Kept as a pure truth table rather than a test of `AppSettings`, which cannot
 * be built on the JVM: the bug this used to pin was not in the arithmetic but
 * in the wiring, where a caller read the detector and forgot the switch — or
 * read a signal whose offline verdict was computed at the wrong instant. That
 * question is gone from this decision entirely (a file plays whatever the
 * network is doing), and what is left is worth having as a truth table for the
 * same reason: the decision is one line, and the line is what gets edited.
 */
class OfflineModeTest {

    private fun playsLocal(hasFile: Boolean) = preferLocalFile(hasLocalFile = hasFile)

    @Test
    fun `a saved file plays`() {
        assertTrue(playsLocal(hasFile = true))
    }

    @Test
    fun `no file to play means the network is asked`() {
        assertFalse(playsLocal(hasFile = false))
    }

    @Test
    fun `the switch and the detector are alternatives, either is enough`() {
        // AppSettings.effectiveOffline is `manual || !streaming`; this is that
        // line, spelled out, because a caller that reconstructs it wrongly is
        // how the switch ends up doing nothing. It decides the *offline jobs*
        // now — claiming the files for the rest of the queue, refusing the
        // second look — rather than which copy of a track plays.
        val combos = listOf(
            //              manual, streaming, offline?
            Triple(true, true, true),    // switch on, network present -> offline
            Triple(true, false, true),   // switch on, no network -> offline
            Triple(false, false, true),   // switch off, no network -> offline
            Triple(false, true, false),   // switch off, network present -> online
        )
        for ((manual, streaming, expected) in combos) {
            val offline = manual || !streaming
            assertTrue("manual=$manual streaming=$streaming", offline == expected)
        }
    }
}
