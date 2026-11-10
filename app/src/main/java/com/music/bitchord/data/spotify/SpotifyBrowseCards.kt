package com.music.bitchord.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The public catalogue's own two lists: the grid of genres and moods, and
 * what is new in it.
 *
 * Both are read through the web player's backend rather than the public API,
 * for the same reason the home feed is: the public catalogue rate limits per
 * client, and the client a harvested token belongs to is shared by everyone
 * who has one. A visitor who has not signed in gets these two lists, and a
 * page with something on it, rather than a page asking for a sign-in it does
 * not need.
 */
internal object SpotifyBrowseCards {

    /**
     * The grid of genres and moods.
     *
     * The answer nests them two deep — a section of cards, each card holding
     * its own representation — and the shape differs slightly between the
     * kinds of card the grid holds, so the title and the picture are looked
     * for in the places each kind puts them.
     */
    fun parse(root: JsonObject): List<SpotifyCard> {
        val sections = root.obj("browseStart")?.obj("sections")?.arr("items").orEmpty()
        val cards = mutableListOf<SpotifyCard>()
        sections.forEach { section ->
            val items = section.jsonObject.obj("sectionItems")?.arr("items").orEmpty()
            items.forEach { item ->
                parseCategory(item)?.let(cards::add)
            }
        }
        return cards
    }

    private fun parseCategory(element: JsonElement): SpotifyCard? {
        val item = element.jsonObject
        val content = item.obj("content") ?: return null
        val uri = item.str("uri") ?: content.str("uri") ?: return null
        if (!uri.startsWith("spotify:page:")) return null
        // A card's own representation sits under the wrapper, one or two
        // levels down depending on which kind of wrapper it is.
        val representation = content.obj("data")?.obj("data")?.obj("cardRepresentation")
            ?: content.obj("data")?.obj("cardRepresentation")
            ?: content.obj("cardRepresentation")
            ?: return null
        val title = representation.str("title.transformedLabel")
            ?: representation.str("title.text")
            ?: return null
        if (title.isBlank()) return null
        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = SpotifyCard.Kind.CATEGORY,
            title = title,
            subtitle = "",
            imageUrl = representation.arr("artwork.sources")?.bestImage(),
        )
    }

    /**
     * What is new.
     *
     * The answer is a page of items, each holding an album under its own
     * wrapper.
     */
    fun parseWhatsNew(root: JsonObject): List<SpotifyCard> {
        val feed = root.obj("whatsNewFeedItems") ?: return emptyList()
        val items = feed.arr("items").orEmpty()
        return items.mapNotNull { item ->
            val data = item.jsonObject.obj("content")?.obj("data") ?: return@mapNotNull null
            val uri = data.str("uri") ?: return@mapNotNull null
            if (!uri.startsWith("spotify:album:")) return@mapNotNull null
            val title = data.str("name") ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SpotifyCard(
                uri = uri,
                id = uri.substringAfterLast(':'),
                kind = SpotifyCard.Kind.ALBUM,
                title = title,
                subtitle = data.artists(),
                imageUrl = data.arr("coverArt.sources")?.bestImage(),
            )
        }
    }

    /** The artists of an album, as one line. */
    private fun JsonObject.artists(): String {
        val names = obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }
        if (names.isNotEmpty()) return names.joinToString(", ")
        return str("firstArtist.items.0.profile.name").orEmpty()
    }

    /** The widest picture offered, by the width the answer states. */
    private fun JsonArray.bestImage(): String? =
        mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }.maxByOrNull { it.second }?.first

    // ── Small JSON helpers ──────────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.arr(path: String): JsonArray? =
        path.split('.').fold(this as JsonElement?) { element, key ->
            element?.jsonObject?.get(key)
        }?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

    private fun JsonObject.str(path: String): String? =
        path.split('.').fold(this as JsonElement?) { element, key ->
            element?.jsonObject?.get(key)
        }?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
}
