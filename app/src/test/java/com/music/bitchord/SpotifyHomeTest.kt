package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyBrowseCards
import com.music.bitchord.data.spotify.SpotifyGraphQL
import com.music.bitchord.data.spotify.SpotifyGraphQLException
import com.music.bitchord.data.spotify.SpotifyHomeFeedParser
import com.music.bitchord.data.spotify.SpotifyOps
import com.music.bitchord.data.spotify.SpotifyPages
import com.music.bitchord.data.spotify.browseIdOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotify home: what is read out of the web player's answers.
 *
 * These are the shapes the web player answers in, written down as JSON. They
 * are worth a test each because the answer is not a contract anybody reads:
 * a field that moves from `title.text` to `title.transformedLabel`, or a card
 * whose picture is under `visuals` rather than `coverArt`, empties a shelf
 * without any error at all — the page simply has less on it.
 */
class SpotifyHomeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(text: String) = json.parseToJsonElement(text).jsonObject

    private val homeAnswer = """
        {
          "home": {
            "greeting": {"transformedLabel": "Good evening"},
            "homeChips": [
              {"id": "music-chip", "label": {"transformedLabel": "Music"},
               "subChips": [{"id": "music-chip-following", "label": {"text": "Following"}}]},
              {"id": "podcasts-chip", "label": {"text": "Podcasts"}}
            ],
            "sectionContainer": {
              "sections": {
                "items": [
                  {
                    "data": {"title": {"transformedLabel": ""}},
                    "sectionItems": {"items": [
                      {"content": {"data": {
                        "uri": "spotify:playlist:tracks",
                        "name": "Liked Songs",
                        "images": {"items": [{"sources": [
                          {"url": "https://i.scdn.co/small", "width": 64},
                          {"url": "https://i.scdn.co/big", "width": 300}
                        ]}]}
                      }}},
                      {"content": {"data": {
                        "uri": "spotify:artist:abc",
                        "profile": {"name": "The Artist"},
                        "visuals": {"avatarImage": {"sources": [
                          {"url": "https://i.scdn.co/avatar", "width": 640}
                        ]}}
                      }}}
                    ]}
                  },
                  {
                    "data": {"title": {"transformedLabel": "Jump back in"}},
                    "sectionItems": {"items": [
                      {"content": {"data": {
                        "uri": "spotify:album:xyz",
                        "name": "The Album",
                        "artists": {"items": [{"profile": {"name": "The Band"}}]},
                        "coverArt": {"sources": [
                          {"url": "https://i.scdn.co/cover-small", "width": 64},
                          {"url": "https://i.scdn.co/cover-large", "width": 640}
                        ]}
                      }}}
                    ]}
                  }
                ]
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun theHomeFeedIsReadIntoItsGreetingChipsAndShelves() {
        val feed = SpotifyHomeFeedParser.parse(root(homeAnswer))

        assertEquals("Good evening", feed.greeting)
        // The podcasts chip is not drawn: it opens a shelf of shows, and
        // there is nothing here that can play one.
        assertEquals(listOf("music-chip"), feed.chips.map { it.id })
        assertEquals("Music", feed.chips.first().label)
        // The chips that open beside a chip are read with it: Spotify opens
        // *Following* beside *Music*, and the page has to know it can.
        assertEquals(listOf("music-chip-following"), feed.chips.first().subChips.map { it.id })
        assertEquals("Following", feed.chips.first().subChips.first().label)
    }

    @Test
    fun theLongFormChipsAreNotDrawn() {
        val answer = """
            {"home": {"homeChips": [
              {"id": "music-chip", "label": {"text": "Music"}},
              {"id": "podcasts-chip", "label": {"text": "Podcasts"},
               "subChips": [{"id": "podcasts-chip-following", "label": {"text": "Following"}}]},
              {"id": "audiobooks-chip", "label": {"text": "Audiobooks"}},
              {"id": "books-chip", "label": {"text": "Books"}}
            ]}}
        """.trimIndent()

        val feed = SpotifyHomeFeedParser.parse(root(answer))

        assertEquals(listOf("music-chip"), feed.chips.map { it.id })
    }

    @Test
    fun aShowIsNotACard() {
        // A card that opens a shelf of shows leads nowhere: there is no
        // episode player here, and a shelf that cannot be played is worse
        // than no shelf.
        val answer = """
            {"home": {"sectionContainer": {"sections": {"items": [
              {"data": {"title": {"text": "Shows"}}, "sectionItems": {"items": [
                {"content": {"data": {
                  "uri": "spotify:show:abc",
                  "name": "The Show",
                  "publisher": {"name": "A Publisher"}
                }}}
              ]}}
            ]}}}}
        """.trimIndent()

        val feed = SpotifyHomeFeedParser.parse(root(answer))

        assertTrue(feed.sections.isEmpty())
    }

    @Test
    fun theUntitledFirstShelfIsTheQuickAccessGrid() {
        val feed = SpotifyHomeFeedParser.parse(root(homeAnswer))

        // Spotify's web home opens with an untitled shelf of shortcuts, and
        // the shelves come after it.
        assertEquals(listOf("Liked Songs", "The Artist"), feed.shortcuts.map { it.title })
        assertEquals(listOf("Jump back in"), feed.shelves.map { it.title })
    }

    @Test
    fun aCardCarriesTheWidestPictureOffered() {
        val feed = SpotifyHomeFeedParser.parse(root(homeAnswer))
        val cards = feed.shortcuts + feed.shelves.flatMap { it.cards }

        // The sizes come in no fixed order — a 64px thumbnail can come first —
        // so the widest is the one that is kept.
        assertEquals("https://i.scdn.co/big", cards.first { it.title == "Liked Songs" }.imageUrl)
        assertEquals("https://i.scdn.co/avatar", cards.first { it.title == "The Artist" }.imageUrl)
        assertEquals("https://i.scdn.co/cover-large", cards.first { it.title == "The Album" }.imageUrl)
    }

    @Test
    fun aCardOpensThePageItNames() {
        val feed = SpotifyHomeFeedParser.parse(root(homeAnswer))
        val cards = feed.shortcuts + feed.shelves.flatMap { it.cards }

        // Liked Songs is not a playlist to Spotify: it is the collection every
        // listener has, under a uri of its own.
        assertEquals("spotify:playlist:liked", cards.first { it.title == "Liked Songs" }.browseIdOf())
        assertEquals("spotify:artist:abc", cards.first { it.title == "The Artist" }.browseIdOf())
        assertEquals("spotify:album:xyz", cards.first { it.title == "The Album" }.browseIdOf())
    }

    @Test
    fun aShelfWithNothingInItIsNotDrawn() {
        // A heading over a blank space is worse than no heading.
        val answer = """
            {"home": {"sectionContainer": {"sections": {"items": [
              {"data": {"title": {"text": "Empty"}}, "sectionItems": {"items": []}}
            ]}}}}
        """.trimIndent()

        val feed = SpotifyHomeFeedParser.parse(root(answer))

        assertTrue(feed.sections.isEmpty())
        assertTrue(feed.shelves.isEmpty())
    }

    @Test
    fun theBrowseGridIsRead() {
        val answer = """
            {"browseStart": {"sections": {"items": [
              {"sectionItems": {"items": [
                {"uri": "spotify:page:pop",
                 "content": {"data": {"cardRepresentation": {
                   "title": {"transformedLabel": "Pop"},
                   "artwork": {"sources": [
                     {"url": "https://i.scdn.co/pop-small", "width": 64},
                     {"url": "https://i.scdn.co/pop-large", "width": 640}
                   ]}
                 }}}},
                {"uri": "spotify:page:rock",
                 "content": {"data": {"data": {"cardRepresentation": {
                   "title": {"text": "Rock"}
                 }}}}}
              ]}}
            ]}}}
        """.trimIndent()

        val cards = SpotifyBrowseCards.parse(root(answer))

        assertEquals(listOf("Pop", "Rock"), cards.map { it.title })
        assertEquals("spotify:page:pop", cards.first().browseIdOf())
        assertEquals("https://i.scdn.co/pop-large", cards.first().imageUrl)
    }

    @Test
    fun theNewReleasesFeedIsRead() {
        val answer = """
            {"whatsNewFeedItems": {"items": [
              {"content": {"data": {
                "uri": "spotify:album:new1",
                "name": "New One",
                "artists": {"items": [{"profile": {"name": "Someone"}}]},
                "coverArt": {"sources": [{"url": "https://i.scdn.co/new", "width": 300}]}
              }}}
            ]}}
        """.trimIndent()

        val cards = SpotifyBrowseCards.parseWhatsNew(root(answer))

        assertEquals(1, cards.size)
        assertEquals("New One", cards.single().title)
        assertEquals("Someone", cards.single().subtitle)
        assertEquals("spotify:album:new1", cards.single().browseIdOf())
    }

    @Test
    fun theOperationsAreReadOutOfABundle() {
        // The bundles define their operations as `new X("name","kind","hash")`,
        // where the kind is the operation's type and the hash is what the
        // backend has the query filed under.
        val bundle = """
            var a = new Query("home","query","5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1");
            var b = new Mutation("addToLibrary","mutation","19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d");
            var c = new NotAnOperation("nothing","query","not-a-hash");
        """.trimIndent()

        val operations = SpotifyOps.parseBundleOperations(bundle)

        assertEquals(2, operations.size)
        assertEquals(
            "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1",
            operations["home"],
        )
        assertEquals(
            "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d",
            operations["addToLibrary"],
        )
    }

    @Test
    fun theBundleUrlsAreReadOutOfThePlayerPage() {
        // The page names its own bundles, and the refresh fetches each of
        // them. This is the read that killed the app: the pattern has no
        // capture group, and it was read with `groupValues[1]`, so the first
        // refresh after launch threw "No group 1" in a coroutine with no
        // handler and the process died a second after start.
        val page = """
            <script src="https://open.spotifycdn.com/cdn/build/web-player/vendor.js"></script>
            <script src="https://open.spotifycdn.com/cdn/build/web-player/home.js"></script>
            <script src="https://open.spotifycdn.com/cdn/build/web-player/other.js"></script>
        """.trimIndent()

        val urls = SpotifyOps.bundleUrls(page)

        assertEquals(3, urls.size)
        assertTrue(urls.all { it.startsWith("https://open.spotifycdn.com/cdn/build/web-player/") })
        assertTrue(urls.any { it.endsWith("/home.js") })
    }

    @Test
    fun aPageWithNoBundlesYieldsNoUrlsRatherThanThrowing() {
        assertEquals(emptyList<String>(), SpotifyOps.bundleUrls("<html><body>nothing here</body></html>"))
    }

    @Test
    fun anOperationThisBuildHasNeverHeardOfIsRefusedRatherThanSent() = runBlocking {
        // A request naming a hash the backend no longer has is refused, and a
        // request that is not made is not. So an unknown operation never
        // reaches the network.
        val failure = runCatching {
            SpotifyGraphQL.query("noSuchOperation", buildJsonObject { })
        }.exceptionOrNull()

        assertTrue(failure is SpotifyGraphQLException)
        assertTrue(failure!!.message!!.contains("noSuchOperation"))
    }

    @Test
    fun theHomeOperationIsKnown() {
        // The home feed is the whole page; an app that cannot name it has no
        // home at all.
        assertNotNull(SpotifyOps.hash("home"))
        assertNotNull(SpotifyOps.hash("browseAll"))
        assertNotNull(SpotifyOps.hash("getAlbum"))
        assertNotNull(SpotifyOps.hash("queryArtistOverview"))
        assertNotNull(SpotifyOps.hash("browsePage"))
        assertNull(SpotifyOps.hash("noSuchOperation"))
    }

    @Test
    fun anAlbumPageIsRead() {
        val answer = """
            {"albumUnion": {
              "name": "The Album",
              "artists": {"items": [{"profile": {"name": "The Band"}}]},
              "coverArt": {"sources": [
                {"url": "https://i.scdn.co/small", "width": 64},
                {"url": "https://i.scdn.co/large", "width": 640}
              ]},
              "tracksV2": {"items": [
                {"uid": "1", "track": {
                  "uri": "spotify:track:t1",
                  "name": "First Track",
                  "artists": {"items": [{"profile": {"name": "The Band"}}]},
                  "duration": {"totalMilliseconds": 210000},
                  "albumOfTrack": {"name": "The Album", "coverArt": {"sources": [
                    {"url": "https://i.scdn.co/track", "width": 300}
                  ]}}
                }}
              ]}
            }}
        """.trimIndent()

        val page = SpotifyPages.parseAlbumPage(root(answer))

        assertEquals("The Album", page.title)
        assertEquals("The Band", page.subtitle)
        assertEquals("https://i.scdn.co/large", page.imageUrl)
        assertEquals(1, page.tracks.size)
        assertEquals("First Track", page.tracks.single().title)
        assertEquals(210000, page.tracks.single().durationMs)
    }

    @Test
    fun anArtistPageIsRead() {
        val answer = """
            {"artistUnion": {
              "profile": {"name": "The Artist"},
              "visuals": {"avatarImage": {"sources": [
                {"url": "https://i.scdn.co/avatar", "width": 640}
              ]}},
              "discography": {
                "topTracks": {"items": [
                  {"uid": "1", "track": {
                    "uri": "spotify:track:t1",
                    "name": "Hit",
                    "artists": {"items": [{"profile": {"name": "The Artist"}}]}
                  }}
                ]},
                "popularReleasesAlbums": {"items": [
                  {"uri": "spotify:album:rel1", "name": "Release One",
                   "artists": {"items": [{"profile": {"name": "The Artist"}}]},
                   "coverArt": {"sources": [{"url": "https://i.scdn.co/rel", "width": 300}]}}
                ]}
              },
              "relatedContent": {"relatedArtists": {"items": [
                {"uri": "spotify:artist:other", "profile": {"name": "Another Artist"}}
              ]}}
            }}
        """.trimIndent()

        val page = SpotifyPages.parseArtistPage(root(answer))

        assertEquals("The Artist", page.title)
        assertEquals("https://i.scdn.co/avatar", page.imageUrl)
        assertEquals(listOf("Hit"), page.tracks.map { it.title })
        assertEquals(listOf("Popular releases", "Fans also like"), page.shelves.map { it.title })
        assertEquals("spotify:album:rel1", page.shelves.first().items.first().browseId)
        assertEquals("spotify:artist:other", page.shelves.last().items.first().browseId)
    }

    @Test
    fun aCategoryPageIsRead() {
        val answer = """
            {"browse": {
              "header": {"title": {"transformedLabel": "Pop"}},
              "sections": {"items": [
                {"data": {"title": {"transformedLabel": "Popular playlists"}},
                 "sectionItems": {"items": [
                   {"content": {"data": {
                     "uri": "spotify:playlist:hot",
                     "name": "Hot Hits",
                     "ownerV2": {"data": {"name": "Spotify"}}
                   }}}
                 ]}}
              ]}
            }}
        """.trimIndent()

        val page = SpotifyPages.parseCategoryPage(root(answer))

        assertEquals("Pop", page.title)
        assertTrue(page.tracks.isEmpty())
        assertEquals(listOf("Popular playlists"), page.shelves.map { it.title })
        assertEquals("spotify:playlist:hot", page.shelves.single().items.single().browseId)
        assertEquals("Spotify", page.shelves.single().items.single().subtitle)
    }
}
