package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song

/**
 * Spotify track id → the song matched for it, for the life of the process.
 *
 * In memory, and deliberately: a match is a *search result*, and a stale one
 * is a wrong song played confidently. A row seen once in a session loads
 * instantly; a fresh process asks again.
 *
 * Each entry remembers the [context] it was matched under (which sources were
 * enabled, and how good a stream the connection allows), and only answers a
 * lookup made under the same one. Switching YouTube off has to stop a
 * remembered YouTube match being handed back as if nothing had changed.
 *
 * Bounded, least recently used out first. Only matches are kept, never a miss:
 * a miss can be a search that failed on a bad connection, and remembering it
 * would make a playlist stay unmatched until the app restarts.
 */
internal class SpotifyMatchCache(private val maxEntries: Int) {

    private class Entry(val context: Any, val song: Song)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    fun get(trackId: String, context: Any): Song? = synchronized(entries) {
        entries[trackId]?.takeIf { it.context == context }?.song
    }

    fun put(trackId: String, context: Any, song: Song) {
        if (trackId.isBlank()) return
        synchronized(entries) { entries[trackId] = Entry(context, song) }
    }

    val size: Int get() = synchronized(entries) { entries.size }
}
