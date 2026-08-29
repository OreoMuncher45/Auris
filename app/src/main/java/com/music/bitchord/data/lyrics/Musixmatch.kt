package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Line-synced lyrics from Musixmatch's own web client API.
 *
 * There is no public key for this: the web player signs every request with an
 * HMAC over the URL and the day's date, using a secret that has been baked
 * into that same web player's JavaScript — and, by extension, into every
 * independent Musixmatch client that has reimplemented the scheme from
 * reading it, which is where this one comes from too. A session token from
 * `token.get` rides alongside it and is cached until the service itself
 * rejects it.
 */
object Musixmatch {

    private const val BASE = "https://apic.musixmatch.com/ws/1.1"

    // The signing secret Musixmatch's web client bakes into its own bundle —
    // see the file note above for where this comes from.
    private const val SIGNING_SECRET = "RJDefUswhwjkZDeM"

    private val tokenMutex = Mutex()
    private val cachedToken = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = (durationMs / 1000).toInt()
        val track = bestTrack(title, artist, seconds) ?: return@withContext null
        val subtitle = if (track.hasSubtitles == 1) fetchSubtitle(track.trackId) else null
        val lrc = subtitle?.let(::subtitleToLrc)?.takeIf { it.isNotBlank() } ?: return@withContext null
        LrcLib.parseLrc(lrc).takeIf { it.isNotEmpty() }
    }

    private suspend fun bestTrack(title: String, artist: String, seconds: Int): Track? {
        val tracks = searchTrack(title, artist) ?: return null
        return tracks.maxByOrNull { score(it, title, artist, seconds) }
    }

    private fun score(track: Track, title: String, artist: String, seconds: Int): Double {
        var score = 0.0
        val name = track.trackName.trim().lowercase(Locale.ROOT)
        val targetTitle = title.trim().lowercase(Locale.ROOT)
        score += when {
            name == targetTitle -> 80.0
            name.contains(targetTitle) || targetTitle.contains(name) -> 40.0
            else -> 0.0
        }
        if (track.artistName.trim().lowercase(Locale.ROOT).contains(artist.trim().lowercase(Locale.ROOT))) {
            score += 40.0
        }
        track.trackLength?.let { length ->
            val diff = abs(length - seconds)
            score += when {
                diff <= 2 -> 30.0
                diff <= 5 -> 15.0
                diff <= 10 -> 5.0
                else -> -20.0
            }
        }
        return score
    }

    private suspend fun searchTrack(title: String, artist: String): List<Track>? {
        val response = signedGet { token ->
            "$BASE/track.search".toHttpUrl().newBuilder()
                .addQueryParameter("app_id", "web-desktop-app-v1.0")
                .addQueryParameter("q_track", title)
                .addQueryParameter("q_artist", artist)
                .addQueryParameter("f_has_lyrics", "1")
                .addQueryParameter("s_track_rating", "desc")
                .addQueryParameter("quorum_factor", "1")
                .addQueryParameter("page_size", "10")
                .addQueryParameter("page", "1")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        val body = runCatching {
            lyricsJson.decodeFromString<Envelope<TrackSearchBody>>(response)
        }.getOrNull() ?: return null
        return body.message.body?.trackList?.map { it.track }
    }

    private suspend fun fetchSubtitle(trackId: Long): String? {
        val response = signedGet { token ->
            "$BASE/track.subtitle.get".toHttpUrl().newBuilder()
                .addQueryParameter("app_id", "web-desktop-app-v1.0")
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("subtitle_format", "mxm")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        return runCatching {
            lyricsJson.decodeFromString<Envelope<SubtitleBody>>(response)
        }.getOrNull()?.message?.body?.subtitle?.subtitleBody
    }

    /** Musixmatch's `mxm` subtitle JSON — a list of `{text, time:{total}}` — turned into LRC. */
    private fun subtitleToLrc(subtitleBody: String): String {
        val lines = runCatching { lyricsJson.decodeFromString<List<SubtitleLine>>(subtitleBody) }
            .getOrNull() ?: return ""
        return buildString {
            for (line in lines) {
                if (line.text.isBlank()) continue
                val totalMs = (line.time.total * 1000).toLong()
                val minutes = totalMs / 1000 / 60
                val seconds = (totalMs / 1000) % 60
                val millis = totalMs % 1000
                appendLine(
                    "[" + "%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis) + "]" + line.text,
                )
            }
        }.trim()
    }

    /** Signs and issues [buildUrl]; on an auth failure, drops the token and retries once. */
    private suspend fun signedGet(buildUrl: (token: String) -> okhttp3.HttpUrl): String? {
        val token = getToken() ?: return null
        val first = lyricsGet(sign(buildUrl(token).toString()))
        if (first != null && !looksUnauthorized(first)) return first

        cachedToken.set(null)
        val fresh = getToken() ?: return null
        return lyricsGet(sign(buildUrl(fresh).toString()))
    }

    /** Musixmatch answers an expired token with HTTP 200 and a header status code, not a 401. */
    private fun looksUnauthorized(body: String): Boolean =
        runCatching { lyricsJson.decodeFromString<Envelope<kotlinx.serialization.json.JsonElement>>(body) }
            .getOrNull()?.message?.header?.statusCode?.let { it == 401 || it == 402 } ?: false

    /**
     * A short critical section around one network call — cheap insurance
     * against every source in the race minting its own token the first time
     * this object is touched.
     */
    private suspend fun getToken(): String? = cachedToken.get() ?: tokenMutex.withLock {
        cachedToken.get() ?: fetchToken()?.also { cachedToken.set(it) }
    }

    private fun fetchToken(): String? {
        val url = "$BASE/token.get".toHttpUrl().newBuilder()
            .addQueryParameter("app_id", "web-desktop-app-v1.0")
            .build()
        val body = lyricsGet(sign(url.toString())) ?: return null
        return runCatching {
            lyricsJson.decodeFromString<Envelope<TokenBody>>(body)
        }.getOrNull()?.message?.body?.userToken
    }

    /** Musixmatch's web client signs `<url><UTC yyyyMMdd>` with HMAC-SHA256, base64-encoded. */
    private fun sign(url: String): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGNING_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal("$url$date".toByteArray(Charsets.UTF_8))
        val signature = Base64.getEncoder().encodeToString(raw)
        return "$url&signature=${java.net.URLEncoder.encode(signature, "UTF-8")}&signature_protocol=sha256"
    }

    @Serializable
    private data class Envelope<T>(val message: Message<T>)

    @Serializable
    private data class Message<T>(val header: Header, val body: T? = null)

    @Serializable
    private data class Header(@SerialName("status_code") val statusCode: Int = 0)

    @Serializable
    private data class TokenBody(@SerialName("user_token") val userToken: String)

    @Serializable
    private data class TrackSearchBody(@SerialName("track_list") val trackList: List<TrackWrapper> = emptyList())

    @Serializable
    private data class TrackWrapper(val track: Track)

    @Serializable
    private data class Track(
        @SerialName("track_id") val trackId: Long,
        @SerialName("track_name") val trackName: String,
        @SerialName("artist_name") val artistName: String = "",
        @SerialName("track_length") val trackLength: Int? = null,
        @SerialName("has_subtitles") val hasSubtitles: Int = 0,
    )

    @Serializable
    private data class SubtitleBody(val subtitle: Subtitle? = null)

    @Serializable
    private data class Subtitle(@SerialName("subtitle_body") val subtitleBody: String)

    @Serializable
    private data class SubtitleLine(val text: String, val time: SubtitleTime)

    @Serializable
    private data class SubtitleTime(val total: Double)
}
