package com.music.bitchord.data.settings

/**
 * Which catalogue the app looks in.
 *
 * Not which algorithm builds the homepage, the Flow or the radio: those are
 * YouTube Music's, whichever catalogue is chosen here. One algorithm means
 * one answer to "what should play next" — two meant two mixes, and the
 * second one repeated the listener's saved songs whether they had asked for
 * that or not.
 *
 * What this does choose:
 *
 *  - Which catalogue the homepage and the search come from. Spotify's own
 *    home is Spotify's curation; YouTube Music's is its feed.
 *  - Nothing else about the recommendations. What the listener plays on
 *    Spotify is read either way — it is what seeds the mix — so a signed-in
 *    Spotify session is worth having whichever catalogue is chosen.
 *
 * The audio is played from whatever the source ranking picks — YouTube, an
 * addon, a file on the device — whichever catalogue is chosen. A Spotify
 * track is matched to a playable recording the same way an imported
 * playlist's is.
 *
 * More catalogues are expected; nothing outside this enum should assume
 * there are two.
 */
enum class HomeProvider {
    /** YouTube Music, through the app's own innertube session. The default. */
    YOUTUBE,

    /**
     * Spotify: the listener's own playlists, top tracks and pages when they
     * are signed in, and Spotify's public browse lists (featured playlists, new
     * releases, categories) when they are not, so a selection made without a
     * sign-in still shows music.
     */
    SPOTIFY,
    ;

    /** Whether this catalogue can only be read by a signed-in listener. */
    val needsSignIn: Boolean get() = this == SPOTIFY

    companion object {
        /** The catalogue named [name], or [YOUTUBE] when it names nothing known. */
        fun of(name: String?): HomeProvider =
            entries.firstOrNull { it.name == name } ?: YOUTUBE
    }
}
