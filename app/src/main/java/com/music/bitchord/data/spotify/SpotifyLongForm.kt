package com.music.bitchord.data.spotify

/**
 * What Spotify's long-form catalogue is, and this app's answer to it.
 *
 * ## Why it is dropped where it enters
 *
 * Spotify's web player carries podcasts, episodes and audiobooks in the same
 * catalogue as the music: the same shelves, the same chips, the same rows in
 * a playlist that happens to hold a show. None of it is playable here — there
 * is no episode player and no book reader — and every one of those rows is a
 * row that cannot be queued.
 *
 * So the long form is refused at the door rather than deeper in: a chip that
 * opens a shelf of shows is not a chip, a card that opens a show is not a
 * card, and a row in a playlist is only a row when it is a *track*. Filtering
 * it later would leave the shelves and the chips leading somewhere empty.
 *
 * ## What this does not touch
 *
 * The app's own podcast and audiobook features are separate catalogues,
 * reached from their own shelves and served by their own readers. They are
 * not Spotify's, and they stay.
 */
internal object SpotifyLongForm {

    /** Spotify's own uri prefixes for the things this app does not play. */
    private val LONG_FORM_URIS = listOf(
        "spotify:show:",
        "spotify:episode:",
        "spotify:audiobook:",
        "spotify:audiobookchapter:",
    )

    /** The chip ids the web player uses for the long-form shelves. */
    private val LONG_FORM_CHIPS = listOf("podcast", "audiobook", "book")

    /**
     * Whether [uri] names a show, an episode or a book.
     *
     * Read from the uri rather than from a title or a label, because the label
     * is Spotify's own and localised — "Podcasts" in one language, something
     * else in another — while the uri is the same everywhere.
     */
    fun isLongFormUri(uri: String): Boolean =
        LONG_FORM_URIS.any { uri.startsWith(it, ignoreCase = true) }

    /**
     * Whether [uri] names a music track.
     *
     * The test a row in a playlist has to pass to be a row at all.
     */
    fun isMusicTrackUri(uri: String): Boolean =
        uri.startsWith("spotify:track:", ignoreCase = true)

    /**
     * Whether a feed chip opens the long-form catalogue.
     *
     * Spotify's ids are `music-chip`, `podcasts-chip`, `audiobooks-chip`, and
     * a subchip inherits the name — `podcasts-chip-following`. The test is on
     * the name rather than on a list of exact ids, so a chip this build has
     * never seen is still recognised for what it is.
     */
    fun isLongFormChip(id: String): Boolean {
        val name = id.lowercase()
        return LONG_FORM_CHIPS.any { word -> name.contains(word) }
    }
}
