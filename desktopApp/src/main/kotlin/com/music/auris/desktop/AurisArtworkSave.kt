package com.music.auris.desktop

import com.music.bitchord.data.model.Song
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * The cover art, saved as a file.
 *
 * The phone files it in Pictures/Auris through the gallery; a desktop has no gallery to ask,
 * so it goes where a desktop keeps pictures — the XDG pictures directory, or the user's own
 * Pictures folder when there is none.
 */
internal object AurisArtworkSave {

    /** The largest rendition of [thumbnailUrl], or null when there is no art at all. */
    fun fullUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        // YouTube-style size hint: ask for the source rung.
        val sized = Regex("""w\d+-h\d+""").replace(thumbnailUrl, "w1400-h1400")
        if (sized != thumbnailUrl) return sized
        // Tidal resources: step up to the 1280 rung.
        if ("/640x640.jpg" in thumbnailUrl) {
            return thumbnailUrl.replace("/640x640.jpg", "/1280x1280.jpg")
        }
        return thumbnailUrl
    }

    /**
     * Fetches the art for [song] and writes it. Returns the file it wrote.
     *
     * A track with no art is an error rather than a silent nothing — the row is only offered
     * when there is something to fetch.
     */
    suspend fun save(song: Song): Result<Path> = runCatching {
        val url = fullUrl(song.thumbnailUrl) ?: error("This track has no cover art")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            error("The cover art could not be fetched (HTTP ${response.statusCode()})")
        }
        val bytes = response.body()
        if (bytes.isEmpty()) error("The cover art came back empty")
        val extension = extensionFor(response.headers().firstValue("content-type").orElse(""))
        val directory = picturesDirectory().resolve("Auris")
        Files.createDirectories(directory)
        val target = directory.resolve(fileName(song, extension))
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        target
    }

    /** Where a desktop keeps pictures. */
    private fun picturesDirectory(): Path {
        val xdg = System.getenv("XDG_PICTURES_DIR")?.takeIf { it.isNotBlank() }
        if (xdg != null) return Path.of(xdg)
        val home = System.getProperty("user.home") ?: "."
        return Path.of(home, "Pictures")
    }

    /** The name the file is saved under: the track, named the way it is written on its row. */
    private fun fileName(song: Song, extension: String): String {
        val base = listOf(song.artist, song.title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { song.videoId }
        return "$base.$extension"
    }

    /** The extension the bytes' own type asks for. */
    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png", ignoreCase = true) -> "png"
        contentType.contains("webp", ignoreCase = true) -> "webp"
        contentType.contains("gif", ignoreCase = true) -> "gif"
        else -> "jpg"
    }
}
