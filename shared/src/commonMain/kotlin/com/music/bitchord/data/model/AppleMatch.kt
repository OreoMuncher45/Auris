package com.music.bitchord.data.model

/**
 * The prefix every Apple Music row's id carries.
 *
 * An Apple row is catalogue metadata, not a playable id: Apple's audio is DRM
 * and is never fetched. The prefix is what tells the rest of the app that — the
 * source matcher resolves the row to a playable recording by name, the heart
 * rates the recording behind it, AutoPlay seeds a station from it, and the
 * share menu sends Apple's own link. Without it a row would be handed to
 * YouTube as though `apple:song:123` were a video id, and nothing would work.
 *
 * The same arrangement [SPOTIFY_ROW_PREFIX] makes for Spotify's rows; the two
 * are kept apart so a row's provenance travels with its id.
 */
const val APPLE_ROW_PREFIX = "apple:"

/** An Apple catalogue song. */
const val APPLE_SONG_PREFIX = "apple:song:"

/** An Apple catalogue album. */
const val APPLE_ALBUM_PREFIX = "apple:album:"

/** An Apple catalogue artist. */
const val APPLE_ARTIST_PREFIX = "apple:artist:"

/** An Apple catalogue playlist. */
const val APPLE_PLAYLIST_PREFIX = "apple:playlist:"

/** The listener's own Apple library — a playlist or an album they saved. */
const val APPLE_LIBRARY_PREFIX = "apple:lib:"
