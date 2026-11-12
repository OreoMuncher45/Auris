package com.music.bitchord.data.lyrics

/**
 * The databases [LyricsRepository] can ask, in the order it asks them.
 *
 * Exposed in Settings because the trade-offs are real and personal: one of
 * these is geoblocked in some countries, another runs on volunteer mirrors
 * that come and go, and all of them are third-party services being reached on
 * the user's connection. Anyone who would rather not talk to a given one
 * should be able to say so.
 */
enum class LyricsSource(
    private val baseLabel: String,
    private val baseDetail: String,
    /** Whether it can return per-word timings, or only whole lines. */
    val wordSynced: Boolean,
    /**
     * Kept in code but off the list: not shown in Settings or the provider
     * picker, and never asked — even by someone whose saved choices still name
     * it. For a host that has stopped answering, where deleting the provider
     * outright would only mean writing it again if it comes back.
     */
    val hidden: Boolean = false,
) {
    // Declaration order is the default priority — [AppSettings.lyricsSourceOrder]
    // and [AppSettings.lyricsSources] both fall back to [LyricsSource.entries]
    // verbatim, so this list *is* the out-of-the-box experience. Stored by
    // name rather than position, so this can be rearranged without disturbing
    // an order somebody has already chosen for themselves.
    //
    // The three Apple hosts lead, because they carry the same catalogue and
    // that catalogue is the one with the voices in it. [BINI_LYRICS] goes
    // first of the three: it is the only one that will answer to a recording
    // rather than to a name, and it is where the ISRC the others use comes
    // from. [LYRICS_PLUS] has the finest timing of the lot and the least
    // reliable hosting — behind them rather than in front, so a track does not
    // wait on a mirror that is down to be told what three other hosts already
    // had.
    //
    // [LRC_RED] is ahead of all of them: it is where [BINI_LYRICS] gets its
    // documents now, so asking it directly is the same Apple timing without
    // the middleman, and it is the one a dead mirror can't take down with it.
    LRC_RED(
        baseLabel = "lrc.red",
        baseDetail = "Apple timings by recording, syllable by syllable",
        wordSynced = true,
    ),
    // An installed `.sflx` extension that can answer lyrics — Apple's own
    // timings, through the listener's own subscription. Ahead of the hosts
    // that ask Apple for the same timings without being asked, because it is
    // the one that can also answer for a track the others have never heard of.
    // It costs nothing when no such extension is installed: the host says so
    // without reaching the network.
    EXTENSION(
        baseLabel = "Mornye extension",
        baseDetail = "Apple timings from an installed .sflx extension",
        wordSynced = true,
    ),
    BINI_LYRICS(
        baseLabel = "BiniLyrics",
        baseDetail = "The same Apple timings, matched on the recording itself",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        baseLabel = "BetterLyrics",
        baseDetail = "Apple Music timings, word by word",
        wordSynced = true,
    ),
    BETTER_LYRICS_PORTATO(
        baseLabel = "BetterLyrics Portato",
        baseDetail = "QQ Music karaoke timings through BetterLyrics",
        wordSynced = true,
    ),
    PAXSENIX(
        baseLabel = "PaxSenix",
        baseDetail = "Apple Music timings through the original keyless provider",
        wordSynced = true,
    ),
    PAXSENIX_SPOTIFY(
        baseLabel = "PaxSenix: Spotify",
        baseDetail = "Spotify lyrics with PaxSenix fallback; API key required",
        wordSynced = false,
    ),
    PAXSENIX_MUSIXMATCH(
        baseLabel = "PaxSenix: Musixmatch",
        baseDetail = "Musixmatch timings with PaxSenix fallback; API key required",
        wordSynced = true,
    ),
    LYRICS_PLUS(
        baseLabel = "LyricsPlus",
        baseDetail = "Syllable by syllable, on community mirrors",
        wordSynced = true,
    ),
    SIMP_MUSIC(
        baseLabel = "SimpMusic",
        baseDetail = "Matched on the video, so never the wrong edit",
        wordSynced = true,
        hidden = true,
    ),
    UNISON(
        baseLabel = "Unison",
        baseDetail = "Contributed by listeners, so it has what nobody licensed",
        wordSynced = true,
    ),
    YOUTUBE_TRANSCRIPT(
        baseLabel = "YouTube captions",
        baseDetail = "Timed captions matched to the exact playing video",
        wordSynced = false,
    ),
    YOUTUBE_MUSIC(
        baseLabel = "YouTube Music",
        baseDetail = "Plain lyrics from the playing video's Lyrics tab",
        wordSynced = false,
    ),
    MEGALOBIZ(
        baseLabel = "Megalobiz",
        baseDetail = "Community-made, whole-line LRC",
        wordSynced = false,
        hidden = true,
    ),
    KUGOU(
        baseLabel = "KuGou",
        baseDetail = "Whole lines, strong outside the English catalogue",
        wordSynced = false,
    ),
    LRCLIB(
        baseLabel = "LRCLIB",
        baseDetail = "Whole lines only, and always up",
        wordSynced = false,
    ),
    MUSIXMATCH(
        baseLabel = "Musixmatch",
        baseDetail = "Whole lines, from the biggest lyrics database there is",
        wordSynced = false,
    ),
    GENIUS(
        baseLabel = "Genius",
        baseDetail = "Plain text fallback, massive web catalogue",
        wordSynced = false,
    ),
    ;

    /**
     * The name this source is known by.
     *
     * For every source but one, that is written in the enum. The exception is
     * [EXTENSION]: it is not a service but a *package* on this device, and the
     * package has a name — "Apple Music", for the one in the store. Showing
     * "Mornye extension" instead was naming a kind of thing where the
     * listener had a particular thing in mind, and it left them unable to
     * tell which package the row referred to. The host publishes the
     * installed package's own name; this shows it.
     */
    val label: String
        get() = if (this == EXTENSION) LyricsRepository.extensionLabel ?: baseLabel else baseLabel

    /**
     * The one line under the name.
     *
     * For [EXTENSION] it says what is missing when the package is installed
     * and cannot answer yet — Apple's asks the listener for their MusicKit
     * token, and answers nothing at all until it has one. Without that, the
     * player says "no lyrics found" for every song and nobody is told why.
     */
    val detail: String
        get() {
            if (this != EXTENSION) return baseDetail
            val setup = LyricsRepository.extensionSetup ?: return baseDetail
            return "Needs ${setup.settingLabel} — open ${setup.extensionName} settings"
        }

    /**
     * What this source is waiting for, when it is waiting for something.
     *
     * Null for every source but [EXTENSION], and null for that one too when
     * its package is installed and ready.
     */
    val setupRequirement: LyricsRepository.ExtensionSetup?
        get() = if (this == EXTENSION) LyricsRepository.extensionSetup else null

    companion object {
        /** Every source a listener can see and choose, in default priority. */
        val offered: List<LyricsSource> = entries.filterNot { it.hidden }

        /**
         * A saved order brought up to date with this build: hidden sources
         * dropped, and any source added since it was saved slotted in right
         * after the one it is declared behind — at the very top if nothing is
         * declared ahead of it. So a new source lands where a fresh install
         * puts it relative to its neighbours, rather than under every
         * line-only fallback where it would never get to answer.
         */
        fun ordered(saved: List<LyricsSource>): List<LyricsSource> {
            val result = saved.filter { it in offered }.distinct().toMutableList()
            offered.forEachIndexed { index, source ->
                if (source in result) return@forEachIndexed
                val before = offered.subList(0, index).lastOrNull { it in result }
                result.add(before?.let { result.indexOf(it) + 1 } ?: 0, source)
            }
            return result
        }
    }
}
