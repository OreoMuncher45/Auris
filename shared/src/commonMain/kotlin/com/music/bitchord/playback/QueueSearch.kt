package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * The queue's own search: what a typed name matches, and how the answers from
 * the catalogue and from the device are put together.
 *
 * ## Why this is here and not in the app
 *
 * The queue search answers two questions at once — "what does the catalogue
 * have" and "what do I already have" — and the two answers have to be put
 * together the same way every time. That is arithmetic, not platform work, so
 * it belongs where it can be tested: the phone's downloads and the phone's
 * catalogue are read by the app, and this file decides what those two lists
 * mean together.
 *
 * ## Why the device's own songs come first
 *
 * A song the listener has downloaded is a song they can hear without a
 * network, and it is a song they asked for by name because they have it. So
 * the local copy is the answer, and the catalogue's copy of the same
 * recording is not shown beside it — two rows for one song, one of which
 * cannot be played on the train, is worse than one row that can.
 */
object QueueSearch {

    /**
     * How many rows the search shows.
     *
     * A queue is a short list of things to hear next, not a catalogue: past
     * this many hits the list is a scroll, and a scroll in a search is a
     * search that has stopped being about the song.
     */
    const val MAX_RESULTS = 50

    /**
     * Whether [song] is what [query] is asking for.
     *
     * Title, artist and album, all of them case- and accent-insensitive
     * enough for a name typed on a phone keyboard. An empty query matches
     * nothing: the field's own hint is not a search, and a list of everything
     * would be the catalogue rather than an answer.
     */
    fun matches(song: Song, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return false
        return song.title.lowercase().contains(needle) ||
            song.artist.lowercase().contains(needle) ||
            (song.albumName ?: "").lowercase().contains(needle)
    }

    /**
     * The songs already on the device that [query] is asking for.
     *
     * The device's own list is searched here rather than in the app, so the
     * rule for what a name matches is one rule and not two.
     */
    fun localMatches(songs: List<Song>, query: String): List<Song> =
        songs.filter { matches(it, query) }.take(MAX_RESULTS)

    /**
     * The catalogue's songs that [query] is asking for.
     *
     * The catalogue is not filtered by title here — it has already searched,
     * and it answers with what it thinks the name means, which is often a
     * better answer than a substring test would give. It is only trimmed.
     */
    fun onlineMatches(songs: List<Song>): List<Song> = songs.take(MAX_RESULTS)

    /**
     * The device's answers and the catalogue's, in one list.
     *
     * Local first, and each recording once: the copy that is already on the
     * device is the one that plays without a network, and the catalogue's
     * copy of the same recording would only be a second row for a song the
     * listener has already got.
     */
    fun merge(local: List<Song>, online: List<Song>): List<Song> {
        val seen = mutableSetOf<String>()
        return (local + online)
            .filter { seen.add(it.videoId) }
            .take(MAX_RESULTS)
    }
}
