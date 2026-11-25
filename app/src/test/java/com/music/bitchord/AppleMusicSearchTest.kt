package com.music.bitchord

import com.music.bitchord.data.apple.AppleMusicSearch
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple's catalog search JSON mapped onto the app's rows: ids keep their
 * provenance, artwork is asked for at a real size, and a non-track row keeps
 * the type the detail loader needs.
 */
class AppleMusicSearchTest {

    private val body = Json.parseToJsonElement(
        """
        {
          "results": {
            "songs": { "data": [ {
              "id": "1440833098",
              "attributes": {
                "name": "Shape of You",
                "artistName": "Ed Sheeran",
                "albumName": "Divide",
                "albumId": "1440934728",
                "artistId": "183313439",
                "durationInMillis": 233712,
                "artwork": {
                  "url": "https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg",
                  "width": 3000,
                  "height": 3000
                }
              }
            } ] },
            "albums": { "data": [ { "id": "1440934728", "attributes": {
              "name": "Divide",
              "artistName": "Ed Sheeran",
              "artwork": { "url": "https://x/{w}x{h}bb.jpg" }
            } } ] },
            "artists": { "data": [ { "id": "183313439", "attributes": {
              "name": "Ed Sheeran"
            } } ] },
            "playlists": { "data": [ { "id": "pl.abc", "attributes": {
              "name": "Today's Hits",
              "curatorName": "Apple Music"
            } } ] }
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun mapsSongsWithProvenance() {
        val song = AppleMusicSearch.parse(body)
            .filterIsInstance<SearchResult.Track>()
            .single()
            .song
        assertEquals("apple:song:1440833098", song.videoId)
        assertEquals("Shape of You", song.title)
        assertEquals("Ed Sheeran", song.artist)
        assertEquals("apple:album:1440934728", song.albumId)
        assertEquals("Divide", song.albumName)
        assertEquals("apple:artist:183313439", song.artistId)
        assertEquals("3:53", song.durationText)
        assertTrue(song.thumbnailUrl.orEmpty().contains("480x480"))
        assertTrue(!song.thumbnailUrl.orEmpty().contains("{"))
    }

    @Test
    fun mapsBrowseRowsWithTheirTypes() {
        val rows = AppleMusicSearch.parse(body)
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
        assertEquals(BrowseType.ALBUM, rows[0].type)
        assertEquals("apple:album:1440934728", rows[0].browseId)
        assertEquals("Divide", rows[0].title)
        assertEquals(BrowseType.ARTIST, rows[1].type)
        assertEquals("Ed Sheeran", rows[1].title)
        assertEquals(BrowseType.PLAYLIST, rows[2].type)
        assertEquals("Apple Music", rows[2].subtitle)
    }

    @Test
    fun anEmptyBodyHasNoRows() {
        assertTrue(AppleMusicSearch.parse(Json.parseToJsonElement("{}").jsonObject).isEmpty())
    }
}