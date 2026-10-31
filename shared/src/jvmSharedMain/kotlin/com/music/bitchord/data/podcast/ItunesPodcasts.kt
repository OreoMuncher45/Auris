package com.music.bitchord.data.podcast

import com.music.bitchord.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One podcast show from the Apple Podcasts catalogue (iTunes Search API).
 *
 * The API is key-less and anonymous: a plain GET returns the show's name,
 * publisher, artwork, genre, episode count and its RSS feed URL.
 */
data class ItunesShow(
    val collectionId: String,
    val name: String,
    val artist: String,
    val artworkUrl: String?,
    val feedUrl: String,
    val genre: String?,
    val trackCount: Int,
)

/**
 * The Apple Podcasts catalogue via the iTunes Search API.
 *
 * YouTube Music's own podcast landing browses collapse to the generic Home
 * page for anonymous clients, and the library podcast feed is empty unless
 * the account follows shows — so the discoverable catalogue (categories and
 * recommendations) comes from here instead. Episode audio is streamed
 * straight from each show's RSS enclosure URL.
 */
object ItunesPodcasts {
    private val jsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        HttpClient(OkHttp) { expectSuccess = true }
    }

    private const val SEARCH = "https://itunes.apple.com/search"

    /** Browse categories shown on the podcasts page, one shelf each. */
    val CATEGORIES: List<Pair<String, String>> = listOf(
        "News" to "news",
        "Technology" to "technology",
        "True Crime" to "true crime",
        "Comedy" to "comedy",
        "Business" to "business",
        "Science" to "science",
        "Health" to "health fitness",
        "Sports" to "sports",
        "History" to "history",
        "Arts" to "arts",
        "Education" to "education",
        "TV & Film" to "tv film",
    )

    /** A page of shows matching [term] ("podcast" broadly, or a genre word). */
    suspend fun search(term: String, limit: Int = 20, country: String = "US"): List<ItunesShow> {
        val body = client.get(SEARCH) {
            parameter("term", term)
            parameter("media", "podcast")
            parameter("entity", "podcast")
            parameter("limit", limit.coerceIn(1, 200).toString())
            parameter("country", country)
        }.bodyAsText()
        val root = jsonFormat.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray.orEmpty()
        return results.mapNotNull { element -> (element as? JsonObject)?.toShow() }
    }

    /**
     * The latest episodes of a show, parsed from its RSS feed.
     *
     * Only `<item>` entries with an audio `<enclosure>` become playable rows;
     * each row's playback id is the enclosure URL itself, which the player
     * treats as a direct stream.
     */
    suspend fun episodes(show: ItunesShow, limit: Int = 50): List<Song> {
        val rss = runCatching { client.get(show.feedUrl).bodyAsText() }.getOrNull() ?: return emptyList()
        val items = Regex("<item[\\s>].*?</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(rss).map { it.value }.take(limit)
        val songs = mutableListOf<Song>()
        for (item in items) {
            val enclosureTag = Regex("<enclosure[^>]*>").find(item)?.value ?: continue
            val url = Regex("url\\s*=\\s*\"([^\"]+)\"").find(enclosureTag)?.groupValues?.get(1) ?: continue
            val type = Regex("type\\s*=\\s*\"([^\"]+)\"").find(enclosureTag)?.groupValues?.get(1).orEmpty()
            val isAudio = type.contains("audio", ignoreCase = true) ||
                url.endsWith(".mp3", ignoreCase = true) || url.endsWith(".m4a", ignoreCase = true)
            if (!isAudio) continue
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(item)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
            songs.add(Song(
                videoId = url,
                title = title?.takeIf { it.isNotBlank() } ?: "Episode",
                artist = show.name,
                thumbnailUrl = show.artworkUrl,
            ))
        }
        return songs
    }

    private fun JsonObject.toShow(): ItunesShow? {
        val id = string("collectionId") ?: return null
        val name = string("collectionName")?.takeIf { it.isNotBlank() } ?: return null
        return ItunesShow(
            collectionId = id,
            name = name,
            artist = string("artistName").orEmpty(),
            artworkUrl = string("artworkUrl600") ?: string("artworkUrl100"),
            feedUrl = string("feedUrl") ?: return null,
            genre = string("primaryGenreName"),
            trackCount = string("trackCount")?.toIntOrNull() ?: 0,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toString()

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
}
