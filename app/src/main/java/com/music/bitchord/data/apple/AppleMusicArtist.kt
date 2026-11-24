package com.music.bitchord.data.apple

import com.music.bitchord.data.canvas.AppleMusicCanvas
import com.music.bitchord.data.canvas.CANVAS_UA
import com.music.bitchord.data.canvas.canvasGet
import com.music.bitchord.data.model.APPLE_SONG_PREFIX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * An Apple Music artist, read from the artist's own page.
 *
 * The catalog API's artist resource is thin — a name and a picture — and its
 * shelves (the discography, the top songs, the related artists) are the
 * editorial ones an anonymous token is refused. The page itself, though, is the
 * same `serialized-server-data` the home and a release page are read from, and
 * it carries every shelf the web player draws: **Albums**, **Singles & EPs**,
 * **Top Songs**, **Appears On**, **Similar Artists** and the rest.
 *
 * So a tap on an Apple artist opens its own page rather than falling through to
 * YouTube Music with an id it does not know — which is the 400 the artist card
 * used to answer with.
 *
 * The shelves are mapped by [AppleMusicHome.shelf], so an artist card is a card
 * is a card; the top songs are this page's track list, each a row that plays
 * through the normal source match. Apple's audio is DRM and is never fetched.
 */
object AppleMusicArtist {

    data class Page(
        val name: String?,
        val artwork: String?,
        val shelves: List<HomeShelf>,
        val topSongs: List<Song>,
    )

    suspend fun page(id: String): Page? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val storefront = AppleMusicCanvas.storefront
        for (path in listOf("$storefront/artist/-/$id", "artist/-/$id")) {
            val html = canvasGet(
                "https://music.apple.com/$path",
                mapOf("User-Agent" to CANVAS_UA),
            ) ?: continue
            val root = AppleMusicHome.serverData(html) ?: continue
            val sections = AppleMusicHome.sections(root)
            if (sections.isEmpty()) continue
            val header = sections.firstOrNull { it.itemKind() == "artistDetailHeader" }
                ?.array("items")
                ?.firstOrNull() as? JsonObject
            val shelves = sections.mapNotNull(AppleMusicHome::shelf)
                .filter { it.items.isNotEmpty() }
            val topSongs = sections.firstOrNull { it.itemKind() == "artistFeaturedContentAndTracks" }
                ?.array("items")
                ?.firstOrNull()?.jsonObject
                ?.array("tracks")
                ?.mapNotNull { hit -> (hit as? JsonObject)?.let(::song) }
                .orEmpty()
            return@withContext Page(
                name = header?.str("title")?.takeIf { it.isNotBlank() },
                artwork = header?.obj("artwork")?.obj("dictionary")?.str("url")
                    ?.let { AppleMusicHome.fill(it, 800 to 800) },
                shelves = shelves,
                topSongs = topSongs,
            )
        }
        null
    }

    /**
     * A top-song lockup as this app's row.
     *
     * The artist page's track shape is its own — a `title`, subtitle links for
     * the credit and the album, and a `contentDescriptor` naming the song —
     * rather than the catalog API's `attributes`. Only [Song.videoId] matters
     * to playback, and it keeps Apple's provenance.
     */
    internal fun song(hit: JsonObject): Song? {
        val descriptor = hit.obj("contentDescriptor") ?: return null
        if (descriptor.str("kind") != "song") return null
        val id = descriptor.obj("identifiers")?.str("storeAdamID")
            ?: descriptor.str("identifiers.storeAdamID")
            ?: return null
        val title = hit.str("title") ?: return null
        val links = hit.array("subtitleLinks").orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("title") }
        val millis = hit.int("duration")
        return Song(
            videoId = APPLE_SONG_PREFIX + id,
            title = title,
            artist = links.firstOrNull().orEmpty(),
            thumbnailUrl = hit.obj("artwork")?.obj("dictionary")?.str("url")
                ?.let { AppleMusicHome.fill(it, 600 to 600) },
            durationText = millis?.takeIf { it > 0 }?.let { ms ->
                val total = ms / 1000
                "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
            },
            albumName = hit.array("tertiaryLinks")?.firstOrNull()?.jsonObject?.str("title"),
        )
    }

    private fun JsonObject.itemKind(): String? = str("itemKind")
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
