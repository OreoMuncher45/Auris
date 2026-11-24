package com.music.bitchord.data.apple

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The listener's own Apple Music library, read with their `Music-User-Token`.
 *
 * Apple's account library is a separate endpoint family from the catalog —
 * `/v1/me/library/...` rather than `/v1/catalog/...` — and answers only under a
 * user token. When none resolves (neither pasted nor from an addon) every read
 * here returns nothing, so the Home screen simply shows no account rows rather
 * than an empty library pretending to be one.
 *
 * The rows carry `apple:lib:<kind>:<id>`: the library's own id, which is what
 * its track list is addressed by. Their audio is still Apple's DRM and is never
 * fetched; a row plays through the normal source match like any other.
 */
object AppleMusicLibrary {

    const val PLAYLIST_PREFIX = "apple:lib:playlist:"
    const val ALBUM_PREFIX = "apple:lib:album:"

    /** Whether a browse id is one of the account library's. */
    fun isLibraryId(browseId: String): Boolean =
        browseId.startsWith(PLAYLIST_PREFIX) || browseId.startsWith(ALBUM_PREFIX)

    /**
     * The account's playlists, albums and songs as Home shelves, in that order.
     *
     * Empty when no credential resolves or the library is genuinely empty; the
     * caller cannot tell those apart from here, and does not need to — either
     * way there is nothing to draw.
     */
    suspend fun shelves(limit: Int = 25): List<HomeShelf> = withContext(Dispatchers.IO) {
        listOfNotNull(
            collectionShelf("playlists", "Your Playlists", PLAYLIST_PREFIX, limit),
            collectionShelf("albums", "Your Albums", ALBUM_PREFIX, limit),
            songShelf(limit),
        )
    }

    private suspend fun collectionShelf(
        kind: String,
        title: String,
        prefix: String,
        limit: Int,
    ): HomeShelf? {
        val body = AppleMusicApi.meGet("library/$kind", mapOf("limit" to limit.toString()))
            ?: return null
        val items = data(body).mapNotNull { hit ->
            val id = hit["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val a = hit["attributes"]?.jsonObject ?: JsonObject(emptyMap())
            val name = a["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ShelfItem(
                title = name,
                subtitle = a["artistName"]?.jsonPrimitive?.contentOrNull
                    ?: a["curatorName"]?.jsonPrimitive?.contentOrNull
                    ?: "",
                thumbnailUrl = AppleMusicSearch.artwork(a),
                videoId = null,
                // The kind travels in the id's prefix, which is what the
                // detail loader reads to know which library route to open.
                browseId = prefix + id,
            )
        }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = title, items = it) }
    }

    private suspend fun songShelf(limit: Int): HomeShelf? {
        val body = AppleMusicApi.meGet("library/songs", mapOf("limit" to limit.toString()))
            ?: return null
        val items = data(body).mapNotNull(AppleMusicSearch::song).map { song ->
            ShelfItem(
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                videoId = song.videoId,
                browseId = null,
            )
        }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = "Your Songs", items = it) }
    }

    /**
     * One of the account's playlists or albums, with its tracks.
     *
     * `apple:lib:playlist:<id>` reads `/library/playlists/<id>/tracks`; the
     * album one is the same under `albums`. Apple's `/me` track answers are the
     * same song objects the catalog returns, so the mapping is shared.
     */
    suspend fun detail(
        browseId: String,
        fallbackTitle: String = "",
        fallbackSubtitle: String = "",
        fallbackThumbnail: String? = null,
    ): DetailPage? = withContext(Dispatchers.IO) {
        val kind = when {
            browseId.startsWith(PLAYLIST_PREFIX) -> "playlists"
            browseId.startsWith(ALBUM_PREFIX) -> "albums"
            else -> return@withContext null
        }
        val id = browseId.substringAfterLast(':')
        if (id.isEmpty()) return@withContext null
        val body = AppleMusicApi.meGet("library/$kind/$id/tracks", mapOf("limit" to "100"))
            ?: return@withContext null
        val songs = data(body).mapNotNull(AppleMusicSearch::song)
        DetailPage(
            browseId = browseId,
            title = fallbackTitle,
            subtitle = fallbackSubtitle,
            thumbnailUrl = fallbackThumbnail,
            songs = UiState.Success(songs),
            type = if (kind == "albums") BrowseType.ALBUM else BrowseType.PLAYLIST,
        )
    }

    private fun data(body: JsonObject): List<JsonObject> =
        body["data"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
}
