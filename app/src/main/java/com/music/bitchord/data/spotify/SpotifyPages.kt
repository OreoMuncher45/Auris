package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.HomeShelf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The pages a card on Spotify's home opens.
 *
 * ## Why this exists
 *
 * The home feed is a page of cards, and a card that opens nothing is a dead
 * end. Three kinds of card need a page of their own: an album, an artist and
 * a category (a genre or a mood). They are read here, through the same client
 * and the same persisted operations as the feed itself, so the page a card
 * opens is the page Spotify's own web player would open.
 *
 * ## What a page is
 *
 * A title, a picture, a list of tracks and — for the pages that have them —
 * a list of shelves of cards. An album is its tracks; an artist is its top
 * tracks with its releases and its related artists beside them; a category is
 * shelves of playlists and albums and no tracks at all.
 */
internal object SpotifyPages {

    /**
     * How many tracks a page asks for in one request.
     *
     * Fifty is the most the operation takes in one page, and an album is
     * rarely longer than that. The rest of an album's tracks are not read:
     * a page that stops halfway is a page that lies about being complete, so
     * it is better to ask for the whole thing at once.
     */
    private const val PAGE_SIZE = 50

    /**
     * One page of Spotify: what it is called, what it looks like, and what is
     * on it.
     */
    data class Page(
        val title: String,
        val subtitle: String,
        val imageUrl: String?,
        val tracks: List<SpotifyTrack>,
        val shelves: List<HomeShelf>,
    )

    /**
     * An album: its own cover, its artists, and its tracks.
     *
     * The web player's own operation, with the web player's own variables —
     * the uri, an empty locale, and the page of tracks wanted.
     */
    suspend fun album(id: String): Page {
        val variables = kotlinx.serialization.json.buildJsonObject {
            put("uri", "spotify:album:$id")
            put("locale", "")
            put("offset", 0)
            put("limit", PAGE_SIZE)
        }
        return parseAlbumPage(SpotifyGraphQL.query("getAlbum", variables))
    }

    /**
     * An artist: their picture, their top tracks, their releases and the
     * artists their listeners also play.
     *
     * The three shelves are what Spotify's own artist page opens with, in the
     * same order. The top tracks are the page's track list; the releases and
     * the related artists are shelves of cards.
     */
    suspend fun artist(id: String): Page {
        val variables = kotlinx.serialization.json.buildJsonObject {
            put("uri", "spotify:artist:$id")
            put("locale", "")
            put("preReleaseV2", false)
            // Part of the operation's variable set, and the one this app was
            // not sending: a persisted query refused for a missing variable
            // reads as an artist Spotify does not have, which is how the
            // "fans also like" expansion — and with it Flow — came back empty.
            put("includeMusicVideoPreviews", false)
        }
        return parseArtistPage(SpotifyGraphQL.query("queryArtistOverview", variables))
    }

    /**
     * A category — a genre or a mood — as the catalogue's own page.
     *
     * A category has no tracks of its own: it is shelves of playlists and
     * albums, and each of those opens a page of its own. So the whole page is
     * shelves, and the title comes from the browse header.
     */
    suspend fun category(id: String): Page {
        val variables = kotlinx.serialization.json.buildJsonObject {
            put("uri", "spotify:page:$id")
            putJsonObject("pagePagination") {
                put("offset", 0)
                put("limit", 10)
            }
            putJsonObject("sectionPagination") {
                put("offset", 0)
                put("limit", 20)
            }
            put("browseEndUserIntegration", "INTEGRATION_WEB_PLAYER")
            put("includeEpisodeContentRatingsV2", false)
        }
        return parseCategoryPage(SpotifyGraphQL.query("browsePage", variables))
    }

    /**
     * An album's page, out of the web player's answer.
     *
     * The answer nests the album under `albumUnion`, its tracks under
     * `tracksV2`, and each track row wraps the track itself under `track`.
     */
    internal fun parseAlbumPage(root: JsonObject): Page {
        val album = root.obj("albumUnion")
            ?: throw IllegalStateException("Spotify album was not found")
        val tracks = album.obj("tracksV2")?.arr("items").orEmpty().mapNotNull { row ->
            val row = row.objOrNull() ?: return@mapNotNull null
            val track = row.obj("track") ?: row
            parseTrack(track)
        }
        return Page(
            title = album.str("name").orEmpty(),
            subtitle = album.artists(),
            imageUrl = album.obj("coverArt")?.arr("sources").bestImage(),
            tracks = tracks,
            shelves = emptyList(),
        )
    }

