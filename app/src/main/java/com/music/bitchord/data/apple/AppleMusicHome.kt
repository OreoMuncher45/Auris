package com.music.bitchord.data.apple

import com.music.bitchord.data.canvas.AppleMusicCanvas
import com.music.bitchord.data.canvas.CANVAS_UA
import com.music.bitchord.data.canvas.canvasGet
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Apple Music's home, as Apple's own web player writes it.
 *
 * The catalog API's chart and search endpoints answer plenty, but they are not
 * Apple's home — Apple's home is a page of editorial shelves (a leading row of
 * curated playlists, then a row per genre, each holding *collections*), and the
 * editorial API refuses an anonymous token. The page itself does not: its
 * `serialized-server-data` blob carries every shelf, read exactly as the web
 * player reads it.
 *
 * So this is the same move the motion-artwork scraper already makes — read
 * Apple's own page rather than an endpoint that will not talk — and the shelves
 * that come back are Apple's, with collections as collections and songs as
 * songs. When the page cannot be read, [fallbackShelves] keeps the Home screen
 * alive from the catalog API, and is honest that it is a thinner page.
 *
 * A tapped collection opens its own page (see [AppleMusicCatalog]); a tapped
 * song plays through the normal source match. Apple's audio is DRM and is never
 * fetched here.
 */
object AppleMusicHome {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The genre rows the fallback draws, one collection row each. */
    private val GENRES = listOf(
        "Pop", "Hip-Hop", "R&B", "Rock", "Dance", "Electronic",
        "Indie", "Latin", "K-Pop", "Jazz", "Country", "Classical",
    )

    suspend fun shelves(limit: Int = 20): List<HomeShelf> = withContext(Dispatchers.IO) {
        val perRow = limit.coerceIn(1, 25)
        // The listener's own library first, when a token resolves: account
        // rows above the catalogue's, the way Apple's real home leads with
        // what is yours.
        val account = runCatching { AppleMusicLibrary.shelves(perRow) }.getOrDefault(emptyList())
        val editorial = runCatching { webShelves() }.getOrDefault(emptyList())
        val page = if (editorial.isNotEmpty()) {
            editorial
        } else {
            runCatching { fallbackShelves(perRow) }.getOrDefault(emptyList())
        }
        (account + page).distinctBy { it.title.lowercase() }
    }

    // ── Apple's own page ──────────────────────────────────────────────────

    /**
     * The storefront home, read from its `serialized-server-data`.
     *
     * `/browse` is the page the web player serves as home and the same one the
     * token scrape already loads; `/{storefront}` is where it lands. Either
     * answers, so both are tried before giving up.
     */
    private fun webShelves(): List<HomeShelf> {
        val storefront = AppleMusicCanvas.storefront
        val paths = listOf("$storefront/browse", storefront)
        for (path in paths) {
            val html = canvasGet(
                "https://music.apple.com/$path",
                mapOf("User-Agent" to CANVAS_UA),
            ) ?: continue
            val page = serverData(html) ?: continue
            val shelves = sections(page).mapNotNull(::shelf).filter { it.items.isNotEmpty() }
            if (shelves.isNotEmpty()) return shelves
        }
        return emptyList()
    }

