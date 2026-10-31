package com.music.bitchord.data.audiobook

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

/**
 * A book of public-domain recordings, read aloud.
 *
 * Backed by the Internet Archive's LibriVox collection
 * (collection:librivoxaudio). The legacy LibriVox JSON feed only matches
 * exact, full titles and carries no usable cover art, so discovery and
 * covers come from Archive.org: advancedsearch for the catalogue,
 * /metadata/{identifier} for chapters, /services/img/{identifier} for covers.
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
 * A chapter is one original MP3 file on Archive.org, played straight off
 * the archive.
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
    /** Track number from Archive.org metadata; chapters sort by this first. */
    val order: Int = 0,
)

/**
 * The LibriVox collection on Archive.org.
 *
 * Plain JSON over HTTPS, no key, no account, no cookie — readable from a
 * phone with no sign-in.
 */
object LibriVox {
    private val jsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        HttpClient(OkHttp) { expectSuccess = true }
    }

    private const val SEARCH = "https://archive.org/advancedsearch.php"
    private const val METADATA = "https://archive.org/metadata/"

    /**
     * A page of books, sorted by downloads descending. A title search
     * narrows to titles containing the query words; a genre narrows by
     * subject.
     */
    suspend fun books(
        limit: Int = 50,
        offset: Int = 0,
        title: String? = null,
        genre: String? = null,
    ): List<Audiobook> {
        val q = buildString {
            append("collection:librivoxaudio AND mediatype:(audio)")
            title?.takeIf { it.isNotBlank() }?.let { append(" AND title:(${escapeQuery(it)})") }
            genre?.takeIf { it.isNotBlank() }?.let { append(" AND subject:(${escapeQuery(it)})") }
        }
        val page = (offset.coerceAtLeast(0) / limit.coerceAtLeast(1)) + 1
        val body = client.get(SEARCH) {
            parameter("q", q)
            parameter("fl[]", "identifier")
            parameter("fl[]", "title")
            parameter("fl[]", "creator")
            parameter("fl[]", "downloads")
            parameter("fl[]", "language")
            parameter("fl[]", "subject")
            parameter("rows", limit.coerceIn(1, 100).toString())
            parameter("page", page.toString())
            parameter("output", "json")
        }.bodyAsText()
        val root = jsonFormat.parseToJsonElement(body).jsonObject
        val docs = (root["response"] as? JsonObject)?.get("docs") as? JsonArray ?: emptyList()
        return docs.mapNotNull { element -> (element as? JsonObject)?.toAudiobook() }
    }

    /**
     * A book's chapters, in reading order.
     *
     * The metadata response lists every file in the item; original VBR MP3s
     * are the chapters, derivative 64 kbps MP3s and images are skipped.
     */
    suspend fun chapters(bookId: String): List<AudiobookChapter> {
        val body = client.get("$METADATA$bookId").bodyAsText()
        val root = jsonFormat.parseToJsonElement(body).jsonObject
        val metadata = root["metadata"]?.jsonObject ?: return emptyList()
        val bookTitle = metadata.string("title").orEmpty()
        val cover = coverUrlForItem(metadata)
        val files = root["files"]?.jsonArray.orEmpty()
        val chapters = mutableListOf<AudiobookChapter>()
        for (element in files) {
            val file = element as? JsonObject ?: continue
            val name = file.string("name") ?: continue
            if (!name.endsWith(".mp3", ignoreCase = true)) continue
            if (file["original"] != null) continue // derivative, not the chapter
            val format = file.string("format") ?: ""
            if (!format.contains("MP3", ignoreCase = true)) continue
            val order = file.string("track")?.substringBefore("/")?.toIntOrNull() ?: Int.MAX_VALUE
            chapters.add(AudiobookChapter(
                id = name,
                bookId = bookId,
                bookTitle = bookTitle,
                title = file.string("title")?.trim().orEmpty().ifBlank { "Chapter" },
                audioUrl = "https://archive.org/download/${URLEncoder.encode(bookId, "UTF-8")}/${URLEncoder.encode(name, "UTF-8")}",
                seconds = parseSeconds(file.string("length")),
                reader = file.string("artist"),
                coverUrl = cover,
                order = order,
            ))
        }
        return chapters.sortedWith(compareBy({ it.order }, { it.title.lowercase() }))
    }

    private fun JsonObject.toAudiobook(): Audiobook? {
        val id = string("identifier") ?: return null
        val title = string("title")?.takeIf { it.isNotBlank() } ?: return null
        val author = (this["creator"] as? JsonPrimitive)?.content?.toString() ?: ""
        return Audiobook(
            id = id,
            title = title,
            author = author,
            coverUrl = coverUrl(),
            language = string("language"),
            totalSeconds = null,
            sectionCount = 0,
            genres = subjectNames(this["subject"]),
        )
    }

    private fun subjectNames(subject: JsonElement?): List<String> {
        val arr = subject as? JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            when (element) {
                is JsonObject -> element.string("name")
                is JsonPrimitive -> element.content.toString()
                else -> null
            }
        }.filter { it.isNotBlank() }
    }

    /**
     * The recording's own cover. Archive.org holds the audio, so it holds the
     * picture; its image service answers for any item id.
     */
    private fun JsonObject.coverUrl(): String? {
        val id = string("identifier") ?: return null
        return "https://archive.org/services/img/$id"
    }

    private fun coverUrlForItem(metadata: JsonObject): String? {
        val id = metadata.string("identifier") ?: return null
        return "https://archive.org/services/img/$id"
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.toString()

    /** Escapes Archive.org query special characters inside a parenthesised term. */
    private fun escapeQuery(q: String): String =
        q.replace("\\", "\\\\").replace("\"", "\\\"").replace("(", "\\(").replace(")", "\\)")

    private fun parseSeconds(length: String?): Long? {
        if (length.isNullOrBlank()) return null
        return if (length.contains(":")) {
            val parts = length.split(":").map { it.toDoubleOrNull() ?: 0.0 }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toLong()
                2 -> (parts[0] * 60 + parts[1]).toLong()
                else -> parts.last().toLong()
            }
        } else {
            length.toDoubleOrNull()?.toLong()
        }
    }
}
