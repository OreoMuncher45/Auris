package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the downloaded-playlist sync fetches, and what it refuses to.
 *
 * Four rules, and each one is a complaint somebody made:
 *
 *  - A song added to a downloaded playlist has to arrive. This is the whole
 *    point of the feature, and the rule that used to be missing.
 *  - A song already on the device is not fetched again.
 *  - A song the listener deleted by hand is never fetched again, however
 *    clearly the playlist still names it.
 *  - A file of the listener's own is not fetched at all.
 *
 * And one about ids: YouTube puts a `VL` on a playlist's page id and the
 * record does not always carry it, so a refresh asked for one has to find the
 * other.
 */
class DownloadedPlaylistSyncTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = id,
        artist = "an artist",
        thumbnailUrl = null,
    )

    private fun missing(
        remote: List<String>,
        onDisk: Set<String> = emptySet(),
        deleted: Set<String> = emptySet(),
    ) = missingFromPlaylist(
        remote.map { song(it) },
        onDisk = onDisk,
        deletedByHand = deleted,
    ).map { it.videoId }

    @Test
    fun `a song added to the playlist is fetched`() {
        assertEquals(listOf("new"), missing(listOf("old", "new"), onDisk = setOf("old")))
    }

    @Test
    fun `a song already on the device is not fetched again`() {
        assertTrue(missing(listOf("old"), onDisk = setOf("old")).isEmpty())
    }

    @Test
    fun `a song deleted by hand is never fetched again`() {
        assertTrue(
            "the listener deleted it on purpose; nothing may put it back",
            missing(listOf("gone"), deleted = setOf("gone")).isEmpty(),
        )
    }

    @Test
    fun `a file of the listener's own is not fetched`() {
        assertTrue(missing(listOf("file:///music/song.mp3")).isEmpty())
        assertTrue(missing(listOf("content://media/external/audio/media/1")).isEmpty())
    }

    @Test
    fun `a track the collection already asked for does not come back on a restart`() {
        // The on-disk projection is not complete: a cold start can read it
        // before the index is ready, and a file saved under a fetched id does
        // not always list the id the playlist asked for. Judged on disk alone
        // these looked new again on every launch, which is how completed and
        // cleared downloads kept reappearing in the queue.
        val missing = missingFromPlaylist(
            listOf(song("done"), song("new")),
            onDisk = emptySet(),
            deletedByHand = emptySet(),
            alreadyRequested = setOf("done"),
        ).map { it.videoId }
        assertEquals(listOf("new"), missing)
    }

    @Test
    fun `the two ids of one playlist are the same playlist`() {
        assertTrue(samePlaylist("VLPL123", "PL123"))
        assertTrue(samePlaylist("PL123", "VLPL123"))
        assertFalse(samePlaylist("PL123", "PL456"))
    }

    @Test
    fun `a downloaded playlist keeps itself whole unless the switch says otherwise`() {
        val playlist = SavedCollection(id = "PL1", title = "A playlist", playlist = true)
        assertTrue("downloading a playlist is asking for it to stay whole", playlist.autoDownload)
    }
}
