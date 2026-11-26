package com.music.bitchord.data

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.JsonElement

/**
 * Every entry of a playlist, in order, read page by page from [first] on,
 * with [next] fetching the page a continuation token names. See
 * [YtMusicRepository.playlistEntries].
 *
 * A reorder is sent as moves relative to neighbours, so a list that stops
 * short is worse than no list: it would move its last row "to the end" past
 * rows it never saw. So a later page that cannot be read as the playlist's
 * rows — one holding something other than track rows, say — fails the read
 * rather than ending it. Only the first page may be unreadable, and then it
 * is simply not a playlist (an empty list, as before).
 */
internal suspend fun collectPlaylistEntries(
    first: JsonElement,
    maxPages: Int,
    next: suspend (token: String) -> JsonElement,
): List<Song> {
    val out = LinkedHashMap<String, Song>()
    var response = first
    var page = 1
    while (true) {
        val shelf = InnertubeParser.parsePlaylistShelf(response, byEntry = true)
            // A later page whose own rows weren't recognised can't end the
            // list, even when a Suggestions shelf on it was read. A page of
            // nothing but Suggestions is how an owned playlist's chain ends.
            ?.takeIf { page == 1 || it.kind != InnertubeParser.PlaylistShelfPage.Kind.UNREADABLE }
            ?: if (page == 1) break else error("playlist page $page could not be read")
        // A row on any page, the first included, that could not be read: the
        // list would be short by it, and a reorder can't be built on that.
        if (!shelf.complete) error("playlist page $page has a row that could not be read")
        shelf.songs.forEach { song ->
            val setVideoId = song.setVideoId ?: error("playlist entry without an id")
            out.putIfAbsent(setVideoId, song)
        }
        val token = shelf.continuation ?: break
        // A playlist past the cap can't be listed whole, and a partial list
        // can't be reordered safely.
        if (page++ >= maxPages) error("playlist too long to reorder")
        response = next(token)
    }
    return out.values.toList()
}

/**
 * Every track behind a playlist or album, read page by page from [first] on,
 * with [next] fetching the page a continuation token names. See
 * [YtMusicRepository.allSongs].
 *
 * A [whole] read is one a caller acts on as the complete list: "Queue all",
 * the download sync, a check that a track is not already in a playlist. A list
 * that stops short would answer those wrongly (a track it never reached reads
 * as absent), so a page that fails to load, a later page whose rows can't be
 * read, or a list longer than [maxPages] fails the read. A read that is not
 * [whole] (the library's Songs shelf, an artist's top songs) keeps what it
 * collected, as display data.
 *
 * A row that could not be parsed (an unavailable track, with no video id) is
 * left out either way: it names nothing a caller could queue or match.
 */
internal suspend fun collectSongListing(
    first: JsonElement,
    maxPages: Int,
    whole: Boolean,
    next: suspend (token: String) -> JsonElement,
): List<Song> {
    val out = LinkedHashMap<String, Song>()
    var response = first
    var page = 1
    while (true) {
        val (songs, token) = listingPage(response, page, whole)
        songs.forEach { out[it.videoId] = it }
        if (token == null) break
        if (page++ >= maxPages) {
            if (whole) error("list longer than $maxPages pages") else break
        }
        response = if (whole) next(token) else {
            try {
                next(token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                break
            }
        }
    }
    return out.values.toList()
}

/** One page's own tracks and the token for the next, read the way a page on screen reads it. */
private fun listingPage(response: JsonElement, page: Int, whole: Boolean): Pair<List<Song>, String?> {
    // A later page of nothing but track rows: the cheap reading.
    InnertubeParser.parsePlaylistContinuation(response)?.let { return it.songs to it.continuation }
    // A playlist page, scoped to its own shelf so its Suggestions never join.
    val shelf = InnertubeParser.parsePlaylistShelf(response)
        ?: return InnertubeParser.collectSongsDeep(response).distinctBy { it.videoId } to
            InnertubeParser.continuationToken(response)
    // An appended page with an episode among its rows: its own items, the
    // general way, so its next-page token keeps the list going.
    shelf.unreadItems?.let { items ->
        return InnertubeParser.collectSongsDeep(items).distinctBy { it.videoId } to InnertubeParser.continuationToken(items)
    }
    // A later page whose own rows weren't found at all is not the list's end.
    if (whole && page > 1 && shelf.kind == InnertubeParser.PlaylistShelfPage.Kind.UNREADABLE && shelf.complete) {
        error("page $page of the list could not be read")
    }
    return shelf.songs to shelf.continuation
}