    /**
     * The JSON Apple's server writes into the page.
     *
     * It is one `<script id="serialized-server-data">` element; the class is
     * found by id rather than by position, because the page carries other JSON
     * scripts and the first one is not this one.
     */
    private fun serverData(html: String): JsonObject? {
        val marker = html.indexOf("serialized-server-data")
        if (marker < 0) return null
        val open = html.indexOf('>', marker)
        if (open < 0) return null
        val close = html.indexOf("</script>", open)
        if (close < 0) return null
        val text = html.substring(open + 1, close).trim()
        if (!text.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun sections(page: JsonObject): List<JsonObject> =
        page["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("sections")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    /** One `section` as a shelf, or null for a kind that is not a row of cards. */
    internal fun shelf(section: JsonObject): HomeShelf? {
        val kind = section.str("itemKind") ?: return null
        // The lockup kinds Apple's browse pages use; link lists and page
        // headers are not rows.
        val wide = when (kind) {
            "squareLockup" -> false
            "flowcaseLockup", "horizontalLockup", "verticalVideoLockup" -> true
            "trackLockup" -> false
            "brickLockup" -> true
            else -> return null
        }
        // A video shelf of station promos is not what this app can open; only
        // real clips would earn a place, and none here is a collection.
        if (kind == "verticalVideoLockup") return null
        val title = section.obj("header")?.obj("item")?.obj("titleLink")?.str("title")
            // A hero writes no heading; naming it keeps the row from drawing
            // an empty one.
            ?.takeIf { it.isNotBlank() } ?: "Featured"
        val items = mutableListOf<ShelfItem>()
        val seen = HashSet<String>()
        for (item in section.arr("items").orEmpty()) {
            val tile = item as? JsonObject ?: continue
            // A hero writes its title itself; a lockup keeps it behind a link.
            val name = tile.str("title")
                ?: tile.arr("titleLinks")?.firstOrNull()?.jsonObject?.str("title")
                ?: continue
            val descriptor = tile.obj("contentDescriptor") ?: continue
            val kind = descriptor.str("kind") ?: continue
            val id = descriptor.obj("identifiers")?.str("storeAdamID")
                ?: descriptor.str("identifiers.storeAdamID")
            if (id.isNullOrBlank()) continue
            val art = tile.obj("artwork")?.obj("dictionary")?.str("url")
            val artwork = art?.let { fill(it, if (wide) 800 to 450 else 600 to 600) }
            val subtitle = tile.arr("subtitleLinks")
                ?.mapNotNull { (it as? JsonObject)?.str("title") }
                ?.joinToString(", ")
                .orEmpty()
            val shelfItem = when (kind) {
                "album" -> ShelfItem(
                    title = name, subtitle = subtitle, thumbnailUrl = artwork,
                    videoId = null, browseId = AppleMusicSearch.ALBUM_PREFIX + id,
                )
                "playlist" -> ShelfItem(
                    title = name, subtitle = subtitle, thumbnailUrl = artwork,
                    videoId = null, browseId = AppleMusicSearch.PLAYLIST_PREFIX + id,
                )
                "song" -> ShelfItem(
                    title = name, subtitle = subtitle, thumbnailUrl = artwork,
                    videoId = AppleMusicSearch.SONG_PREFIX + id, browseId = null,
                )
                // Radio stations and music videos are not collections this app
                // opens; skipped rather than drawn as a row that cannot.
                else -> continue
            }
            if (!seen.add(id)) continue
            items += shelfItem
        }
        return HomeShelf(title = title, items = items)
    }

    // ── The catalog-API fallback ──────────────────────────────────────────

    /**
     * A thinner page from the catalog API, for when Apple's own page cannot be
     * read: the charts, then one collection row per genre.
     *
     * Genre rows hold **playlists** — collections, the shape Apple's own genre
     * rows have — not songs, which is what a bare search would hand back.
     */
    private suspend fun fallbackShelves(limit: Int): List<HomeShelf> {
        val charts = runCatching { chartShelves(limit) }.getOrDefault(emptyList())
        val genres = coroutineScope {
            GENRES.map { genre ->
                async { runCatching { genreShelf(genre, limit) }.getOrNull() }
            }.awaitAll()
        }
        return (charts + genres.filterNotNull()).distinctBy { it.title.lowercase() }
    }

    private suspend fun chartShelves(limit: Int): List<HomeShelf> {
        val body = AppleMusicApi.get(
            "charts",
            mapOf("types" to "songs,albums,playlists", "limit" to limit.toString()),
        ) ?: return emptyList()
        val results = body["results"]?.jsonObject ?: return emptyList()
        return listOfNotNull(
            songShelf(results, "songs", "Top Songs"),
            cardShelf(results, "albums", "Top Albums", BrowseType.ALBUM, AppleMusicSearch.ALBUM_PREFIX),
            cardShelf(results, "playlists", "Top Playlists", BrowseType.PLAYLIST, AppleMusicSearch.PLAYLIST_PREFIX),
        )
    }

    /** A genre's playlists: a row of collections, not of songs. */
    private suspend fun genreShelf(genre: String, limit: Int): HomeShelf? {
        val body = AppleMusicApi.search(genre, listOf("playlists"), limit) ?: return null
        val results = body["results"]?.jsonObject ?: return null
        return cardShelf(results, "playlists", genre, BrowseType.PLAYLIST, AppleMusicSearch.PLAYLIST_PREFIX)
    }

    /**
     * The rows under [key].
     *
     * A **chart** lists one entry per chart in an array whose first element
     * carries `data`; a **search** puts `data` straight on the object. Both
     * shapes are read here so a row builder does not care which it came from.
     */
    private fun chart(results: JsonObject, key: String): List<JsonObject> {
        val node = results[key] ?: return emptyList()
        val data = (node as? JsonArray)?.firstOrNull()?.jsonObject?.get("data")
            ?: (node as? JsonObject)?.get("data")
            ?: return emptyList()
        return data.jsonArray.mapNotNull { it as? JsonObject }
    }

    private fun songShelf(results: JsonObject, key: String, title: String): HomeShelf? {
        val items = chart(results, key).mapNotNull(AppleMusicSearch::song).map { song ->
            ShelfItem(
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                videoId = song.videoId,
                browseId = null,
            )
        }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = title, items = it) }
    }

    private fun cardShelf(
        results: JsonObject,
        key: String,
        title: String,
        type: BrowseType,
        prefix: String,
    ): HomeShelf? {
        val items = chart(results, key)
            .mapNotNull { AppleMusicSearch.browse(it, type, prefix) }
            .map { item ->
                ShelfItem(
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    videoId = null,
                    browseId = item.browseId,
                )
            }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = title, items = it) }
    }

    /**
     * Apple's artwork as `w`x`h`.
     *
     * Its `{c}` is the crop: `sr` fills the box and trims the overflow, so the
     * picture arrives at the shape it was asked for rather than stretched. A
     * template without placeholders is taken as it comes.
     */
    private fun fill(template: String, size: Pair<Int, Int>): String? {
        if (!template.startsWith("https://")) return null
        if (!template.contains("{w}")) return template
        val (w, h) = size
        return template
            .replace("{w}", w.toString())
            .replace("{h}", h.toString())
            .replace("{c}", "sr")
            .replace("{f}", "webp")
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
