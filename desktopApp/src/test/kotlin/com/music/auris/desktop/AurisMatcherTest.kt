package com.music.auris.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The case that made addons useless outside search. */
class AurisMatcherTest {

    private val youtubeRow = Song(
        videoId = "kb_6Vz-k7Wc",
        title = "Tera Mera Rishta -  New Version (From \"Awarapan 2\")",
        artist = "Mithoon, Saaj Bhatt, Sayeed Quadri & Mustafa Zahid",
        thumbnailUrl = null,
    )

    @Test
    fun aCatalogueIsAskedForTheTitleWithoutItsPackaging() {
        val queries = AurisTrackMatcher.queries(youtubeRow)

        // The film, and the four-way credit, are not part of the question.
        assertEquals(listOf("tera mera rishta version mithoon", "tera mera rishta version"), queries)
    }

    @Test
    fun theCatalogueListingOfTheSameTakeMatchesADecoratedYouTubeTitle() {
        // What JioSaavn actually files this under, film and extra credits and upload decoration all
        // absent.
        val catalogueRow = Song(
            videoId = "jiosaavn:wfTj8NYC",
            title = "Tera Mera Rishta - New Version",
            artist = "Mithoon",
            thumbnailUrl = null,
            durationText = "6:05",
        )

        assertNotNull(AurisTrackMatcher.best(listOf(catalogueRow), youtubeRow))
    }

    @Test
    fun aDifferentTakeOfTheSameSongIsStillRefused() {
        // "New Version" is a take, so the plain listing is a different recording and must not stand
        // in for it.
        val plainListing = Song("jiosaavn:other", "Tera Mera Rishta", "Mithoon", null)

        assertNull(AurisTrackMatcher.best(listOf(plainListing), youtubeRow))
    }

    @Test
    fun theUploadConventionOfArtistFirstIsUnderstood() {
        // "Artist - Title" hands the title over to the tail rather than eating it, so this is the
        // same recording as a catalogue's plain listing.
        val upload = Song("v", "Mithoon - Tera Mera Rishta (Official Video)", "Mithoon", null)

        assertEquals("tera mera rishta", AurisTrackMatcher.searchableTitle(upload.title, upload.artist))
    }

    @Test
    fun aTakeIsIdentityAndHasToAgree() {
        val target = Song("v", "Song Title", "An Artist", null)
        val live = Song("a", "Song Title (Live)", "An Artist", null)
        val album = Song("b", "Song Title", "An Artist", null)

        // Asking for the album cut must not land on the live take…
        assertNull(AurisTrackMatcher.best(listOf(live), target))
        // …and asking for the live take must not land on the album cut.
        assertNull(AurisTrackMatcher.best(listOf(album), live))
        assertNotNull(AurisTrackMatcher.best(listOf(album), target))
    }

    @Test
    fun packagingThatOnlyLooksLikeATakeIsIgnored() {
        val target = Song("v", "Song Title (Album Version)", "An Artist", null)
        val plain = Song("a", "Song Title", "An Artist", null)

        // "Album Version" describes the ordinary release; treating it as a take would stop a source
        // ever matching the plain listing.
        assertNotNull(AurisTrackMatcher.best(listOf(plain), target))
    }

    @Test
    fun anUploadsTrailingLabelIsNotPartOfTheTitle() {
        assertEquals("paniyon sa", AurisTrackMatcher.searchableTitle("Paniyon Sa Full Song", ""))
        // Never stripped to nothing: a track really called "Song" keeps its name.
        assertEquals("song", AurisTrackMatcher.searchableTitle("Song", ""))
    }

    @Test
    fun onlyTheFirstCreditedArtistGoesIntoTheQuery() {
        assertEquals("mithoon", AurisTrackMatcher.primaryArtist("Mithoon, Saaj Bhatt & Sayeed Quadri"))
        assertEquals("arijit singh", AurisTrackMatcher.primaryArtist("Arijit Singh feat. Someone"))
    }
}
