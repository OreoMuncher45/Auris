package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.sources.module.ModuleStreamSegment
import com.music.bitchord.data.sources.module.ModuleStreamTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One extension's answer to "play this track", as this host reads it.
 *
 * The answer is JSON written by somebody else's JavaScript, and it has three
 * shapes that matter:
 *
 * ```json
 * {"streamUrl":"https://…","headers":{…},"track":{…}}
 * {"streamUrl":null,"segments":[{"url":"https://…"},…],"track":{…}}
 * {"streamUrl":null,"error":"…","track":{…}}
 * ```
 *
 * ## Why this is not read with org.json
 *
 * `JSONObject.optString(name, "")` does not treat a JSON null as absent. It
 * carries a JSON null as [org.json.JSONObject.NULL], whose `toString()` is the
 * four letters `null` — so the field comes back as the *string* "null", which
 * is neither blank nor absent.
 *
 * On a stream answer that was the difference between a track playing and a
 * track being skipped. Tidal answers a segmented start with `streamUrl: null`
 * and the segments beside it; a reader that took "null" for a URL stopped the
 * quality ladder on an answer with no URL, handed the player a URL that
 * org.json had invented, and left the segments unread in the same object. The
 * player then reported "malformed URL" for a track whose Atmos mix had been
 * in hand the whole time.
 *
 * Reading the answer with kotlinx.serialization instead makes a JSON null a
 * null — which is what it is — and leaves nothing to be careful about later.
 * Every field here is read the same way: absent, null, blank, or the word
 * "null" all mean the same thing, and they mean *nothing was said*.
 */
internal object SflxAnswer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** The URL the extension resolved, or null when it resolved none. */
    fun streamUrl(raw: String): String? = text(field(raw, "streamUrl"))

    /** The reason the extension gave, or null when it gave none. */
    fun error(raw: String): String? = text(field(raw, "error"))

    /**
     * Whether the answer carries something to play.
     *
     * A URL, or the parts of one. Tidal, Amazon and Apple Music answer with
     * `streamUrl: null` and a list of segments, and the segments are the
     * answer: the host assembles them into one file (see [SflxSegments]) and
     * hands the player that.
     */
    fun carriesStream(raw: String): Boolean = streamUrl(raw) != null || segments(raw).isNotEmpty()

    /** The headers the extension built for the audio request. */
    fun headers(raw: String): Map<String, String> = headerMap(field(raw, "headers"))

    /** The parts of a segmented start, in the order the extension gave them. */
    fun segments(raw: String): List<ModuleStreamSegment> {
        val array = field(raw, "segments") as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val part = entry as? JsonObject ?: return@mapNotNull null
            val url = text(part["url"]) ?: return@mapNotNull null
            ModuleStreamSegment(url = url, headers = headerMap(part["headers"]))
        }
    }

    /** What the extension said about the audio it resolved. */
    fun track(raw: String, trackId: String): ModuleStreamTrack? {
        val meta = field(raw, "track") as? JsonObject ?: return null
        return ModuleStreamTrack(
            id = trackId,
            audioQuality = text(meta["audioQuality"]).orEmpty(),
            mimeType = text(meta["mimeType"]),
            bitDepth = (meta["bitDepth"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            sampleRate = (meta["sampleRate"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
            bitrate = (meta["bitrate"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            audioModes = (meta["audioModes"] as? JsonArray)
                ?.mapNotNull { text(it) }
                ?.takeIf { it.isNotEmpty() },
        )
    }

    // ── Reading ──────────────────────────────────────────────────────────

    private fun field(raw: String, name: String): JsonElement? =
        root(raw)?.get(name)

    private fun root(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    /**
     * A JSON value as text, or null when it says nothing.
     *
     * Absent, null, blank, and the word "null" are the same answer: nothing
     * was said. An extension that has no URL writes no URL; it does not write
     * the word "null", and this host must not read one as if it had.
     */
    private fun text(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        else -> null
    }

    /**
     * A JSON object of header names to values.
     *
     * Passed through as written rather than filtered against a list this host
     * would have to keep current: these are the headers the extension was
     * going to send with the audio request, and a part's URL is frequently
     * bound to them. Blank names and values are dropped because OkHttp
     * refuses them, and refusing the whole request over one empty header
     * would lose the track.
     */
    private fun headerMap(value: JsonElement?): Map<String, String> {
        val object_ = value as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        object_.forEach { (key, entry) ->
            val text = text(entry) ?: return@forEach
            if (key.isNotBlank()) result[key] = text
        }
        return result
    }
}
