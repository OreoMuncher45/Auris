package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which clip is allowed behind which track. Every case here is a way a common title ("Rang De")
 * used to put another song's Canvas on the player.
 */
class CanvasMatchTest {

    private val title = "Rang De"
    private val artist = "Ramajogayya Sastri, Ramya Behara"

    private fun item(uri: String?, name: String?, artists: List<String>?, album: String? = null): String {
        val fields = buildList {
            uri?.let { add("\"uri\":\"$it\"") }
            name?.let { add("\"name\":\"$it\"") }
            artists?.let { names ->
                add("\"artists\":{\"items\":[" + names.joinToString(",") { "{\"profile\":{\"name\":\"$it\"}}" } + "]}")
            }
            album?.let { add("\"albumOfTrack\":{\"name\":\"$it\"}") }
        }
        return "{\"item\":{\"data\":{\"__typename\":\"Track\",${fields.joinToString(",")}}}}"
    }

    private fun answer(vararg items: String) =
        "{\"data\":{\"searchV2\":{\"tracksV2\":{\"items\":[${items.joinToString(",")}]}}}}"

    // ---- Pathfinder search ------------------------------------------------

    @Test
    fun `Spotify's top hit for a shared title is skipped when it is another song`() {
        val body = answer(
            item("spotify:track:other", "Rang De", listOf("Someone Else"), "A Different Film"),
            item("spotify:track:right", "Rang De", listOf("Ramajogayya Sastri", "Ramya Behara", "A Composer"), "The Right Film"),
        )
        val hit = SpotifyTrackSearch.bestMatch(SpotifyTrackSearch.parse(body), title, artist)
        assertEquals("spotify:track:right", hit?.uri)
        // The hit carries Spotify's own credits, not the ones asked for, so the repository's
        // re-check compares two different things.
        assertEquals(listOf("Ramajogayya Sastri", "Ramya Behara", "A Composer"), hit?.artists)
        assertEquals("The Right Film", hit?.album)
    }

    @Test
    fun `no hit at all when none of Spotify's results is the track`() {
        val body = answer(
            item("spotify:track:a", "Rang De", listOf("Someone Else")),
            item("spotify:track:b", "Rang De Basanti", listOf("Ramajogayya Sastri", "Ramya Behara")),
        )
        assertNull(SpotifyTrackSearch.bestMatch(SpotifyTrackSearch.parse(body), title, artist))
    }

    @Test
    fun `a hit whose name or artists can't be read is not trusted`() {
        val body = answer(
            item("spotify:track:noname", null, listOf("Ramajogayya Sastri", "Ramya Behara")),
            item("spotify:track:noartists", "Rang De", null),
            item(null, "Rang De", listOf("Ramajogayya Sastri", "Ramya Behara")),
        )
        assertEquals(emptyList(), SpotifyTrackSearch.parse(body))
    }

    @Test
    fun `a hit with only an id still gets a track uri`() {
        val body = answer(
            "{\"item\":{\"data\":{\"id\":\"abc\",\"name\":\"Rang De\"," +
                "\"artists\":{\"items\":[{\"profile\":{\"name\":\"Ramya Behara\"}}]}}}}",
        )
        assertEquals("spotify:track:abc", SpotifyTrackSearch.parse(body).single().uri)
    }

    @Test
    fun `an unreadable answer is no hits`() {
        assertEquals(emptyList(), SpotifyTrackSearch.parse("not json"))
        assertEquals(emptyList(), SpotifyTrackSearch.parse("{\"data\":null}"))
    }

    // ---- The repository's re-check ---------------------------------------

    private fun clip(title: String?, artist: String?, album: String? = null) =
        CanvasArtwork(url = "https://example.invalid/clip.mp4", title = title, artist = artist, album = album)

    @Test
    fun `a clip that doesn't say what it is is rejected`() {
        assertFalse(clip(null, artist).matches(title, artist, null))
        assertFalse(clip(title, null).matches(title, artist, null))
        assertTrue(clip(title, "Ramya Behara, Ramajogayya Sastri").matches(title, artist, null))
    }

    @Test
    fun `another singer's song of the same name is rejected`() {
        assertFalse(clip("Rang De", "Someone Else").matches(title, artist, null))
        assertFalse(clip("Rang De", "Ramya Behara").matches(title, artist, null))
    }

    @Test
    fun `titles in a non-Latin script are compared, not folded to nothing`() {
        val telugu = "రంగ్ దే"
        val other = "నిదుర"
        assertNotEquals("", telugu.normalizeForMatch())
        assertNotEquals(telugu.normalizeForMatch(), other.normalizeForMatch())
        assertTrue(clip(telugu, "Ramya Behara").matches(telugu, "Ramya Behara", null))
        assertFalse(clip(other, "Ramya Behara").matches(telugu, "Ramya Behara", null))
        // Artists too: a credit written in another script is not "nobody".
        assertFalse(clip("Rang De", "Мета").matches("Rang De", "Мата", null))
    }

    @Test
    fun `Latin folding still ignores case, accents and punctuation`() {
        assertEquals("beyonce crazy in love feat jay z", "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)".normalizeForMatch())
        assertTrue(canvasTrackMatches("RANG DE!", listOf("Ramya Behara", "Ramajogayya Sastri"), title, artist))
    }

    @Test
    fun `a blank request never matches`() {
        assertFalse(canvasTrackMatches("", listOf("Ramya Behara"), "", "Ramya Behara"))
        assertFalse(canvasTrackMatches("!!", listOf("Ramya Behara"), "??", "Ramya Behara"))
        assertFalse(clip("", "Ramya Behara").matches("", "Ramya Behara", null))
    }
}
