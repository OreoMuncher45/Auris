package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/**
 * One lazy-list key per item, built from what the item is rather than where it
 * sits, and unique even when the feed repeats itself.
 *
 * A key is what lets a lazy list keep an item's state, and its slot, when the
 * list around it changes — without one, the index is the key, and a shelf
 * that gains a card at the front re-composes every card after it as if each
 * were new. But the feeds these lists draw do send the same track or album
 * twice in one row, and a key given twice is a crash, so every item is keyed by
 * its base and which copy of it this is.
 *
 * The pair is written length-first (`<length>:<base>#<n>`, `n` from 0), so it
 * reads back one way only: a plain `#n` suffix let a base that already ended
 * in `#1` collide with the second copy of another, as `Top`, `Top`, `Top#1`.
 */
fun <T> uniqueKeys(items: List<T>, base: (T) -> String): List<String> {
    val seen = HashMap<String, Int>(items.size)
    return items.map { item ->
        val key = base(item)
        val count = seen.getOrDefault(key, 0)
        seen[key] = count + 1
        "${key.length}:$key#$count"
    }
}

/** [uniqueKeys] for a row of cards, remembered for as long as the row is the same list. */
@Composable
fun rememberShelfKeys(items: List<ShelfItem>): List<String> =
    remember(items) { uniqueKeys(items, ShelfItem::lazyKey) }

/** [uniqueKeys] for a list of tracks, remembered for as long as it is the same list. */
@Composable
fun rememberSongKeys(items: List<Song>): List<String> =
    remember(items) { uniqueKeys(items) { it.videoId } }

/** What a card is, for a lazy key: the page it opens, else the track it plays. */
fun ShelfItem.lazyKey(): String = browseId ?: videoId ?: title

/**
 * The [contentType]s the shared lists hand their items, so a lazy list only
 * reuses a slot for something drawn the same way — a song row's composition is
 * never recycled into a header or a shelf.
 */
object LazyContent {
    const val HEADER = "header"
    const val MESSAGE = "message"
    const val SONG_ROW = "song-row"
    const val BROWSE_ROW = "browse-row"
    const val CARD = "card"
    const val LEADING_CARD = "leading-card"
    const val HERO_CARD = "hero-card"
    const val TRACK_COLUMN = "track-column"
    const val SHELF = "shelf"
    const val HERO_SHELF = "hero-shelf"
    const val RECENT_SHELF = "recent-shelf"
    const val SKELETON = "skeleton"
}
