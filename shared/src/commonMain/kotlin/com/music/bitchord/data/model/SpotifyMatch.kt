package com.music.bitchord.data.model

/**
 * A Spotify playlist page lists Spotify's tracks first and finds each one's
 * YouTube Music counterpart afterwards. Until then a row carries this prefix
 * on its video id; one with no counterpart carries [SPOTIFY_MISSING_PREFIX].
 * Neither can be played, and the rows say so rather than failing on tap.
 */
const val SPOTIFY_PENDING_PREFIX = "sp:"
const val SPOTIFY_MISSING_PREFIX = "sp-miss:"

/**
 * The prefix on a row that is Spotify's own catalogue entry.
 *
 * `spotify:<id>` says "this is a Spotify track, and here is its id". The
 * player resolves it by asking the enabled sources for the recording by name,
 * so such a row is playable without having been matched to anything else
 * first.
 */
const val SPOTIFY_ROW_PREFIX = "spotify:"

/** Still looking for the YouTube Music version of this Spotify track. */
val Song.isMatchPending: Boolean get() = videoId.startsWith(SPOTIFY_PENDING_PREFIX)

/** Looked, and there is no YouTube Music version to play. */
val Song.isMatchMissing: Boolean get() = videoId.startsWith(SPOTIFY_MISSING_PREFIX)

val Song.isUnresolvedSpotify: Boolean get() = isMatchPending || isMatchMissing

/**
 * A Spotify row as a queue entry, whether or not it has been matched yet.
 *
 * ## Why an unmatched row can still be queued
 *
 * The matcher asks YouTube Music and every enabled source whether it holds
 * this recording, and it does that for the whole page — which on a slow
 * connection is a minute of work for a hundred rows, and a Play pressed in
 * the middle of it used to wait for all of it. Thirty seconds of nothing
 * between pressing Play and hearing the song.
 *
 * But the matcher is a *preference*, not a requirement. A row that carries
 * its Spotify id says which recording it is, and the player can ask the
 * sources for that recording by name when the row is reached — the same path
 * a Spotify search result has always taken. So an unmatched row is queued as
 * `spotify:<id>` and resolved when it plays, rather than the whole page being
 * held hostage to a lookup done before anybody asked for a sound.
 *
 * The rows that *have* been matched keep their own id: a match is a better
 * answer than a name, and it is already in hand.
 */
fun Song.asSpotifyQueueRow(): Song = when {
    isMatchPending -> copy(videoId = "$SPOTIFY_ROW_PREFIX${videoId.removePrefix(SPOTIFY_PENDING_PREFIX)}")
    isMatchMissing -> copy(videoId = "$SPOTIFY_ROW_PREFIX${videoId.removePrefix(SPOTIFY_MISSING_PREFIX)}")
    else -> this
}

/** The same, for a page's worth of rows. */
fun List<Song>.asSpotifyQueueRows(): List<Song> = map { it.asSpotifyQueueRow() }
