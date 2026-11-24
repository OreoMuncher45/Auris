package com.music.bitchord.data.apple

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Apple Music's catalogue as Home shelves.
 *
 * Apple's own home is a page of editorial rows that no single account-free read
 * answers; what this builds instead is a page with the same *shape* — a page of
 * rows — from two account-free sources: Apple's **charts** (top songs, albums,
 * playlists) and a **genre row** per genre, each a catalog search narrowed to
 * that genre. Every row is real Apple catalogue content, and every card opens
 * on Apple's id.
 *
 * Rows are read concurrently: the page is one read of ~15 endpoints, and doing
 * them in sequence is the difference between a page that appears and a page
 * that fills in for half a minute.
 *
 * Apple's audio is DRM and is never fetched here; a tapped row plays through
 * the normal source match.
 */
object AppleMusicHome {

    /** The genre rows, in the order they appear under the charts. */
    private val GENRES = listOf(
        "Pop",
        "Hip-Hop",
        "R&B",
        "Rock",
        "Dance",
        "Electronic",
        "Indie",
        "Latin",
        "K-Pop",
        "Jazz",
        "Country",
        "Classical",
    )

    suspend fun shelves(limit: Int = 20): List<HomeShelf> = withContext(Dispatchers.IO) {
        val perRow = limit.coerceIn(1, 25)
        val charts = runCatching { chartShelves(perRow) }.getOrDefault(emptyList())
        val genres = coroutineScope {
            GENRES.map { genre -> async { runCatching { genreShelf(genre, perRow) }.getOrNull() } }
                .awaitAll()
        }
        (charts + genres.filterNotNull()).distinctBy { it.title.lowercase() }
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

    private suspend fun genreShelf(genre: String, limit: Int): HomeShelf? {
        val body = AppleMusicApi.search(genre, listOf("songs"), limit) ?: return null
        val results = body["results"]?.jsonObject ?: return null
        return songShelf(results, "songs", genre)
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
}