package com.music.bitchord.data

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One line of a track's credits: a role ("Written by") and who filled it. */
data class SongCredit(val role: String, val names: List<String>) {
    val text: String get() = names.joinToString(", ")
}

/**
 * A track's credits, as YouTube Music's "View song credits" page lists them.
 *
 * Asked only when the Song info dialog opens, and kept per track for the rest
 * of the session, so opening it again on the same song costs nothing.
 *
 * Ported from BitChord 5d3597f6c. The watch-page fallback is left out: it
 * needs a second, much larger request, and the credits page covers the
 * catalogue tracks that have credits at all.
 */
object SongCreditsRepository {
    private const val CACHE_SIZE = 32
    private val cache = object : LinkedHashMap<String, List<SongCredit>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SongCredit>>?) =
            size > CACHE_SIZE
    }

    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    /** Null when the lookup failed; empty when the track has no credits to show. */
    suspend fun load(song: Song): List<SongCredit>? {
        if (song.localUri != null || !videoIdPattern.matches(song.videoId)) return emptyList()
        synchronized(cache) { cache[song.videoId] }?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                fetchCredits(song.videoId).also { credits ->
                    synchronized(cache) { cache[song.videoId] = credits }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun fetchCredits(videoId: String): List<SongCredit> {
        // The credits page directly: reliable for catalogue tracks.
        var directFailure: Exception? = null
        val direct = try {
            parseCreditsPage(Innertube.browse("MPTC$videoId"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            directFailure = e
            emptyList()
        }
        if (direct.isNotEmpty()) return direct
        // Otherwise the endpoint the watch queue's own menu links to. With
        // nothing there either, a direct request that *failed* makes this a
        // failed lookup, not "no credits": [load] must not cache it as empty.
        val noCredits = { directFailure?.let { throw it } ?: emptyList<SongCredit>() }
        val next = Innertube.next(videoId)
        val endpoint = findCreditsEndpoint(next, videoId) ?: return noCredits()
        val browseId = (endpoint["browseId"] as? JsonPrimitive)?.contentOrNull ?: return noCredits()
        val params = (endpoint["params"] as? JsonPrimitive)?.contentOrNull
        return parseCreditsPage(Innertube.browse(browseId, params))
    }

    /**
     * The credits browse endpoint in a `next` response. Prefers an endpoint
     * whose page type marks it as track credits, then any menu entry whose
     * text says credits.
     */
    internal fun findCreditsEndpoint(root: JsonElement, videoId: String): JsonObject? {
        fun JsonObject.pageType(): String =
            (this["browseEndpointContextSupportedConfigs"] as? JsonObject)
                ?.let { it["browseEndpointContextMusicConfig"] as? JsonObject }
                ?.let { it["pageType"] as? JsonPrimitive }
                ?.contentOrNull.orEmpty()

        fun isCreditsBrowse(browse: JsonObject): Boolean {
            val id = (browse["browseId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return "CREDIT" in browse.pageType() || id.startsWith("MPTC")
        }

        fun JsonObject.browseEndpoint(): JsonObject? =
            (this["navigationEndpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject

        // Only [videoId]'s own row may answer: another queued track's menu
        // links to *its* credits, which would then be cached under this id.
        // With no row for the track, the response outside the queue rows is
        // the only place left that can be about it.
        val ownRows = objectsNamed(root, "playlistPanelVideoRenderer")
            .filter { (it["videoId"] as? JsonPrimitive)?.contentOrNull == videoId }
            .toList()
        val scopes: List<JsonElement> = ownRows.ifEmpty { listOf(root) }
        val skip = if (ownRows.isEmpty()) QUEUE_ROW else null
        for (scope in scopes) {
            val items = objectsNamed(scope, "menuNavigationItemRenderer", skip).toList()
            val endpoint = items.firstNotNullOfOrNull { nav -> nav.browseEndpoint()?.takeIf(::isCreditsBrowse) }
                ?: items.firstNotNullOfOrNull { nav ->
                    nav.browseEndpoint()?.takeIf { nav.runsText().contains("credit", ignoreCase = true) }
                }
            if (endpoint != null) return endpoint
        }
        return scopes.firstNotNullOfOrNull { scope ->
            objectsNamed(scope, "browseEndpoint", skip).firstOrNull(::isCreditsBrowse)
        }
    }

    private const val QUEUE_ROW = "playlistPanelVideoRenderer"

    internal fun parseCreditsPage(root: JsonElement): List<SongCredit> {
        val credits = mutableListOf<SongCredit>()
        // The credits page (MPTC…): one dialog section per role.
        for (section in objectsNamed(root, "dismissableDialogContentSectionRenderer")) {
            val role = section["title"]?.runsText()?.trim().orEmpty()
            val names = splitNames(section["subtitle"]?.runsText().orEmpty())
            if (role.isNotBlank() && names.isNotEmpty()) credits += SongCredit(role, names)
        }
        if (credits.isNotEmpty()) return credits.distinctCredits()
        // List rows: the first flex column is the role, the rest are names.
        for (item in objectsNamed(root, "musicResponsiveListItemRenderer")) {
            val flex = (item["flexColumns"] as? JsonArray).orEmpty()
            val role = flex.getOrNull(0)?.rendererText()?.trim().orEmpty()
            val names = if (flex.size > 1) {
                flex.drop(1).flatMap { splitNames(it.rendererText()) }
            } else {
                splitNames(item["subtitle"]?.runsText().orEmpty())
            }
            if (role.isNotBlank() && names.isNotEmpty()) credits += SongCredit(role, names)
        }
        if (credits.isNotEmpty()) return credits.distinctCredits()
        // Shelf layouts: the shelf's header is the role.
        for (shelf in objectsNamed(root, "musicShelfRenderer") + objectsNamed(root, "musicCarouselShelfRenderer")) {
            val role = (shelf["header"] as? JsonObject)?.runsText()?.trim().orEmpty()
            val names = (shelf["contents"] as? JsonArray).orEmpty()
                .flatMap { splitNames((it as? JsonObject)?.runsText().orEmpty()) }
            if (role.isNotBlank() && names.isNotEmpty()) credits += SongCredit(role, names)
        }
        return credits.distinctCredits()
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun List<SongCredit>.distinctCredits() =
        distinctBy { it.role.lowercase() to it.text.lowercase() }

    private fun JsonElement.rendererText(): String =
        objectsNamed(this, "text").firstNotNullOfOrNull { it.runsText().takeIf(String::isNotBlank) }
            ?: runsText()

    private fun JsonElement.runsText(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> joinToString("") { it.runsText() }
        is JsonObject -> (this["simpleText"] as? JsonPrimitive)?.contentOrNull
            ?: (this["runs"] as? JsonArray)?.joinToString("") { run ->
                ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
            ?: this["text"]?.runsText()
            ?: ""
    }

    private val nameSeparators = Regex("\\r?\\n+|\\s*[•·]\\s*|\\s*,\\s+(?=[\\p{Lu}\"'])")

    private fun splitNames(joined: String): List<String> {
        if (joined.isBlank()) return emptyList()
        return joined.split(nameSeparators)
            .map { it.trim().trimEnd(',', '•', '·') }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(joined.trim()) }
    }

    /** Every object under the key [name], depth first, not descending into keys named [skip]. */
    private fun objectsNamed(root: JsonElement, name: String, skip: String? = null): Sequence<JsonObject> = sequence {
        when (root) {
            is JsonObject -> for ((key, value) in root) {
                if (key == skip) continue
                if (key == name && value is JsonObject) yield(value)
                yieldAll(objectsNamed(value, name, skip))
            }
            is JsonArray -> for (value in root) yieldAll(objectsNamed(value, name, skip))
            else -> Unit
        }
    }
}
