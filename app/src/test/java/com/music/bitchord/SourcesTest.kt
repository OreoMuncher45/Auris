package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ModuleSource
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the source layer that can be wrong quietly.
 *
 * The cross-source matcher gets most of the attention because it is the one
 * piece here whose failure isn't visible: a bad match doesn't crash or show an
 * error, it plays a different recording under the right title.
 */
class SourcesTest {

    // ---- Track identity -----------------------------------------------------

    @Test
    fun `track key round-trips`() {
        val key = SourceRegistry.trackKey("cfg-1", "track-42")
        assertEquals("cfg-1" to "track-42", SourceRegistry.parseTrackKey(key))
    }

    /** A module's own track ids are opaque and some issue ones containing colons. */
    @Test
    fun `track key survives separators inside the track id`() {
        val key = SourceRegistry.trackKey("cfg-1", "al::bum::7")
        assertEquals("cfg-1" to "al::bum::7", SourceRegistry.parseTrackKey(key))
    }

    /** A bare YouTube video id must not be mistaken for a source-backed one. */
    @Test
    fun `plain video ids are not source keys`() {
        assertNull(SourceRegistry.parseTrackKey("dQw4w9WgXcQ"))
        assertNull(SourceRegistry.parseTrackKey(""))
    }

    // ---- Format reporting ---------------------------------------------------

    @Test
    fun `lossless is decided by codec, not bitrate`() {
        assertEquals(true, StreamFormat(codec = "flac", kbps = 900).isLossless)
        assertEquals(true, StreamFormat(codec = "alac").isLossless)
        // A high sample rate does not rescue a lossy codec.
        assertEquals(false, StreamFormat(codec = "opus", sampleRateHz = 192_000).isLossless)
        // Unknown stays unknown rather than defaulting to "no".
        assertNull(StreamFormat().isLossless)
    }

    @Test
    fun `summary states depth and rate and drops bitrate when lossless`() {
        val hiRes = StreamFormat(codec = "flac", kbps = 4608, sampleRateHz = 192_000, bitDepth = 24)
        assertEquals("FLAC · 24-bit · 192 kHz", hiRes.summary)

        val lossy = StreamFormat(codec = "mp3", kbps = 320, sampleRateHz = 44_100)
        assertEquals("MP3 · 44.1 kHz · 320 kbps", lossy.summary)

        assertEquals("Unknown format", StreamFormat().summary)
    }

    // ---- Cross-source matching ---------------------------------------------

    private fun song(title: String, artist: String, duration: String? = null) =
        Song(videoId = "x", title = title, artist = artist, thumbnailUrl = null, durationText = duration)

    private fun matches(candidate: Song, title: String, artist: String, durationSec: Int? = null) =
        TrackMatcher.matches(candidate, title, artist, durationSec)

    @Test
    fun `matches the same recording across differing catalogue titles`() {
        assertTrue(
            matches(
                song("Bohemian Rhapsody (Remastered 2011)", "Queen"),
                title = "Bohemian Rhapsody",
                artist = "Queen",
            ),
        )
        assertTrue(
            matches(
                song("Sunflower", "Post Malone, Swae Lee"),
                title = "Sunflower (feat. Swae Lee)",
                artist = "Post Malone",
            ),
        )
        // Punctuation and case are not identity.
        assertTrue(
            matches(
                song("Don't Stop Me Now", "QUEEN"),
                title = "Dont Stop Me Now",
                artist = "Queen",
            ),
        )
    }

    /**
     * The one that sent this back for a rewrite. YouTube files the track under
     * the film it is from and credits the lead singer; the module holds the
     * same audio under the bare title and credits the duet. Every part of that
     * disagreement is packaging.
     */
    @Test
    fun `matches a film credit against a bare catalogue listing`() {
        assertTrue(
            matches(
                song("Paniyon Sa", "Atif Aslam, Tulsi Kumar", duration = "4:07"),
                title = "Paniyon Sa (From \"Satyameva Jayate\")",
                artist = "Atif Aslam",
                durationSec = 247,
            ),
        )
        // And the other way round, which is how a module-queued track finds
        // its YouTube seed for radio.
        assertTrue(
            matches(
                song("Paniyon Sa (From \"Satyameva Jayate\")", "Atif Aslam"),
                title = "Paniyon Sa",
                artist = "Atif Aslam, Tulsi Kumar",
            ),
        )
    }

