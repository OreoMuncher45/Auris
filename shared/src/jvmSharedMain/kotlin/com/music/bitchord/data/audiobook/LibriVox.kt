package com.music.bitchord.data.audiobook

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
import kotlinx.serialization.json.jsonPrimitive

/**
 * A book of public-domain recordings, read aloud.
 *
 * LibriVox is a volunteer library of audiobooks whose texts have fallen into
 * the public domain. What it offers is what a music app otherwise lacks:
 * something twenty hours long, with chapters, that nobody has to host for
 * free.
 */
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    /** Archive.org's own cover for the recording, not for the printed book. */
    val coverUrl: String?,
    val language: String?,
    val totalSeconds: Long?,
    val sectionCount: Int,
    val genres: List<String>,
)

/**
 * One chapter of a book.
 *
 * A chapter is a file, not a track: it has its own URL, its own length, and
 * no album behind it. It is played straight off the archive.
 */
data class AudiobookChapter(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val title: String,
    val audioUrl: String,
    val seconds: Long?,
    val reader: String?,
    /** The recording's cover, carried along so a chapter row can show it. */
    val coverUrl: String?,
)

/**
 * The LibriVox feed.
 *
 * The feed is plain JSON over HTTPS and needs no key, no account and no
 * cookie — which is why it can be read from a phone with no sign-in at all.
 */
object LibriVox {
    private val jsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        HttpClient(OkHttp) { expectSuccess = true }
    }

    private const val FEED = "https://librivox.org/api/feed/audiobooks/"

    /**
     * A page of books, newest first unless the feed says otherwise.
     *
     * A title search is a regex on LibriVox's side, so it is anchored and
     * escaped here: a query of "count of" finds every title that contains
     * those words, and a query of ".*" finds the whole library.
     */
    suspend fun books(
        limit: Int = 50,
        offset: Int = 0,
        title: String? = null,
        genre: String? = null,
    ): List<Audiobook> {
        val body = client.get(FEED) {
            parameter("format", "json")
            parameter("limit", limit.coerceIn(1, 100))
            parameter("offset", offset.coerceAtLeast(0))
            title?.takeIf { it.isNotBlank() }?.let { parameter("title", ".*${it.trim().escapeRegex()}.*") }
            genre?.takeIf { it.isNotBlank() }?.let { parameter("genre", it.trim()) }
        }.bodyAsText()
        val root = jsonFormat.parseToJsonElement(body).jsonObject
        val books = root["books"]?.jsonArray.orEmpty()
        return books.mapNotNull { element -> (element as? JsonObject)?.toAudiobook() }
    }

    /**
     * A book's chapters, in reading order.
     *
     * The feed answers with the book and its sections in one response, so the
     * cover and the author come with the chapters rather than needing a
     * second request.
     */
    suspend fun chapters(bookId: String): List<AudiobookChapter> {
        val body = client.get(FEED) {
            parameter("format", "json")
            parameter("id", bookId)
            parameter("extended", 1)
        }.bodyAsText()
        val root = jsonFormat.parseToJsonElement(body).jsonObject
        val book = root["books"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val bookTitle = book.string("title").orEmpty()
        val cover = book.coverUrl()
        val sections = book["sections"]?.jsonArray.orEmpty()
        return sections.mapNotNull { element ->
            val section = element as? JsonObject ?: return@mapNotNull null
            val url = section.string("listen_url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AudiobookChapter(
                id = section.string("id").orEmpty(),
                bookId = bookId,
                bookTitle = bookTitle,
                title = section.string("title")?.trim().orEmpty().ifBlank { "Chapter" },
                audioUrl = url,
                seconds = section.string("playtime")?.toLongOrNull(),
                reader = section["readers"]?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.string("display_name"),
                coverUrl = cover,
            )
        }
    }

    private fun JsonObject.toAudiobook(): Audiobook? {
        val id = string("id") ?: return null
        val title = string("title")?.takeIf { it.isNotBlank() } ?: return null
        val author = this["authors"]?.jsonArray
            ?.mapNotNull { element ->
                val person = element as? JsonObject ?: return@mapNotNull null
                val name = listOfNotNull(person.string("first_name"), person.string("last_name"))
                    .joinToString(" ")
                    .trim()
                name.ifBlank { null }
            }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        return Audiobook(
            id = id,
            title = title,
            author = author,
            coverUrl = coverUrl(),
            language = string("language"),
            totalSeconds = string("totaltimesecs")?.toLongOrNull(),
            sectionCount = string("num_sections")?.toIntOrNull() ?: 0,
            genres = this["genres"]?.jsonArray
                ?.mapNotNull { element -> (element as? JsonObject)?.string("name") }
                .orEmpty(),
        )
    }

    /**
     * The recording's own cover.
     *
     * Archive.org holds the audio, so Archive.org holds the picture, and its
     * image service answers for any item id without a second lookup.
     */
    private fun JsonObject.coverUrl(): String? {
        val id = string("id") ?: return null
        val archive = string("url_iarchive")
            ?.substringAfter("archive.org/download/")
            ?.substringBefore("/")
            ?.takeIf { it.isNotBlank() }
            ?: id
        return "https://archive.org/services/img/$archive"
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNullSafe()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun String.escapeRegex(): String =
        Regex.escape(this)
}