    /**
     * An artist's page, out of the web player's answer.
     *
     * The artist's own name and picture are under `profile` and `visuals`;
     * their top tracks and their releases are under `discography`; the
     * artists their listeners also play are under `relatedContent`.
     */
    internal fun parseArtistPage(root: JsonObject): Page {
        val artist = root.obj("artistUnion")
            ?: throw IllegalStateException("Spotify artist was not found")
        val discography = artist.obj("discography")
        val tracks = discography?.obj("topTracks")?.arr("items").orEmpty().mapNotNull { row ->
            val row = row.objOrNull() ?: return@mapNotNull null
            val track = row.obj("track") ?: row
            parseTrack(track)
        }
        val shelves = mutableListOf<HomeShelf>()
        discography?.obj("popularReleasesAlbums")?.arr("items").orEmpty()
            .mapNotNull { item -> item.objOrNull()?.let { card -> parseAlbumCard(card) } }
            .takeIf { it.isNotEmpty() }
            ?.let { cards -> shelves += HomeShelf(title = "Popular releases", items = cards.mapNotNull { it.item() }) }
        artist.obj("relatedContent")?.obj("relatedArtists")?.arr("items").orEmpty()
            .mapNotNull { item -> item.objOrNull()?.let { card -> parseArtistCard(card) } }
            .takeIf { it.isNotEmpty() }
            ?.let { cards -> shelves += HomeShelf(title = "Fans also like", items = cards.mapNotNull { it.item() }) }
        return Page(
            title = artist.obj("profile")?.str("name").orEmpty(),
            subtitle = "",
            imageUrl = artist.obj("visuals")?.obj("avatarImage")?.arr("sources").bestImage(),
            tracks = tracks,
            shelves = shelves,
        )
    }

    /**
     * A category's page, out of the web player's answer.
     *
     * The browse page holds its own shelves, and each shelf's entries are the
     * same cards the home feed holds — a card is a card, one shelf lower
     * down.
     */
    internal fun parseCategoryPage(root: JsonObject): Page {
        val browse = root.obj("browse")
            ?: throw IllegalStateException("Spotify category was not found")
        val shelves = browse.obj("sections")?.arr("items").orEmpty().mapNotNull { section ->
            val section = section.objOrNull() ?: return@mapNotNull null
            val data = section.obj("data")
            val title = data?.str("title.transformedLabel") ?: data?.str("title.text") ?: ""
            val cards = section.obj("sectionItems")?.arr("items").orEmpty()
                .mapNotNull { item -> SpotifyHomeFeedParser.parseCard(item) }
            if (cards.isEmpty()) null else HomeShelf(title = title, items = cards.mapNotNull { it.item() })
        }
        return Page(
            title = browse.obj("header")?.str("title.transformedLabel")
                ?: browse.obj("header")?.str("title.text")
                ?: "",
            subtitle = "",
            imageUrl = null,
            tracks = emptyList(),
            shelves = shelves,
        )
    }

    /**
     * One track, as the rest of the app wants it.
     *
     * The same shape the library reader produces, so a track from an album and
     * a track from a playlist are the same thing to everything downstream —
     * including the matcher that finds it on YouTube Music.
     */
    private fun parseTrack(data: JsonObject): SpotifyTrack? {
        val uri = data.str("uri") ?: return null
        // Only a *track* is a row. An album page can list an episode or a
        // book, and neither of those can be queued — see [SpotifyLongForm].
        if (!SpotifyLongForm.isMusicTrackUri(uri)) return null
        val title = data.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val album = data.obj("albumOfTrack")
        return SpotifyTrack(
            id = uri.substringAfterLast(':'),
            title = title,
            artist = data.artists().ifBlank { album?.str("name").orEmpty() },
            album = album?.str("name"),
            durationMs = (data.obj("duration") ?: data.obj("trackDuration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources").bestImage(),
        )
    }

    /** An album, as a card. */
    private fun parseAlbumCard(data: JsonObject): SpotifyCard? {
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:album:") } ?: return null
        val title = data.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = SpotifyCard.Kind.ALBUM,
            title = title,
            subtitle = data.artists(),
            imageUrl = data.obj("coverArt")?.arr("sources").bestImage(),
        )
    }

    /** An artist, as a card. */
    private fun parseArtistCard(data: JsonObject): SpotifyCard? {
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:artist:") } ?: return null
        val title = data.obj("profile")?.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = SpotifyCard.Kind.ARTIST,
            title = title,
            subtitle = "",
            imageUrl = data.obj("visuals")?.obj("avatarImage")?.arr("sources").bestImage(),
        )
    }

    /** The artists of a track or an album, as one line. */
    private fun JsonObject.artists(): String {
        val names = obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.objOrNull()?.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }
        if (names.isNotEmpty()) return names.joinToString(", ")
        return str("firstArtist.items.0.profile.name").orEmpty()
    }

    /** The widest picture offered, by the width the answer states. */
    private fun JsonArray?.bestImage(): String? =
        this?.mapNotNull { source ->
            val source = source.objOrNull() ?: return@mapNotNull null
            val url = source.str("url") ?: return@mapNotNull null
            url to (source.int("width") ?: 0)
        }?.maxByOrNull { it.second }?.first

}
