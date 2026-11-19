package com.music.bitchord.playback.cenc

import com.music.bitchord.data.Http
import okhttp3.Request

/**
 * Reads just enough of a stream to know how it is encrypted — see [CencHead].
 *
 * A ranged GET from the start, read until the `moov` is complete. A streamed
 * MP4 keeps it in the first few kilobytes; the ceiling is for one that keeps
 * cover art in it, and for a server that ignores the range.
 */
object CencProbe {

    private const val MAX_BYTES = 1 shl 20
    private const val CHUNK = 16 * 1024

    fun head(url: String, headers: Map<String, String>): CencHead.Result {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=0-${MAX_BYTES - 1}")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) error("HTTP ${response.code} reading the stream's header")
            val source = response.body?.byteStream() ?: error("an empty response reading the stream's header")
            val bytes = ByteArray(MAX_BYTES)
            var filled = 0
            while (filled < MAX_BYTES) {
                val read = source.read(bytes, filled, minOf(CHUNK, MAX_BYTES - filled))
                if (read < 0) break
                filled += read
                val result = CencHead.parse(bytes, filled)
                if (result != CencHead.Result.NeedMore) return result
            }
            return CencHead.parse(bytes, filled)
        }
    }
}
