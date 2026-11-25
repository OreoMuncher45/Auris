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
            // list, even when a Suggestions shelf on it was read.
            ?.takeIf { page == 1 || it.playlistRead }
            ?: if (page == 1) break else error("playlist page $page could not be read")
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
