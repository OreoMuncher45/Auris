package com.music.bitchord.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the web player's own track search (Pathfinder `searchTracks`) and picks the hit that is
 * really the track asked for.
 *
 * Spotify ranks a free-text search by popularity as much as by fit, so its first result for a
 * common title is often another song with the same name: a "Rang De" by different singers, from
 * a different film. Trusting that first result and labelling it with the title and artist that
 * were *asked* for is what let another song's Canvas through, because every later check then
 * compared the request with itself. So each hit's own name and credits are read back here and
 * held to the same exact match as the REST search ([canvasTrackMatches]). A hit whose name or
 * artists can't be read is not trusted at all: no canvas beats the wrong one.
 *
 * Kept free of any HTTP client, like [SpotifyCanvasQuery], so it can be tested without a network.
 */
object SpotifyTrackSearch {

    /** One track out of a search answer, as Spotify credits it. */
    data class Hit(
        val uri: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Every readable track in a `searchTracks` answer, in Spotify's order:
     * `data.searchV2.tracksV2.items[].item.data`, carrying `uri` (or `id`), `name`,
     * `artists.items[].profile.name` and `albumOfTrack.name`. Hits missing a name or any
     * artist are dropped rather than guessed at.
     */
    fun parse(body: String): List<Hit> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val items = root.obj("data")?.obj("searchV2")?.obj("tracksV2")?.get("items") as? JsonArray
            ?: return emptyList()
        return items.mapNotNull { entry ->
            val track = (entry as? JsonObject)?.obj("item")?.obj("data") ?: return@mapNotNull null
            val uri = track.text("uri")?.takeIf { it.startsWith("spotify:track:") }
                ?: track.text("id")?.let { "spotify:track:$it" }
                ?: return@mapNotNull null
            val title = track.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artists = (track.obj("artists")?.get("items") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.obj("profile")?.text("name") }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (artists.isEmpty()) return@mapNotNull null
            Hit(uri, title, artists, track.obj("albumOfTrack")?.text("name"))
        }
    }

    /** The first hit in [hits] that is exactly [title] by every artist in [artist], or null. */
    fun bestMatch(hits: List<Hit>, title: String, artist: String): Hit? =
        hits.firstOrNull { canvasTrackMatches(it.title, it.artists, title, artist) }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
