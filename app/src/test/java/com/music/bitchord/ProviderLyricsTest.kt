package com.music.bitchord

import com.music.bitchord.data.lyrics.KaraokeLrc
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.ProviderLyrics
import com.music.bitchord.data.lyrics.PaxSenix
import com.music.bitchord.data.lyrics.normalizePaxSenixApiKey
import com.music.bitchord.data.lyrics.youtubeStrings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLyricsTest {

    @Test
    fun `the six imported providers are exposed`() {
        assertEquals(16, LyricsSource.entries.size)
        assertTrue(LyricsSource.entries.containsAll(listOf(
            LyricsSource.BETTER_LYRICS_PORTATO,
            LyricsSource.MEGALOBIZ,
            LyricsSource.PAXSENIX_SPOTIFY,
            LyricsSource.PAXSENIX_MUSIXMATCH,
            LyricsSource.YOUTUBE_TRANSCRIPT,
            LyricsSource.YOUTUBE_MUSIC,
        )))
    }

    @Test
    fun `json wrapped TTML keeps Apple word timing`() {
        val raw = """{"content":"<tt><body><div><p begin=\"1.0\" end=\"2.0\"><span begin=\"1.0\" end=\"1.4\">sing </span><span begin=\"1.4\" end=\"2.0\">along</span></p></div></body></tt>"}"""
        val line = ProviderLyrics.parse(raw)!!.single { !it.isGap }
        assertEquals("sing along", line.text)
        assertEquals(2, line.words.size)
        assertEquals(2_000L, line.words.last().endMs)
    }

    @Test
    fun `NetEase prefix YRC gets Apple style word animation`() {
        val line = KaraokeLrc.parse("[1000,900](1000,400,0)sing(1400,500,0)along")
            .single { !it.isGap }
        assertEquals("singalong", line.text)
        assertEquals(listOf(1_000L, 1_400L), line.words.map { it.startMs })
        assertEquals(1_900L, line.words.last().endMs)
    }

    @Test
    fun `QQ suffix QRC gets Apple style word animation`() {
        val line = KaraokeLrc.parse("[1000,900]sing (1000,400,0)along(1400,500,0)")
            .single { !it.isGap }
        assertEquals("sing along", line.text)
        assertEquals(2, line.words.size)
        assertTrue(line.isWordSynced)
    }

    @Test
    fun `QQ XML envelope is recognized as karaoke rather than TTML`() {
        val qrc = """<QrcInfos><LyricInfo LyricContent="[1000,900]sing (1000,400,0)along(1400,500,0)"/></QrcInfos>"""
        val line = ProviderLyrics.parse(qrc)!!.single { !it.isGap }
        assertEquals("sing along", line.text)
        assertEquals(2, line.words.size)
    }

    @Test
    fun `structured PaxSenix Apple response keeps word timing`() {
        val raw = """
            {"type":"Word","content":[
              {"timestamp":1000,"text":[{"text":"sing","timestamp":1000},{"text":"along","timestamp":1400}]},
              {"timestamp":2200,"text":[{"text":"again","timestamp":2200}]}
            ]}
        """.trimIndent()
        val lines = PaxSenix.parseTimedApple(raw)!!.filterNot { it.isGap }
        assertEquals("sing along", lines.first().text)
        assertEquals(1_400L, lines.first().words[1].startMs)
        assertEquals(2_200L, lines.first().words[1].endMs)
    }

    @Test
    fun `PaxSenix accepts bare and bearer-prefixed API keys`() {
        assertEquals("secret", normalizePaxSenixApiKey(" secret "))
        assertEquals("secret", normalizePaxSenixApiKey("Bearer secret"))
        assertEquals("secret", normalizePaxSenixApiKey("bearer   secret "))
    }

    @Test
    fun `YouTube nested text objects are traversed without crashing`() {
        val response = Json.parseToJsonElement(
            """{"text":{"runs":[{"text":"Lyrics"}]}}"""
        )

        assertEquals(listOf("Lyrics"), response.youtubeStrings())
    }
}
