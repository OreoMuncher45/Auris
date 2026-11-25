package com.music.bitchord.data.apple

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple Music album and playlist pages.
 *
 * A card on Apple's home opens on its Apple id; this reads that id's tracks
 * from `amp-api` and maps them to the page the rest of the app already draws,
 * so an Apple release looks like any other. The rows carry `apple:song:<id>`
 * and play through the normal source match — Apple's audio is DRM and is not
 * fetched here.
 */
object AppleMusicCatalog {

    suspend fun detail(
        browseId: String,
        fallbackTitle: String = "",
        fallbackSubtitle: String = "",
        fallbackThumbnail: String? = null,
    ): DetailPage? = withContext(Dispatchers.IO) {
        val kind = when {
            browseId.startsWith(AppleMusicSearch.ALBUM_PREFIX) -> "albums"
            browseId.startsWith(AppleMusicSearch.PLAYLIST_PREFIX) -> "playlists"
            else -> return@withContext null
        }
        val id = browseId.substringAfterLast(':')
        if (id.isEmpty()) return@withContext null
        val body = AppleMusicApi.get("$kind/$id", mapOf("include" to "tracks"))
            ?: return@withContext null
        val data = body["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@withContext null
        val attributes = data["attributes"]?.jsonObject ?: return@withContext null
        val songs = data["relationships"]?.jsonObject
            ?.get("tracks")?.jsonObject
            ?.get("data")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull(AppleMusicSearch::song)
            .orEmpty()
        DetailPage(
            browseId = browseId,
            title = attributes["name"]?.jsonPrimitive?.contentOrNull ?: fallbackTitle,
            subtitle = attributes["artistName"]?.jsonPrimitive?.contentOrNull
                ?: attributes["curatorName"]?.jsonPrimitive?.contentOrNull
                ?: fallbackSubtitle,
            thumbnailUrl = AppleMusicSearch.artwork(attributes, 720) ?: fallbackThumbnail,
            songs = UiState.Success(songs),
            type = if (kind == "albums") BrowseType.ALBUM else BrowseType.PLAYLIST,
        )
    }
}