package com.music.bitchord

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.spotify.SpotifySearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify's catalogue search, read out of the answers the web player sends.
 *
 * These are the shapes the web player's own readers dispatch on, written down
 * as JSON. They are worth a test each because the answer is not a contract
 * anybody reads: a section whose list arrives under `itemsV2` instead of
 * `items`, or an entity wrapped one level deeper than it was, empties the
 * page without any error at all — the search simply says Spotify has nothing.
 *
 * Every shape here produced exactly that: an answer that carried the tracks,
 * counted as carrying them, and drew none.
 */
class SpotifySearchTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun section(text: String) = json.parseToJsonElement(text).jsonObject

    /** A track as the web player's own track reader sees it. */
    private val liveTrack = """
        {
          "__typename": "Track",
          "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
          "name": "Shape of You",
          "artists": {"items": [{"profile": {"name": "Ed Sheeran"}}]},
          "albumOfTrack": {
            "name": "Divide",
            "coverArt": {"sources": [
              {"url": "https://i.scdn.co/small", "width": 64},
              {"url": "https://i.scdn.co/large", "width": 640}
            ]}
          },
          "duration": {"totalMilliseconds": 233712}
        }
    """

    private fun trackRows(text: String): List<SearchResult.Track> {
        val page = SpotifySearch.parse(section(text), SearchFilter.SONGS)
        return page.rows.filterIsInstance<SearchResult.Track>()
    }

    /** The list a section's entries may arrive under. */
    @Test
    fun `a section whose list is itemsV2 is read`() {
        val rows = trackRows(
            """
            {"tracksV2": {"itemsV2": [{"item": {"data": $liveTrack}, "matchedFields": ["Name"]}],
                          "totalCount": 1}}
            """,
        )
        assertEquals(1, rows.size)
        assertEquals("Shape of You", rows.first().song.title)
        assertEquals("Ed Sheeran", rows.first().song.artist)
    }

    /** The list a section's entries may arrive under, the other spelling. */
    @Test
    fun `a section whose list is items is read`() {
        val rows = trackRows(
            """
            {"tracksV2": {"items": [{"item": {"data": $liveTrack}, "matchedFields": ["Name"]}],
                          "totalCount": 1}}
            """,
        )
        assertEquals(1, rows.size)
    }

    /** A top result wraps its entity twice: under `item`, then under `data`. */
    @Test
    fun `a track among the top results is read`() {
        val rows = trackRows(
            """
            {"topResultsV2": {"itemsV2": [
              {"item": {"__typename": "TrackResponseWrapper", "data": $liveTrack},
               "matchedFields": ["Name"]}
            ]}}
            """,
        )
        assertEquals(1, rows.size)
        assertEquals("Shape of You", rows.first().song.title)
    }

    /** The older shape: a bare id, a name, and no uri anywhere. */
    @Test
    fun `a track with a bare id is read`() {
        val rows = trackRows(
            """
            {"tracksV2": {"items": [
              {"id": "4uLU6hMCjMI75M1A2tKUQC", "name": "Shape of You", "duration_ms": 233712,
               "artists": [{"name": "Ed Sheeran"}], "album": {"name": "Divide"}}
            ]}}
            """,
        )
        assertEquals(1, rows.size)
        assertEquals("Shape of You", rows.first().song.title)
        assertEquals("Ed Sheeran", rows.first().song.artist)
    }

    /** An entity under `data` with no wrapper around it at all. */
    @Test
    fun `a bare entity is read`() {
        val rows = trackRows("""{"tracksV2": {"items": [$liveTrack]}}""")
        assertEquals(1, rows.size)
    }

    /** The id is what the row is played by, so it has to be there. */
    @Test
    fun `the row carries Spotify's own id`() {
        val rows = trackRows(
            """{"tracksV2": {"items": [{"item": {"data": $liveTrack}}]}}""",
        )
        assertEquals("spotify:4uLU6hMCjMI75M1A2tKUQC", rows.first().song.videoId)
    }

    /**
     * A shelf inside the top results: the rows are one level further down, in
     * a list, under a section entity that names nothing but a title.
     *
     * The answer's own shelves put their tracks in `data.items[]`, and a walk
     * that stopped at the list found the shelf and nothing under it — which
     * is how an answer carrying eight tracks drew none.
     */
    @Test
    fun `a track inside a shelf of the top results is read`() {
        val rows = trackRows(
            """
            {"topResultsV2": {"itemsV2": [
              {"item": {"__typename": "SearchSectionEntity", "data": {
                 "title": {"transformedLabel": "Songs"},
                 "sectionType": "GENERIC",
                 "items": [{"item": {"__typename": "TrackResponseWrapper", "data": $liveTrack}}]
               }},
               "matchedFields": ["Name"]}
            ]}}
            """,
        )
        assertEquals(1, rows.size)
        assertEquals("Shape of You", rows.first().song.title)
    }

    /** The tab operation's own answer: a wrapper, then the entity under data. */
    @Test
    fun `the web player's track tab answer is read`() {
        val rows = trackRows(
            """
            {"tracksV2": {
               "items": [{"matchedFields": [], "item": {
                 "__typename": "TrackResponseWrapper",
                 "data": {
                   "__typename": "Track",
                   "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                   "id": "4uLU6hMCjMI75M1A2tKUQC",
                   "name": "Shape of You",
                   "duration": {"totalMilliseconds": 233712},
                   "contentRating": {"label": "EXPLICIT"},
                   "playability": {"playable": true, "reason": "PLAYABLE"},
                   "trackMediaType": "AUDIO",
                   "artists": {"items": [
                     {"uri": "spotify:artist:6UKW6lh6tcx8oQcumzXhn", "profile": {"name": "Ed Sheeran"}}
                   ]},
                   "albumOfTrack": {
                     "uri": "spotify:album:6kL09DaURb7rAo6DYk7F3",
                     "name": "Divide",
                     "coverArt": {"sources": [
                       {"url": "https://i.scdn.co/small", "width": 64},
                       {"url": "https://i.scdn.co/large", "width": 640}
                     ]}
                   }
                 }
               }}],
               "pagingInfo": {"limit": 20, "nextOffset": 20},
               "totalCount": 429
             }}
            """,
        )
        assertEquals(1, rows.size)
        assertEquals("Shape of You", rows.first().song.title)
        assertEquals("Ed Sheeran", rows.first().song.artist)
        assertEquals("Divide", rows.first().song.albumName)
    }

    /**
     * A page that found nothing says what arrived.
     *
     * The shape of the first entry is the one thing that can be acted on: it
     * is the difference between a parser looking in the wrong place and an
     * answer that is not what the parser expected.
     */
    @Test
    fun `an empty page names the shape it was given`() {
        val page = SpotifySearch.parse(
            section("""{"tracksV2": {"itemsV2": [{"mystery": {"deeper": 1}}]}}"""),
            SearchFilter.SONGS,
        )
        assertTrue(
            "the diagnostic should name the entry's shape: ${page.diagnostic}",
            page.diagnostic.contains("first entry"),
        )
        assertTrue(
            "the diagnostic should name the key it was looking under: ${page.diagnostic}",
            page.diagnostic.contains("mystery"),
        )
    }

    /** Every kind in one answer, each read from its own section. */
    @Test
    fun `albums artists and playlists are read beside the tracks`() {
        val page = SpotifySearch.parse(
            section(
                """
                {
                  "tracksV2": {"items": [{"item": {"data": $liveTrack}}]},
                  "albumsV2": {"items": [{"data": {
                    "__typename": "Album", "uri": "spotify:album:6kL09DaURb7rAo6DYk7F3",
                    "name": "Divide",
                    "artists": {"items": [{"profile": {"name": "Ed Sheeran"}}]},
                    "coverArt": {"sources": [{"url": "https://i.scdn.co/large", "width": 640}]}
                  }}]},
                  "artists": {"items": [{"data": {
                    "__typename": "Artist", "uri": "spotify:artist:6UKW6lh6tcx8oQcumzXhn",
                    "profile": {"name": "Ed Sheeran"},
                    "visuals": {"avatarImage": {"sources": [{"url": "https://i.scdn.co/artist", "width": 320}]}}
                  }}]},
                  "playlists": {"items": [{"data": {
                    "__typename": "Playlist", "uri": "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
                    "name": "Road Trip",
                    "ownerV2": {"data": {"__typename": "User", "name": "Someone"}},
                    "images": {"items": [{"sources": [{"url": "https://i.scdn.co/playlist", "width": 300}]}]}
                  }}]}
                }
                """,
            ),
            SearchFilter.ALL,
        )
        val tracks = page.rows.filterIsInstance<SearchResult.Track>()
        val browses = page.rows.filterIsInstance<SearchResult.Browse>()
        assertEquals(1, tracks.size)
        assertEquals(3, browses.size)
    }
}
