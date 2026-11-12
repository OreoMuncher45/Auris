package com.music.bitchord

import com.music.bitchord.data.spotify.parseLikedPage
import com.music.bitchord.data.spotify.parsePlaylistPage
import com.music.bitchord.data.spotify.parseTrackPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playlistsIncludeFolderLeaves() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:abc","data":{
                "__typename":"Playlist","name":"Road","ownerV2":{"data":{"name":"Amit"}},
                "images":{"items":[{"sources":[{"url":"https://i.scdn.co/x"}]}]}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val (items, total) = parsePlaylistPage(root)
        assertEquals(1, total)
        assertEquals("abc", items.single().id)
        assertEquals("Road", items.single().name)
        assertEquals("Amit", items.single().owner)
        assertEquals("https://i.scdn.co/x", items.single().imageUrl)
    }

    @Test
    fun aPlaylistWithoutAStatedTotalDoesNotClaimTheFirstPageIsTheWhole() {
        // The bug this guards: a page's own size was read as the playlist's
        // length, so a playlist of thirty tracks stopped after the first
        // short page — eight songs, and nothing after them.
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"items":[
              {"itemV2":{"data":{"name":"One","uri":"spotify:track:t1"}}},
              {"itemV2":{"data":{"name":"Two","uri":"spotify:track:t2"}}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val page = parseTrackPage(root)
        assertEquals(null, page.total)
        assertEquals(2, page.raw)
        assertEquals(2, page.items.size)
    }

    @Test
    fun aStatedTotalIsKept() {
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":30,"items":[
              {"itemV2":{"data":{"name":"One","uri":"spotify:track:t1"}}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        assertEquals(30, parseTrackPage(root).total)
    }

    @Test
    fun tracksReadTitleArtistAndDuration() {
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":1,"items":[
              {"itemV2":{"data":{
                "name":"Song","uri":"spotify:track:t1",
                "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
                "albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"https://img"}]}},
                "duration":{"totalMilliseconds":180000}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val track = parseTrackPage(root).items.single()
        assertEquals("t1", track.id)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(180000, track.durationMs)
        assertEquals("https://img", track.imageUrl)
    }

    @Test
    fun anEpisodeInAPlaylistIsNotARow() {
        // A playlist can hold an episode or a book, and neither of those can
        // be queued here. They are not rows.
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":3,"items":[
              {"itemV2":{"data":{"name":"Song","uri":"spotify:track:t1"}}},
              {"itemV2":{"data":{"name":"The Episode","uri":"spotify:episode:e1"}}},
              {"itemV2":{"data":{"name":"The Book","uri":"spotify:audiobook:b1"}}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject

        val page = parseTrackPage(root)

        assertEquals(1, page.items.size)
        assertEquals("Song", page.items.single().title)
        // The count is Spotify's own and still says what the page held.
        assertEquals(3, page.raw)
    }

    @Test
    fun anEpisodeInLikedSongsIsNotARow() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"library":{"tracks":{"items":[
              {"track":{"_uri":"spotify:track:t1","data":{"name":"Song"}}},
              {"track":{"_uri":"spotify:episode:e1","data":{"name":"The Episode"}}}
            ]}}}}}
            """.trimIndent(),
        ).jsonObject

        val page = parseLikedPage(root)

        assertEquals(1, page.items.size)
        assertEquals("Song", page.items.single().title)
    }
}
