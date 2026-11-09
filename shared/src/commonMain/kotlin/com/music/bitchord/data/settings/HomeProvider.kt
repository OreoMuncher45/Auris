package com.music.bitchord.data.settings

/**
 * Where the app's *algorithmic* content comes from: the homepage, Flow, and
 * Autoplay.
 *
 * Not where the audio comes from, and not what the library shows. Those are
 * three different questions and it matters that they are answered separately:
 *
 *  - A home row is a *recommendation*, which one service or the other is better
 *    at. That is what this switches.
 *  - The audio for a row is played from whatever the source ranking picks —
 *    YouTube, an addon, a file on the device — exactly as it is today. A
 *    Spotify-sourced row still plays through the same path a YouTube-sourced
 *    one does, and its tracks are matched to it the same way an imported
 *    playlist's are.
 *  - The Library tab keeps showing the listener's own YouTube and device
 *    content whichever provider is selected, because that is their library
 *    rather than a feed.
 *
 * The switch is deliberately a whole-app one rather than per-screen: Flow
 * carries learning state (skips, bans, an hourly snapshot) and Autoplay
 * carries its own, and both are trained against whichever provider built them.
 * Mixing the two in one session would train each on the other's evidence, which
 * is why changing this restarts the app rather than quietly swapping a feed.
 *
 * More providers are expected; nothing outside this enum should assume there
 * are two.
 */
enum class HomeProvider {
    /** YouTube Music, through the app's own innertube session. The default. */
    YOUTUBE,

    /**
     * Spotify: the listener's own playlists, top tracks and history when they
     * are signed in, and Spotify's public browse lists (featured playlists, new
     * releases, categories) when they are not, so a selection made without a
     * sign-in still shows music.
     */
    SPOTIFY,
    ;

    /** Whether this provider can only be read by a signed-in listener. */
    val needsSignIn: Boolean get() = this == SPOTIFY

    companion object {
        /** The provider named [name], or [YOUTUBE] when it names nothing known. */
        fun of(name: String?): HomeProvider =
            entries.firstOrNull { it.name == name } ?: YOUTUBE
    }
}
