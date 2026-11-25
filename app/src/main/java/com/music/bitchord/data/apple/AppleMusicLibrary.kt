package com.music.bitchord.data.apple

import com.music.bitchord.data.model.APPLE_LIBRARY_PREFIX
import com.music.bitchord.data.model.APPLE_SONG_PREFIX
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The listener's own Apple Music library.
 *
 * Two ways in, and only one of them is Apple's own API. A **pasted**
 * `Music-User-Token` reads Apple's `/v1/me/library` directly. With none, an
 * installed Apple Music **addon** reads its own `/library` routes, since it has
 * the Apple account linked server-side. With neither, there is no library and
 * nothing is drawn — an empty shelf is only ever a real empty shelf, never a
 * failure in disguise.
 *
 * Rows carry `apple:lib:<kind>:<id>` whichever route answered. Their audio is
 * still Apple's DRM and is never fetched; a row plays through the normal source
 * match like any other.
 */
object AppleMusicLibrary {

    const val PLAYLIST_PREFIX = APPLE_LIBRARY_PREFIX + "playlist:"
    const val ALBUM_PREFIX = APPLE_LIBRARY_PREFIX + "album:"

    /** Whether a browse id is one of the account library's. */
    fun isLibraryId(browseId: String): Boolean =
        browseId.startsWith(PLAYLIST_PREFIX) || browseId.startsWith(ALBUM_PREFIX)

    /**
     * The account's playlists, albums and songs as Home shelves, in that order.
     *
     * Empty when neither route resolves a credential or the library is
     * genuinely empty; the caller cannot tell those apart from here, and does
     * not need to — either way there is nothing to draw.
     */
    suspend fun shelves(limit: Int = 25): List<HomeShelf> = withContext(Dispatchers.IO) {
        if (AppleMusicAuth.pastedUserToken() != null) meShelves(limit) else addonShelves(limit)
    }

    /**
     * One of the account's playlists or albums, with its tracks.
     *
     * `apple:lib:playlist:<id>` reads the playlist's tracks; the album one is
     * the same under `albums`. Which backend answers follows the same rule as
     * [shelves].
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
        val songs = if (AppleMusicAuth.pastedUserToken() != null) {
            meSongs(kind, id)
        } else {
            addonSongs(kind, id)
        } ?: return@withContext null
        DetailPage(
            browseId = browseId,
            title = fallbackTitle,
            subtitle = fallbackSubtitle,
            thumbnailUrl = fallbackThumbnail,
            songs = UiState.Success(songs),
            type = if (kind == "albums") BrowseType.ALBUM else BrowseType.PLAYLIST,
        )
    }

    // ── Apple's own /v1/me/library ────────────────────────────────────────

    private suspend fun meShelves(limit: Int): List<HomeShelf> = listOfNotNull(
        meCollectionShelf("playlists", "Your Playlists", PLAYLIST_PREFIX, limit),
        meCollectionShelf("albums", "Your Albums", ALBUM_PREFIX, limit),
        meSongShelf(limit),
    )

    private suspend fun meCollectionShelf(
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
                browseId = prefix + id,
            )
        }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = title, items = it) }
    }

    private suspend fun meSongShelf(limit: Int): HomeShelf? {
        val body = AppleMusicApi.meGet("library/songs", mapOf("limit" to limit.toString()))
            ?: return null
        val items = data(body).mapNotNull(AppleMusicSearch::song).map(::shelfItemOf)
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = "Your Songs", items = it) }
    }

    private suspend fun meSongs(kind: String, id: String): List<Song>? {
        val body = AppleMusicApi.meGet("library/$kind/$id/tracks", mapOf("limit" to "100"))
            ?: return null
        return data(body).mapNotNull(AppleMusicSearch::song)
    }

    private fun data(body: JsonObject): List<JsonObject> =
        body["data"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()

    // ── The addon's own /library routes ───────────────────────────────────

    private suspend fun addonShelves(limit: Int): List<HomeShelf> {
        val (config, manifest) = AppleMusicAuth.appleAddon() ?: return emptyList()
        val library = manifest.library ?: return emptyList()
        val credential = config.settings[library.settingKey].orEmpty()
        val client = AddonClient(config.baseUrl)
        suspend fun page(collection: String) = runCatching {
            client.libraryCollection(collection, credential, library.authentication, limit = limit)
                .getOrNull()
        }.getOrNull()
        val playlists = page("playlists")?.items.orEmpty().mapNotNull { item ->
            collectionItem(item, PLAYLIST_PREFIX)
        }
        val albums = page("albums")?.items.orEmpty().mapNotNull { item ->
            collectionItem(item, ALBUM_PREFIX)
        }
        val songs = page("songs")?.items.orEmpty().map(::songOf).map(::shelfItemOf)
        return listOfNotNull(
            playlists.takeIf { it.isNotEmpty() }?.let { HomeShelf("Your Playlists", it) },
            albums.takeIf { it.isNotEmpty() }?.let { HomeShelf("Your Albums", it) },
            songs.takeIf { it.isNotEmpty() }?.let { HomeShelf("Your Songs", it) },
        )
    }

    private suspend fun addonSongs(kind: String, id: String): List<Song>? {
        val (config, manifest) = AppleMusicAuth.appleAddon() ?: return null
        val library = manifest.library ?: return null
        val credential = config.settings[library.settingKey].orEmpty()
        val page = runCatching {
            AddonClient(config.baseUrl)
                .libraryTracks(kind, id, credential, library.authentication, limit = 100)
                .getOrNull()
        }.getOrNull() ?: return null
        return page.items.map(::songOf)
    }

    private fun collectionItem(item: AddonLibraryItem, prefix: String): ShelfItem? {
        if (item.id.isBlank() || item.title.isBlank()) return null
        return ShelfItem(
            title = item.title,
            subtitle = item.artist,
            thumbnailUrl = item.artworkURL.takeIf { it.isNotBlank() },
            videoId = null,
            browseId = prefix + item.id,
        )
    }

    /** An addon-library song as this app's row: Apple's catalogue id, matched by name. */
    private fun songOf(item: AddonLibraryItem): Song = Song(
        videoId = APPLE_SONG_PREFIX + item.catalogueId,
        title = item.title,
        artist = item.artist,
        thumbnailUrl = item.artworkURL.takeIf { it.isNotBlank() },
        durationText = item.durationSec?.let { seconds ->
            val m = seconds / 60
            val s = seconds % 60
            "$m:${s.toString().padStart(2, '0')}"
        },
        albumName = item.album.takeIf { it.isNotBlank() },
    )

    private fun shelfItemOf(song: Song): ShelfItem = ShelfItem(
        title = song.title,
        subtitle = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        videoId = song.videoId,
        browseId = null,
    )
}
