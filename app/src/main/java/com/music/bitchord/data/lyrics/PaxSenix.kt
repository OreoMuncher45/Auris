package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Word-timed lyrics via [lyrics.paxsenix.org](https://lyrics.paxsenix.org), a
 * public proxy in front of Apple Music's own catalogue and lyrics — a second,
 * independent route to the same Apple TTML [BetterLyrics] carries, useful
 * exactly when that one's host is the one having a bad day.
 *
 * Apple's own search wants a bearer token, which its web player mints from a
 * token embedded in its own JS bundle — there is no key to request, only that
 * bundle to read, so this scrapes it the same way the player itself does at
 * load time, and keeps it until Apple says no.
 */
object PaxSenix {

    private const val PROXY = "https://lyrics.paxsenix.org"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    private const val DURATION_TOLERANCE_SECONDS = 10

    private val tokenMutex = Mutex()
    private val cachedToken = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = (durationMs / 1000).toInt()
        val query = listOfNotNull(title.cleaned(), artist.cleaned().takeIf { it.isNotBlank() })
            .joinToString(" ")
        val results = search(query) ?: return@withContext null
        val best = results
            .filter { track ->
                val trackSeconds = track.durationSeconds
                seconds <= 0 || trackSeconds == null || abs(trackSeconds - seconds) <= DURATION_TOLERANCE_SECONDS
            }
            .maxByOrNull { score(it, title, artist) }
            ?: return@withContext null

        fetchLyrics(best.id)
    }

    private fun score(track: AppleTrack, title: String, artist: String): Double {
        val name = track.attributes.name.trim().lowercase()
        val targetTitle = title.trim().lowercase()
        val artistName = track.attributes.artistName.trim().lowercase()
        val targetArtist = artist.trim().lowercase()
        var score = 0.0
        score += when {
            name == targetTitle -> 80.0
            name.contains(targetTitle) || targetTitle.contains(name) -> 40.0
            else -> 0.0
        }
        if (artistName.contains(targetArtist) || targetArtist.contains(artistName)) score += 40.0
        return score
    }

    private fun String.cleaned(): String = replace(
        Regex(
            """\s*[(\[](official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster\w*|live|version|""" +
                """feat\.?|ft\.?)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        ),
        "",
    ).trim()

    private suspend fun search(query: String): List<AppleTrack>? {
        val token = getToken() ?: return null
        val body = get(
            "$APPLE_SEARCH?term=${java.net.URLEncoder.encode(query, "UTF-8")}&types=songs&limit=10&l=en-US",
            bearer = token,
        ) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<AppleSearchResponse>(body) }.getOrNull()
        return response?.results?.songs?.data
    }

    private fun fetchLyrics(appleId: String): List<LyricLine>? {
        val url = "$PROXY/apple-music/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("id", appleId)
            .build()
        val body = lyricsGet(url.toString()) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<LyricsResponse>(body) }.getOrNull()
            ?: return null

        response.ttmlContent?.takeIf { it.isNotBlank() }?.let { ttml ->
            TtmlLyrics.parse(ttml).takeIf { it.isNotEmpty() }?.let { return it }
        }
        response.elrcMultiPerson?.takeIf { it.isNotBlank() }?.let { elrc ->
            EnhancedLrc.parse(elrc).takeIf { it.isNotEmpty() }?.let { return it }
        }
        response.elrc?.takeIf { it.isNotBlank() }?.let { elrc ->
            EnhancedLrc.parse(elrc).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** [PROXY] itself needs no auth; only the Apple Music catalogue search does. */
    private fun get(url: String, bearer: String? = null): String? = if (bearer == null) {
        lyricsGet(url)
    } else {
        lyricsGetAuthorized(url, bearer)
    }

    private suspend fun getToken(): String? = cachedToken.get() ?: tokenMutex.withLock {
        cachedToken.get() ?: scrapeToken()?.also { cachedToken.set(it) }
    }

    /**
     * Apple's web player carries its own bearer token inside one of its JS
     * bundles rather than minting it per session, so getting one is a matter
     * of reading the same file the player itself loads: the home page names
     * its main script, and the token sits in that script as a complete JWT —
     * three dot-separated segments, not just the leading fragment a looser
     * match would stop at.
     */
    private fun scrapeToken(): String? {
        val home = lyricsGet("https://music.apple.com/us/new") ?: return null
        val scriptPath = INDEX_JS.find(home)?.value ?: return null
        val script = lyricsGet("https://music.apple.com$scriptPath") ?: return null
        return TOKEN.find(script)?.value
    }

    private val INDEX_JS = Regex("""/assets/index~[^"]+\.js""")
    private val TOKEN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    @Serializable
    private data class AppleSearchResponse(val results: Results = Results())

    @Serializable
    private data class Results(val songs: Songs? = null)

    @Serializable
    private data class Songs(val data: List<AppleTrack> = emptyList())

    @Serializable
    private data class AppleTrack(val id: String, val attributes: Attributes) {
        val durationSeconds: Int? get() = attributes.durationInMillis?.let { (it / 1000).toInt() }
    }

    @Serializable
    private data class Attributes(
        val name: String,
        val artistName: String,
        @SerialName("durationInMillis") val durationInMillis: Long? = null,
    )

    @Serializable
    private data class LyricsResponse(
        val ttmlContent: String? = null,
        val elrc: String? = null,
        val elrcMultiPerson: String? = null,
    )
}
