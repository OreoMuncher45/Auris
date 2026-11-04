package com.music.auris.desktop

import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `java.util.prefs` throws when a value passes [Preferences.MAX_VALUE_LENGTH], on whichever thread
 * was saving — which for this app is the UI thread.
 */
class AurisPreferenceChunksTest {

    private val node: Preferences = Preferences.userRoot().node("com.music.auris.desktop.test.chunks")

    @AfterTest
    fun tearDown() {
        runCatching { node.removeNode() }
    }

    @Test
    fun aValueLongerThanThePlatformLimitSurvivesARoundTrip() {
        val huge = buildString {
            repeat(Preferences.MAX_VALUE_LENGTH * 3) { append('a' + (it % 26)) }
        }
        assertTrue(huge.length > Preferences.MAX_VALUE_LENGTH)

        // Writing this straight into a preference is what crashed the app.
        AurisPreferenceChunks.write(node, "entries", huge)

        assertEquals(huge, AurisPreferenceChunks.read(node, "entries"))
    }

    @Test
    fun aShorterValueLeavesNoTailOfTheLongerOneItReplaced() {
        AurisPreferenceChunks.write(node, "queue", "x".repeat(Preferences.MAX_VALUE_LENGTH * 2))

        AurisPreferenceChunks.write(node, "queue", "short")

        assertEquals("short", AurisPreferenceChunks.read(node, "queue"))
    }

    @Test
    fun aValueWrittenBeforeChunkingIsStillReadable() {
        node.put("legacy", "written by an older build")

        assertEquals("written by an older build", AurisPreferenceChunks.read(node, "legacy"))
    }

    @Test
    fun anUnwrittenKeyReadsAsNothing() {
        assertNull(AurisPreferenceChunks.read(node, "never-written"))
    }

    @Test
    fun anEmptyValueIsStoredRatherThanLost() {
        AurisPreferenceChunks.write(node, "empty", "")

        assertEquals("", AurisPreferenceChunks.read(node, "empty"))
    }

    @Test
    fun `characters xml cannot carry never reach the store`() {
        val node = Preferences.userRoot().node("auris-test-${System.nanoTime()}")
        try {
            // 0x1f is the one that was actually observed.
            AurisPreferenceChunks.write(node, "k", "before\u001Fafter\u0000end")

            val stored = AurisPreferenceChunks.read(node, "k")
            assertEquals("beforeafterend", stored)
            assertTrue(stored!!.none { it < ' ' })

            // Tab, newline and carriage return are legal XML and survive.
            AurisPreferenceChunks.write(node, "k2", "a\tb\nc\rd")
            assertEquals("a\tb\nc\rd", AurisPreferenceChunks.read(node, "k2"))
        } finally {
            node.removeNode()
        }
    }
}