    /** The trailing labels an upload hangs on a title with no brackets to hold them. */
    @Test
    fun `strips upload labelling from either side`() {
        assertTrue(matches(song("Tum Hi Ho", "Arijit Singh"), "Tum Hi Ho Full Song", "Arijit Singh"))
        assertTrue(
            matches(
                song("Kesariya", "Arijit Singh"),
                title = "Kesariya - Brahmastra | Official Video",
                artist = "Arijit Singh",
            ),
        )
        // "Artist - Title" uploads: the head is the credit, not the song.
        assertTrue(
            matches(
                song("Believer", "Imagine Dragons"),
                title = "Imagine Dragons - Believer",
                artist = "Imagine Dragons",
            ),
        )
    }

    @Test
    fun `refuses a different song by the same artist`() {
        assertFalse(
            matches(
                song("The Show Must Go On", "Queen"),
                title = "Bohemian Rhapsody",
                artist = "Queen",
            ),
        )
    }

    /**
     * The dangerous case: same title, different artist. A cover, a tribute
     * album, or a completely unrelated song that happens to share a name — all
     * of which a loose matcher would happily play instead.
     */
    @Test
    fun `refuses a cover by a different artist`() {
        assertFalse(
            matches(
                song("Hurt", "Johnny Cash"),
                title = "Hurt",
                artist = "Nine Inch Nails",
            ),
        )
    }

    @Test
    fun `accepts a shared artist when catalogues credit differently`() {
        assertTrue(
            matches(
                song("Numb / Encore", "Jay-Z & Linkin Park"),
                title = "Numb / Encore",
                artist = "Linkin Park",
            ),
        )
    }

    /** A name inside another name is not a shared credit. */
    @Test
    fun `refuses an artist whose name merely contains the one asked for`() {
        assertFalse(matches(song("No One Knows", "Queens of the Stone Age"), "No One Knows", "Queen"))
    }

    /**
     * A different take is a different recording, and the direction it is asked
     * for in doesn't change that.
     */
    @Test
    fun `refuses a different take of the same song`() {
        assertFalse(matches(song("Shape of You (Acoustic)", "Ed Sheeran"), "Shape of You", "Ed Sheeran"))
        assertFalse(matches(song("Shape of You", "Ed Sheeran"), "Shape of You (Acoustic)", "Ed Sheeran"))
        assertFalse(matches(song("Creep (Live)", "Radiohead"), "Creep", "Radiohead"))
        assertFalse(matches(song("Faded", "Alan Walker"), "Faded (Slowed + Reverb)", "Alan Walker"))
        // Both sides saying the same thing is still a match.
        assertTrue(matches(song("Creep (Live)", "Radiohead"), "Creep [Live]", "Radiohead"))
    }

    /** Version-shaped words that describe the ordinary release, not a new take. */
    @Test
    fun `treats an album or radio version as the plain track`() {
        assertTrue(matches(song("Africa", "Toto"), "Africa (Album Version)", "Toto"))
        assertTrue(matches(song("Clocks", "Coldplay"), "Clocks (Radio Edit)", "Coldplay"))
    }

    /** The signal a title can't give: a loop, a snippet, or a whole album side. */
    @Test
    fun `refuses a candidate whose runtime is nowhere near`() {
        assertFalse(
            matches(
                song("Levitating", "Dua Lipa", duration = "1:00:12"),
                title = "Levitating",
                artist = "Dua Lipa",
                durationSec = 203,
            ),
        )
        // A few seconds of trimmed silence is not a different recording.
        assertTrue(
            matches(
                song("Levitating", "Dua Lipa", duration = "3:25"),
                title = "Levitating",
                artist = "Dua Lipa",
                durationSec = 203,
            ),
        )
    }

