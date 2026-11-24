package com.music.bitchord.data.apple

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Apple Music's catalogue as Home shelves.
 *
 * Built from Apple's own charts — top songs, top albums, top playlists — which
 * is the part of Apple's home a single anonymous read answers. The rows are
 * the same cards every other catalogue's home draws; only the source differs.
 *
 * A tapped album or playlist opens on its Apple id (the detail phase reads it),
 * and a tapped song plays through the normal source match — Apple's audio is
 * DRM and is never fetched here.
 */
object AppleMusicHome {

    suspend fun shelves(limit: Int = 20): List<HomeShelf> = withContext(Dispatchers.IO) {
        val body = AppleMusicApi.get(
            "charts",
            mapOf(
                "types" to "songs,albums,playlists",
                "limit" to limit.coerceIn(1, 25).toString(),
            ),
        ) ?: return@withContext emptyList()
        val results = body["results"]?.jsonObject ?: return@withContext emptyList()
        listOfNotNull(
            songShelf(results, "songs", "Top Songs"),
            cardShelf(results, "albums", "Top Albums", BrowseType.ALBUM, AppleMusicSearch.ALBUM_PREFIX),
            cardShelf(results, "playlists", "Top Playlists", BrowseType.PLAYLIST, AppleMusicSearch.PLAYLIST_PREFIX),
        )
    }

    /** Each chart entry is an array with one element whose `data` holds the rows. */
    private fun chart(results: JsonObject, key: String): List<JsonObject> =
        results[key]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("data")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

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