package com.music.bitchord.data.canvas

import java.text.Normalizer
import java.util.Locale

/**
 * Which provider a clip came from — read in one place, by
 * [CanvasArtworkPlayer][com.music.bitchord.ui.player.CanvasArtworkPlayer]'s
 * caller, to decide whether the backdrop should keep re-tinting itself off
 * the clip as it loops rather than settling on its first frame. See that
 * call site for why Spotify's Canvas gets the periodic re-tint and the other
 * three don't.
 */
enum class CanvasSource { SPOTIFY, OTHER }

/**
 * A looping video that stands in for a track's cover art — what Spotify calls
 * a Canvas and Apple calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors,
 * which is how the Apple provider hands over a second rendition of the same
 * clip when the preferred one won't decode. The three metadata fields are not
 * decoration: providers search by free text and will happily return the wrong
 * album's clip, so [matches] re-checks the answer against what's playing.
 */
data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: CanvasSource = CanvasSource.OTHER,
) {
    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match exactly once punctuation, case and accents
     * are stripped — a near miss here is a different song by the same artist,
     * which is the one failure mode users notice. A clip whose provider didn't
     * say what it is (no title or no artist) is rejected rather than waved
     * through: a common title searched loosely lands on someone else's song
     * often enough that "couldn't check" has to mean "no". The album is only held to
     * that standard when both sides know it: BitChord resolves a track's album
     * asynchronously after the player opens, and a track queued from search
     * may never get one, so requiring it outright would mean no canvas at all
     * for most of a session.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        if (title == null || artist == null) return false
        val wantedTitle = wantTitle.normalizeForMatch()
        val titleOk = wantedTitle.isNotEmpty() && title.normalizeForMatch() == wantedTitle

        val artistOk = artistsMatch(wantArtist, splitArtists(artist) + artist.normalizeForMatch())

        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForMatch() == wantAlbum.normalizeForMatch()

        return titleOk && artistOk && albumOk
    }
}

/**
 * Whether a search hit credited as [gotName] by [gotArtists] is exactly the track [wantName] by
 * [wantArtist]: the same title once [normalizeForMatch] has folded both, and every artist asked
 * for among the credits. The one check every provider's track search holds its hits to.
 */
fun canvasTrackMatches(
    gotName: String,
    gotArtists: List<String>,
    wantName: String,
    wantArtist: String,
): Boolean {
    val wantedName = wantName.normalizeForMatch()
    if (wantedName.isEmpty() || gotName.normalizeForMatch() != wantedName) return false
    return artistsMatch(wantArtist, gotArtists.map { it.normalizeForMatch() })
}

/**
 * Whether [credited] (already folded) names [wantArtist]: either the whole credit as one name, so
 * a band called "Earth, Wind & Fire" credited intact still matches, or every artist the credit
 * splits into, so a collaboration needs all of its members.
 */
private fun artistsMatch(wantArtist: String, credited: List<String>): Boolean {
    val names = credited.filter { it.isNotBlank() }
    if (names.isEmpty()) return false
    val whole = wantArtist.normalizeForMatch()
    if (whole.isNotEmpty() && whole in names) return true
    val wanted = splitArtists(wantArtist)
    return wanted.isNotEmpty() && wanted.all { it in names }
}

/**
 * Case, accents and punctuation all differ between YouTube Music, Apple and
 * Tidal for the same release — "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)" against
 * "Beyonce Crazy in Love feat Jay Z". Fold all three away before comparing.
 *
 * Letters and numbers of every script survive, along with the vowel signs
 * Indic scripts write as combining marks. Only Latin accents are dropped.
 * Folding to ASCII alone turned every Telugu, Devanagari, Cyrillic or CJK
 * title into the same empty string, so any two of them "matched".
 */
fun String.normalizeForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(LATIN_ACCENTS, "$1")
        .lowercase(Locale.ROOT)
        .replace(NOT_A_WORD_CHARACTER, " ")
        .replace(SPACES, " ")
        .trim()

// Only marks sitting on a Latin letter: Cyrillic й decomposes to и + U+0306,
// and dropping that mark would make two different letters one.
private val LATIN_ACCENTS = Regex("(\\p{IsLatin})\\p{InCombiningDiacriticalMarks}+")
private val NOT_A_WORD_CHARACTER = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
private val SPACES = Regex("\\s+")

/**
 * One credit string into its individual artists. Every service picks its own
 * separator — commas, ampersands, "feat.", a bare "x" between collaborators —
 * so comparing the joined strings would fail on formatting alone.
 */
fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATORS)
        .map { it.normalizeForMatch() }
        .filter { it.isNotBlank() }

private val ARTIST_SEPARATORS = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)
