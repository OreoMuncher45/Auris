package com.music.bitchord.data.podcast

import com.music.bitchord.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node

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
    suspend fun search(term: String, limit: Int = 50, country: String = "US"): List<ItunesShow> {        val body = client.get(SEARCH) {
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

    /** Fetch one show by collectionId — used when a saved show is opened but the search cache is cold. */
    suspend fun lookup(collectionId: String, country: String = "US"): ItunesShow? {
        val body = client.get("https://itunes.apple.com/lookup") {
            parameter("id", collectionId)
            parameter("entity", "podcast")
            parameter("country", country)
        }.bodyAsText()
        val root = jsonFormat.parseToJsonElement(body).jsonObject
        return root["results"]?.jsonArray?.firstOrNull()?.let { (it as? JsonObject)?.toShow() }
    }

    /**
     * Every episode of a show, parsed from its RSS (or Atom) feed.
     *
     * All of them, not a page: a podcast's back catalogue is the point of a
     * podcast, and a "latest 100" cut off exactly the episodes somebody
     * catching up wants. The feed is read with a real XML parser rather than
     * a regex — feeds carry entity-escaped titles, CDATA and namespace
     * prefixes that a pattern match mangles.
     *
     * Only entries that carry an audio enclosure become playable rows; each
     * row's playback id is the enclosure URL itself, which the player treats
     * as a direct stream. The list comes back newest-first, ordered by the
     * item's own date rather than by the order the feed happened to list it
     * in, so "oldest first" is a reverse of something already sorted.
     */
    suspend fun episodes(show: ItunesShow): List<Song> {
        val feed = runCatching { client.get(show.feedUrl).bodyAsText() }.getOrNull() ?: return emptyList()
        return runCatching { parseEpisodes(feed, show) }.getOrNull() ?: emptyList()
    }

    /**
     * The feed, parsed into playable episodes — newest first.
     *
     * Split out from [episodes] so the parsing can be tested against real feed
     * shapes without a network round trip.
     */
    internal fun parseEpisodes(feed: String, show: ItunesShow): List<Song> {
        val items = parseFeedItems(feed)
        val songs = items.mapNotNull { item -> item.toSong(show) }
        // Newest first by the item's own date, where it gave one. Ties and
        // undated items keep the feed's own order (the sort is stable).
        return if (songs.any { it.epochMs != null }) {
            songs.sortedWith(compareByDescending { it.epochMs ?: Long.MIN_VALUE }).map { it.song }
        } else {
            songs.map { it.song }
        }
    }

    /** A parsed feed entry, before it is shaped into a [Song]. */
    private data class FeedItem(
        val title: String?,
        val enclosureUrl: String?,
        val enclosureType: String?,
        val published: String?,
        val duration: String?,
        val epochMs: Long?,
    )

    /** A [Song] with the epoch its date parsed to, for ordering only. */
    private data class DatedSong(val song: Song, val epochMs: Long?)

    private fun FeedItem.toSong(show: ItunesShow): DatedSong? {
        val url = enclosureUrl?.takeIf { it.isNotBlank() } ?: return null
        val type = enclosureType.orEmpty()
        val isAudio = type.contains("audio", ignoreCase = true) ||
            url.endsWith(".mp3", ignoreCase = true) || url.endsWith(".m4a", ignoreCase = true) ||
            url.endsWith(".aac", ignoreCase = true) || url.endsWith(".ogg", ignoreCase = true)
        if (!isAudio) return null
        val song = Song(
            videoId = url,
            title = title?.takeIf { it.isNotBlank() } ?: "Episode",
            artist = show.name,
            thumbnailUrl = show.artworkUrl,
            durationText = duration,
            publishedDate = published,
        )
        return DatedSong(song, epochMs)
    }

    /**
     * The `<item>` (RSS) and `<entry>` (Atom) elements of a feed.
     *
     * Namespaces are off on purpose: the feed's element names are what they
     * are — `itunes:duration`, `content:encoded`, `dc:creator` — and reading
     * them by their literal name is simpler and no more fragile than tracking
     * which prefix maps to which URI in this particular feed.
     */
    private fun parseFeedItems(xml: String): List<FeedItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // A podcast feed is untrusted input: no doctypes, no external
            // entities, no network fetches from inside the parser.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setExpandEntityReferences(false) }
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = document.documentElement ?: return emptyList()
        val items = mutableListOf<FeedItem>()
        collectItems(root, items)
        return items
    }

    private fun collectItems(node: Node, out: MutableList<FeedItem>) {
        if (node is Element) {
            when (node.tagName.lowercase(Locale.ROOT)) {
                "item", "entry" -> {
                    out.add(parseItem(node))
                    return
                }
            }
        }
        var child = node.firstChild
        while (child != null) {
            collectItems(child, out)
            child = child.nextSibling
        }
    }

    private fun parseItem(item: Element): FeedItem {
        var title: String? = null
        var pubDate: String? = null
        var published: String? = null
        var duration: String? = null
        var enclosureUrl: String? = null
        var enclosureType: String? = null

        var child = item.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.tagName.lowercase(Locale.ROOT)) {
                    "title", "itunes:title" -> title = title ?: text(child)
                    "pubdate" -> pubDate = pubDate ?: text(child)
                    "published", "updated" -> published = published ?: text(child)
                    "itunes:duration", "duration" -> duration = duration ?: text(child)
                    "enclosure" -> {
                        val url = child.getAttribute("url").takeIf { it.isNotBlank() }
                        if (url != null && enclosureUrl == null) {
                            enclosureUrl = url
                            enclosureType = child.getAttribute("type").takeIf { it.isNotBlank() }
                        }
                    }
                    "link" -> {
                        // Atom: <link rel="enclosure" href=... type=...>
                        val rel = child.getAttribute("rel")
                        if (rel.equals("enclosure", ignoreCase = true) && enclosureUrl == null) {
                            val href = child.getAttribute("href").takeIf { it.isNotBlank() }
                            if (href != null) {
                                enclosureUrl = href
                                enclosureType = child.getAttribute("type").takeIf { it.isNotBlank() }
                            }
                        }
                    }
                }
            }
            child = child.nextSibling
        }

        val date = pubDate ?: published
        return FeedItem(
            title = title?.trim()?.takeIf { it.isNotBlank() },
            enclosureUrl = enclosureUrl,
            enclosureType = enclosureType,
            published = date?.trim()?.takeIf { it.isNotBlank() },
            duration = duration?.trim()?.takeIf { it.isNotBlank() }?.let(::formatDuration),
            epochMs = date?.let(::parseEpochMs),
        )
    }

    /** The text of an element, entity references decoded by the parser. */
    private fun text(element: Element): String = element.textContent.orEmpty()

    /**
     * A feed date as epoch millis, or null when it is in no shape this
     * understands. RSS uses RFC 1123 (`Tue, 15 Oct 2024 10:00:00 +0000`);
     * Atom uses ISO 8601 (`2024-10-15T10:00:00Z`); some feeds omit the zone
     * or the seconds, which [DateTimeFormatter.RFC_1123_DATE_TIME] tolerates.
     */
    private fun parseEpochMs(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        // Atom / ISO 8601.
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(value).toInstant().toEpochMilli() }
        // RSS / RFC 1123, and the common variants without the day name.
        runCatching { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (pattern in DATE_PATTERNS) {
            runCatching {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
                return ZonedDateTime.parse(value, formatter).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private val DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm zzz",
        "dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

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

    /** RSS <itunes:duration> is "H:MM:SS", "MM:SS" or seconds — normalise to M:SS / H:MM:SS. */
    private fun formatDuration(raw: String): String? {
        val parts = raw.split(":")
        return when (parts.size) {
            1 -> raw.trim().toLongOrNull()?.let { formatMs(it * 1000) }
            2 -> "${parts[0].trim().toIntOrNull() ?: 0}:${parts[1].trim().padStart(2, '0')}"
            3 -> "${parts[0].trim()}:${parts[1].trim().padStart(2, '0')}:${parts[2].trim().padStart(2, '0')}"
            else -> null
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
