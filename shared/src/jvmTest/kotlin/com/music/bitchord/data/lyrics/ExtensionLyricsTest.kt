package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lyrics an installed `.sflx` extension answers with, read the way the
 * extension writes them.
 *
 * The payloads below are the shape the store's Apple Music package answers
 * with: enhanced LRC in the `words` field, a stamp in front of every word, the
 * answering vocal in a block of its own, and the translations after a
 * separator no recording reaches.
 */
class ExtensionLyricsTest {

    @Test
    fun `reads a word-synced line`() {
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {
                  "lines": [
                    {"startTimeMs": 15460, "words": "<00:15.46>It <00:15.70>was <00:15.90>just ", "endTimeMs": 17300}
                  ],
                  "syncType": "LINE_SYNCED",
                  "instrumental": false,
                  "plainLyrics": "It was just",
                  "provider": "Apple Music"
                }
                """.trimIndent(),
            ),
        )
        val line = lines.single()
        assertEquals(15_460L, line.timeMs)
        assertEquals("It was just", line.text)
        assertTrue(line.isWordSynced)
        assertEquals(listOf("It", "was", "just"), line.words.map { it.text })
        assertEquals(15_460L, line.words[0].startMs)
        assertEquals(15_700L, line.words[0].endMs)
        assertEquals(15_900L, line.words[2].startMs)
        // The last word runs to the end of the line: nothing else says when
        // the singing stops.
        assertEquals(17_300L, line.words[2].endMs)
    }

    @Test
    fun `the stamp after the last word ends that word`() {
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {"lines": [{"startTimeMs": 1000, "words": "<00:01.00>Hel<00:01.20>lo<00:01.60>", "endTimeMs": 2000}],
                 "syncType": "LINE_SYNCED"}
                """.trimIndent(),
            ),
        )
        val line = lines.single()
        // Two halves of one word, and a stamp that closes it.
        assertEquals("Hello", line.text)
        assertEquals(listOf("Hello"), line.words.map { it.text })
        assertEquals(1_000L, line.words.single().startMs)
        assertEquals(1_600L, line.words.single().endMs)
    }

    @Test
    fun `a line with no word stamps is timed but has nothing to sweep`() {
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {"lines": [{"startTimeMs": 4200, "words": "Sittin' in the car", "endTimeMs": 6000}],
                 "syncType": "LINE_SYNCED"}
                """.trimIndent(),
            ),
        )
        val line = lines.single()
        assertEquals(4_200L, line.timeMs)
        assertEquals("Sittin' in the car", line.text)
        assertTrue(!line.isWordSynced)
        assertEquals(6_000L, line.sungUntilMs)
    }

    @Test
    fun `the answering vocal is split off with its own clock`() {
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {"lines": [{"startTimeMs": 1000,
                            "words": "<00:01.00>Hello <00:01.50>world\n[bg:<00:01.20>oh <00:01.60>hey]",
                            "endTimeMs": 2000}],
                 "syncType": "LINE_SYNCED"}
                """.trimIndent(),
            ),
        )
        val line = lines.single()
        assertEquals("Hello world", line.text)
        val backing = assertNotNull(line.background)
        assertEquals("oh hey", backing.text)
        assertEquals(1_200L, backing.timeMs)
        assertTrue(backing.isWordSynced)
    }

    @Test
    fun `the translations after the separator are not lines of the song`() {
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {"lines": [
                    {"startTimeMs": 1000, "words": "<00:01.00>Hello", "endTimeMs": 2000},
                    {"startTimeMs": 999999999, "words": "", "endTimeMs": 999999999},
                    {"startTimeMs": 1000, "words": "Bonjour", "endTimeMs": 2000}
                  ],
                 "syncType": "LINE_SYNCED"}
                """.trimIndent(),
            ),
        )
        assertEquals(1, lines.size)
        assertEquals("Hello", lines.single().text)
    }

    @Test
    fun `an unsynced answer is the plain text`() {
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {"lines": [{"startTimeMs": 0, "words": "Hello", "endTimeMs": 0}],
                 "syncType": "UNSYNCED",
                 "plainLyrics": "Hello\nWorld"}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        // Unsynced: every line at zero, which is how the panel is told there
        // is nothing to follow.
        assertTrue(lines.all { it.timeMs == 0L })
    }

    @Test
    fun `reads the fields however they were spelled`() {
        // The Go host that established the contract read `starttimems`.
        val lines = assertNotNull(
            ExtensionLyrics.parse(
                """
                {"lines": [{"starttimems": 1000, "words": "<00:01.00>Hi", "endtimems": 2000}],
                 "synctype": "LINE_SYNCED"}
                """.trimIndent(),
            ),
        )
        assertEquals(1_000L, lines.single().timeMs)
    }

    @Test
    fun `an instrumental track is a miss rather than an empty answer`() {
        assertNull(
            ExtensionLyrics.parse(
                """
                {"lines": [], "syncType": "LINE_SYNCED", "instrumental": true, "plainLyrics": ""}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `nothing at all is a miss`() {
        assertNull(ExtensionLyrics.parse("null"))
        assertNull(ExtensionLyrics.parse(""))
        assertNull(ExtensionLyrics.parse("{\"error\": \"no lyrics\"}"))
    }
}
