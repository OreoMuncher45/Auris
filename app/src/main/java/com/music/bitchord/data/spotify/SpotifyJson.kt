package com.music.bitchord.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading Spotify's answers without being upset by their shape.
 *
 * Every answer here is somebody else's document, and it changes when they
 * ship a build: a field moves, a list becomes an object, a number becomes a
 * string. The accessors this replaces threw on anything they did not expect,
 * so one moved field emptied a whole page — the read failed, the page said
 * "nothing to show", and the reason was a field name nobody would ever see.
 *
 * These read what is there and skip what is not. A card in a shape this
 * version does not know is one missing card; a feed with one missing card is
 * still a feed.
 */
internal fun JsonElement.objOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.arrOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

/** The text of a primitive, or null when this is not one. */
internal fun JsonElement.textOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

/** The number of a primitive, or null when this is not one. */
internal fun JsonElement.numberOrNull(): Int? = runCatching { jsonPrimitive.intOrNull }.getOrNull()

/** The object at [path], or null. */
internal fun JsonObject.obj(path: String): JsonObject? = walk(path)?.objOrNull()

/** The array at [path], or null. */
internal fun JsonObject.arr(path: String): JsonArray? = walk(path)?.arrOrNull()

/**
 * The text at a dotted [path], or null.
 *
 * The path may cross an array by its index: `firstArtist.items.0.profile.name`
 * is how an answer spells "the first artist's name" when it spells it that
 * way at all. Reading that path through an object-only walk used to throw,
 * because an array is not an object — so the fallback that exists for the
 * artists of a track could itself empty the page.
 */
internal fun JsonObject.str(path: String): String? = walk(path)?.textOrNull()

/** The number at [path], or null. */
internal fun JsonObject.int(path: String): Int? = walk(path)?.numberOrNull()

/**
 * The widest picture a list of sources offers, by the width it states.
 *
 * Spotify lists an image's sizes in no fixed order — a 64px thumbnail can
 * come last — so the list is read by width rather than by position.
 */
internal fun JsonArray.bestSourceUrl(): String? =
    mapNotNull { source ->
        val source = source.objOrNull() ?: return@mapNotNull null
        val url = source.str("url") ?: return@mapNotNull null
        url to (source.int("width") ?: 0)
    }.pickedRendition()

/**
 * The rendition to download: the smallest at least [minWidth] wide, or the
 * largest when every one is smaller.
 *
 * Spotify's sources run 64 / 300 / 640 and sometimes larger. Picking the widest
 * downloaded a 1280px cover for a 150dp card and a 44dp row — bytes and decode
 * spent on pixels the screen throws away. 640 is the size Spotify's own player
 * draws a sleeve at, so nothing in this app is drawn larger than that; the
 * smallest rendition at or above it is the one worth fetching.
 */
internal fun List<Pair<String, Int>>.pickedRendition(minWidth: Int = 640): String? {
    val atLeast = filter { it.second >= minWidth }.minByOrNull { it.second }
    return (atLeast ?: maxByOrNull { it.second })?.first
}

/**
 * The element at a dotted [path], or null.
 *
 * A path is a list of keys, each of which is a name in an object or an index
 * in an array. A path that runs out of road — a key that is not there, an
 * index past the end, an object where an array was expected — yields null
 * rather than an exception.
 */
private fun JsonObject.walk(path: String): JsonElement? =
    path.split('.').fold(this as JsonElement?) { element, key ->
        when (element) {
            null -> null
            is JsonObject -> element[key]
            is JsonArray -> key.toIntOrNull()?.let { index -> element.getOrNull(index) }
            else -> null
        }
    }
