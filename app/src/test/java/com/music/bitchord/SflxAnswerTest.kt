package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an extension's answer says, and what it does not.
 *
 * The answer is JSON written by somebody else's JavaScript, and the field that
 * matters most is the one that is often *not* there: Tidal answers a
 * segmented start with no URL at all and the parts of one beside it.
 *
 * These are the cases that fail quietly — a JSON null read as the word
 * "null", a segmented answer read as no answer — and each of them was worth a
 * track that would not play.
 */
class SflxAnswerTest {

    @Test
    fun `a JSON null is not a URL`() {
        // org.json reads this as the four letters "null", which is neither
        // blank nor absent — and was handed to the player as an address.
        assertNull(SflxAnswer.streamUrl("""{"streamUrl":null,"track":{"id":"t"}}"""))
    }

    @Test
    fun `the word null is not a URL either`() {
        assertNull(SflxAnswer.streamUrl("""{"streamUrl":"null"}"""))
    }

    @Test
    fun `a blank URL is no URL`() {
        assertNull(SflxAnswer.streamUrl("""{"streamUrl":"   "}"""))
    }

    @Test
    fun `a URL is a URL`() {
        assertEquals(
            "https://example.test/track.flac",
            SflxAnswer.streamUrl("""{"streamUrl":"https://example.test/track.flac"}"""),
        )
    }

    @Test
    fun `a segmented answer carries a stream`() {
        // The answer Tidal gives for an Atmos mix: no URL, and the parts of
        // one. The parts are the answer.
        val raw = """
            {"streamUrl":null,"track":{"id":"tidal:1","audioQuality":"DOLBY_ATMOS"},
             "headers":{"User-Agent":"Auris"},
             "segments":[{"url":"https://cdn.test/0.mp4"},{"url":"https://cdn.test/1.mp4"}]}
        """.trimIndent()
        assertTrue(SflxAnswer.carriesStream(raw))
        assertNull(SflxAnswer.streamUrl(raw))
        assertEquals(2, SflxAnswer.segments(raw).size)
        assertEquals("https://cdn.test/0.mp4", SflxAnswer.segments(raw).first().url)
        assertEquals("Auris", SflxAnswer.headers(raw)["User-Agent"])
    }

    @Test
    fun `an answer with nothing in it carries nothing`() {
        assertFalse(SflxAnswer.carriesStream("""{"streamUrl":null,"error":"no stream"}"""))
        assertFalse(SflxAnswer.carriesStream("""{}"""))
        assertFalse(SflxAnswer.carriesStream("""not json at all"""))
    }

    @Test
    fun `an error is read when there is one`() {
        assertEquals("stream resolution timeout", SflxAnswer.error("""{"error":"stream resolution timeout"}"""))
    }

    @Test
    fun `a null error is no error`() {
        // The same trap as the URL: read as the word "null", it threw a
        // failure whose message was four letters long.
        assertNull(SflxAnswer.error("""{"error":null}"""))
        assertNull(SflxAnswer.error("""{"error":""}"""))
        assertNull(SflxAnswer.error("""{"streamUrl":"https://x"}"""))
    }

    @Test
    fun `what the extension said about the audio is carried through`() {
        val raw = """
            {"streamUrl":"https://x","track":{"id":"tidal:1","audioQuality":"DOLBY_ATMOS",
             "mimeType":"audio/mp4","bitDepth":24,"sampleRate":48000,"bitrate":768,
             "audioModes":["atmos"]}}
        """.trimIndent()
        val track = SflxAnswer.track(raw, "tidal:1")
        assertEquals("tidal:1", track?.id)
        assertEquals("DOLBY_ATMOS", track?.audioQuality)
        assertEquals("audio/mp4", track?.mimeType)
        assertEquals(24, track?.bitDepth)
        assertEquals(48000.0, track?.sampleRate)
        assertEquals(768, track?.bitrate)
        assertEquals(listOf("atmos"), track?.audioModes)
    }

    @Test
    fun `an answer with no track block says nothing about the audio`() {
        assertNull(SflxAnswer.track("""{"streamUrl":"https://x"}""", "tidal:1"))
    }
}
