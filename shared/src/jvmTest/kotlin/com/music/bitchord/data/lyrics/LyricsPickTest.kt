package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.Test

class LyricsPickTest {

    private fun result(source: LyricsSource, timeMs: Long) =
        LyricsRepository.Result(source, listOf(LyricLine(timeMs = timeMs, text = "line")))

    @Test
    fun `plain lyrics found first do not shadow line-synced ones when syllable sync is preferred`() = runBlocking {
        val picked = LyricsRepository.pick(
            listOf(
                LyricsSource.YOUTUBE_MUSIC to suspend { result(LyricsSource.YOUTUBE_MUSIC, 0L) },
                LyricsSource.LRCLIB to suspend { result(LyricsSource.LRCLIB, 1_000L) },
            ),
            prioritizeSyllableSync = true,
        )

        assertEquals(LyricsSource.LRCLIB, picked?.source)
    }

    @Test
    fun `timed lyrics whose first line starts at zero are not taken for plain`() = runBlocking {
        val zeroStart = LyricsRepository.Result(
            LyricsSource.YOUTUBE_MUSIC,
            listOf(LyricLine(timeMs = 0L, text = "Hello", sungUntilMs = 2_000L)),
        )
        listOf(true, false).forEach { prefer ->
            val picked = LyricsRepository.pick(
                listOf(
                    LyricsSource.YOUTUBE_MUSIC to suspend { zeroStart },
                    LyricsSource.LRCLIB to suspend { result(LyricsSource.LRCLIB, 1_000L) },
                ),
                prioritizeSyllableSync = prefer,
            )
            assertEquals(LyricsSource.YOUTUBE_MUSIC, picked?.source)
        }
    }

    @Test
    fun `plain lyrics are still the answer when nothing is timed`() = runBlocking {
        val picked = LyricsRepository.pick(
            listOf(LyricsSource.YOUTUBE_MUSIC to suspend { result(LyricsSource.YOUTUBE_MUSIC, 0L) }),
            prioritizeSyllableSync = true,
        )

        assertEquals(LyricsSource.YOUTUBE_MUSIC, picked?.source)
    }

    @Test
    fun `a timed source whose only cue is at 0 s with no end is still timed`() = runBlocking {
        // YouTube Music's timed lyrics, one cue at 0 and no end time.
        val page = kotlinx.serialization.json.Json.parseToJsonElement(
            """{ "timedLyricsData": [ { "lyricLine": "Hello", "cueRange": { "startTimeMilliseconds": "0" } } ] }""",
        ) as kotlinx.serialization.json.JsonObject
        val lines = timedLyrics(page)!!
        kotlin.test.assertTrue(lines.any { it.isTimed }, "the cue at 0 was read as plain")
        // An LRC whose only stamp is [00:00.00], likewise.
        kotlin.test.assertTrue(LrcLib.parseLrc("[00:00.00]Hello").any { it.isTimed })
        val zeroOnly = LyricsRepository.Result(LyricsSource.YOUTUBE_MUSIC, lines)
        listOf(true, false).forEach { prefer ->
            val picked = LyricsRepository.pick(
                listOf(
                    LyricsSource.GENIUS to suspend { result(LyricsSource.GENIUS, 0L) },
                    LyricsSource.YOUTUBE_MUSIC to suspend { zeroOnly },
                ),
                prioritizeSyllableSync = prefer,
            )
            assertEquals(LyricsSource.YOUTUBE_MUSIC, picked?.source)
        }
    }

    @Test
    fun `a plain source's lines are not timed`() {
        kotlin.test.assertFalse(LyricLine(0L, "Hello").isTimed)
    }

    @Test
    fun `an LRC of stamps with nothing sung parses to nothing`() {
        kotlin.test.assertTrue(LrcLib.parseLrc("[00:00.00]\n[00:12.00]\n[00:30.00]").isEmpty())
    }

    @Test
    fun `a result of breaks only does not outrank plain lyrics from an earlier source`() = runBlocking {
        val breaksOnly = LyricsRepository.Result(
            LyricsSource.LRCLIB,
            listOf(LyricLine(0L, "", timed = true), LyricLine(12_000L, "", timed = true)),
        )
        listOf(true, false).forEach { prefer ->
            val picked = LyricsRepository.pick(
                listOf(
                    LyricsSource.YOUTUBE_MUSIC to suspend { result(LyricsSource.YOUTUBE_MUSIC, 0L) },
                    LyricsSource.LRCLIB to suspend { breaksOnly },
                ),
                prioritizeSyllableSync = prefer,
            )
            assertEquals(LyricsSource.YOUTUBE_MUSIC, picked?.source)
        }
    }
}