    /** With no artist to check against, the title alone has to carry it. */
    @Test
    fun `falls back to title alone when no artist is known`() {
        assertTrue(matches(song("Clair de Lune", "Debussy"), "Clair de Lune", ""))
        assertFalse(matches(song("Reverie", "Debussy"), "Clair de Lune", ""))
    }

    // ---- Choosing between candidates ---------------------------------------

    /**
     * Search backends rank however they like. The right copy is the one whose
     * runtime and credit agree, not the one that came back first.
     */
    @Test
    fun `picks the closest candidate rather than the first acceptable one`() {
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", durationSec = 247)
        val wrongLength = song("Paniyon Sa", "Atif Aslam", duration = "4:32")
        val right = song("Paniyon Sa", "Atif Aslam, Tulsi Kumar", duration = "4:06")
        assertEquals(right, TrackMatcher.best(listOf(wrongLength, right), target))
    }

    @Test
    fun `has nothing to offer when no candidate is the recording`() {
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam")
        assertNull(TrackMatcher.best(listOf(song("Paniyon Sa", "Some Cover Band")), target))
        assertNull(TrackMatcher.best(emptyList(), target))
    }

    // ---- Asking ------------------------------------------------------------

    /**
     * What a source is asked for. The raw title is never one of the queries:
     * a catalogue that lists "Paniyon Sa" has never stored the film name
     * YouTube prints alongside it, and scoring against words it doesn't hold
     * is how a source that had the track answered as if it didn't.
     */
    @Test
    fun `asks for the title a catalogue would file the track under`() {
        val queries = TrackMatcher.queries(
            TrackMatcher.Target("Paniyon Sa (From \"Satyameva Jayate\") | Official Video", "Atif Aslam, Tulsi Kumar"),
        )
        assertEquals(listOf("paniyon sa atif aslam", "paniyon sa"), queries)
    }

    /** A version marker is part of what to search for, not packaging to drop. */
    @Test
    fun `keeps the version marker in the query`() {
        assertEquals(
            "shape of you acoustic",
            TrackMatcher.queries(TrackMatcher.Target("Shape of You (Acoustic)", "")).single(),
        )
    }

    @Test
    fun `has nothing to ask for without a title`() {
        assertTrue(TrackMatcher.queries(TrackMatcher.Target("", "Atif Aslam")).isEmpty())
    }

    // ---- Quality tiers -----------------------------------------------------

    /**
     * Every module spells its quality differently, and the spelling is all
     * there is to go on when choosing which catalogue to open a track from.
     */
    @Test
    fun `reads a tier out of whatever a module calls it`() {
        assertEquals("LOSSLESS", ModuleSource.qualityTier("LOSSLESS"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("FLAC 16-bit / 44.1kHz"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("hires-96"))
        assertEquals("HIGH", ModuleSource.qualityTier("HIGH"))
        assertEquals("HIGH", ModuleSource.qualityTier("320kbps"))
        assertEquals("LOW", ModuleSource.qualityTier("128kbps"))
        assertEquals("LOW", ModuleSource.qualityTier("LOW"))
        assertNull(ModuleSource.qualityTier(""))
        assertNull(ModuleSource.qualityTier("Deadbeat"))
    }

    /** The codec wins the tie: a bit depth alongside FLAC is still FLAC. */
    @Test
    fun `does not mistake a bit depth for a bitrate tier`() {
        assertEquals("LOSSLESS", ModuleSource.qualityTier("24-bit / 192 kHz"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("FLAC 128"))
    }

    @Test
    fun `orders tiers worst to best`() {
        assertEquals(listOf("LOW", "HIGH", "LOSSLESS"), ModuleSource.TIERS)
    }

    // ---- Runtime parsing ---------------------------------------------------

    @Test
    fun `reads a runtime off a queue row`() {
        assertEquals(225, TrackMatcher.secondsOf("3:45"))
        assertEquals(3723, TrackMatcher.secondsOf("1:02:03"))
        assertNull(TrackMatcher.secondsOf(null))
        assertNull(TrackMatcher.secondsOf("live"))
        assertNull(TrackMatcher.secondsOf("0:00"))
    }
}
