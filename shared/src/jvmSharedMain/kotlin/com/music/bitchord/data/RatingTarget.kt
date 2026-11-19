package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.songOrNull
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_ROW_PREFIX
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The id a rating is written against.
 *
 * A rating is YouTube Music's: the heart writes to Liked Music, and that API
 * takes a YouTube video id. A row that came out of Spotify's catalogue has no
 * YouTube id behind it — it carries a synthetic one (`spotify:<id>`) that says
 * which row it is and nothing else — and writing that to the rating API asks
 * YouTube about a video that does not exist. The request is refused and the
 * heart silently does nothing, which is exactly what a Spotify row used to
 * do: tap the heart on a track found in the Spotify catalogue, or found by
 * searching Spotify, and nothing was liked anywhere.
 *
 * So a Spotify or source-backed row is resolved to the YouTube recording
 * behind it before it is rated. Two ways to get there, in this order:
 *
 *  - **What playback already worked out.** A Spotify row played from YouTube
 *    is matched to a video id on the way in — that is how the row is played at
 *    all — and the answer is kept here, so the heart costs nothing.
 *  - **Asking YouTube for the recording by name.** The same question the
 *    player asks when it plays such a row, asked again on the one tap that
 *    needs it.
 *
 * The mapping is kept for the session and published, so the heart on a
 * Spotify row shows the rating of the recording it points at rather than
 * nothing at all.
 */
object RatingTarget {

    /**
     * Whether [videoId] is a catalogue row rather than a YouTube Music video id.
     *
     * The three Spotify prefixes are the three ways a Spotify row reaches the
     * app: its own catalogue entry, a match still being looked for, and a
     * match that was looked for and found to be missing. A source-backed row
     * carries its own catalogue's id for the same reason and needs the same
     * YouTube recording found behind it before YouTube Music can be asked to
     * rate it or start a radio from it.
     */
    fun isRowId(videoId: String): Boolean =
        videoId.startsWith(SPOTIFY_ROW_PREFIX) ||
            videoId.startsWith(SPOTIFY_PENDING_PREFIX) ||
            videoId.startsWith(SPOTIFY_MISSING_PREFIX) ||
            SourceTrackKeys.parse(videoId) != null

    /** The Spotify track id behind a row id, when it is one. */
    fun spotifyIdOf(videoId: String): String? = when {
        videoId.startsWith(SPOTIFY_ROW_PREFIX) -> videoId.removePrefix(SPOTIFY_ROW_PREFIX)
        videoId.startsWith(SPOTIFY_PENDING_PREFIX) -> videoId.removePrefix(SPOTIFY_PENDING_PREFIX)
        videoId.startsWith(SPOTIFY_MISSING_PREFIX) -> videoId.removePrefix(SPOTIFY_MISSING_PREFIX)
        else -> null
    }

    /** The rows that have been resolved, and the video ids they resolved to. */
    private val _resolved = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The rows that have been resolved, and the video ids they resolved to. */
    val resolved: StateFlow<Map<String, String>> = _resolved.asStateFlow()

    /**
     * Remembers what a row was resolved to.
     *
     * A row id that has already been resolved to a different video id is not
     * overwritten: the first answer is the one the row was played on, and a
     * later search that picks a different recording is a second answer to a
     * question that has already been answered.
     */
    fun remember(rowId: String, videoId: String) {
        if (rowId == videoId || videoId.isBlank()) return
        _resolved.update { current ->
            if (current.containsKey(rowId)) current else current + (rowId to videoId)
        }
    }

    /** The video id a row was resolved to, when it is known. */
    fun videoIdFor(rowId: String): String? =
        if (isRowId(rowId)) _resolved.value[rowId] else null

    /**
     * The YouTube Music video id to rate [song] under.
     *
     * A YouTube row rates itself. A Spotify row is resolved: from what is
     * already known, or by asking YouTube for the recording by name and
     * keeping the answer if the matcher agrees it is the same recording.
     *
     * Null when there is no YouTube recording behind the row — the caller has
     * nothing to write, and saying so is better than writing an id that means
     * nothing.
     */
    suspend fun resolve(song: Song): String? = resolve(song) { query ->
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
    }

    /** [resolve], with the catalogue search supplied so the match can be tested without a network. */
    internal suspend fun resolve(
        song: Song,
        search: suspend (String) -> List<SearchResult>,
    ): String? {
        if (!isRowId(song.videoId)) return song.videoId
        _resolved.value[song.videoId]?.let { return it }

        val target = TrackMatcher.targetOf(song)
        if (target.title.isBlank()) return null
        val queries = TrackMatcher.queries(target)
        for (query in queries) {
            val rows = search(query)
            val songs = rows.mapNotNull { row -> row.songOrNull() }
            // The matcher's answer, and then — only if it has none — a row by
            // the same artist. The same pair the player uses when it plays a
            // Spotify row, so the recording the heart likes is the recording
            // that was playing.
            val best = TrackMatcher.bestOrSameArtist(songs, target)
            if (best != null) {
                remember(song.videoId, best.videoId)
                // Another lookup may have answered first while this one was in
                // flight. The remembered recording is the one every surface
                // must use, so return that rather than this later answer.
                return videoIdFor(song.videoId) ?: best.videoId
            }
        }
        return null
    }
}
