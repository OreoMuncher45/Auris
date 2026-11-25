package com.music.bitchord.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The queue screen's edits on a real player whose queue has moved on since
 * the screen drew it: rows are found by entry, and what goes back in is the
 * player's current item, not the screen's copy of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class QueueScreenEditsTest {

    private lateinit var player: ExoPlayer

    private fun song(id: String, entry: String = "q-$id", tier: QueueTier = QueueTier.CONTEXT) =
        Song(videoId = id, title = id, artist = "", thumbnailUrl = null, queueTier = tier, queueEntryId = entry)

    private fun liveIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
    }

    @After
    fun tearDown() = player.release()

    @Test
    fun removeFindsTheRowAfterTheQueueGrewAndUndoPutsItBack() {
        val snapshot = listOf("now", "a", "b", "c").map { song(it) }
        player.setMediaItems(snapshot.map { it.toMediaItem() })
        // AutoPlay appended, and a song was queued at the head, after the screen drew.
        player.addMediaItem(1, song("x").toMediaItem())
        player.addMediaItem(song("auto").toMediaItem())

        val removal = player.removeQueueRows(QueueEdits.targetsAt(snapshot, listOf(2)), snapshot)!!
        assertEquals(listOf("now", "x", "a", "c", "auto"), liveIds())

        player.restoreQueueRows(removal)
        assertEquals(listOf("now", "x", "a", "b", "c", "auto"), liveIds())
    }

    @Test
    fun aRowThatIsGoneIsNotReplacedByWhateverTookItsIndex() {
        val snapshot = listOf("now", "a", "b").map { song(it) }
        // Same length, different rows.
        player.setMediaItems(listOf("now", "p", "q").map { song(it).toMediaItem() })
        val removal = player.removeQueueRows(QueueEdits.targetsAt(snapshot, listOf(1, 2)), snapshot)
        assertEquals(null, removal)
        assertEquals(listOf("now", "p", "q"), liveIds())
        assertFalse(player.moveQueueRowsToUser(QueueEdits.targetsAt(snapshot, listOf(2)), true, snapshot))
    }

    @Test
    fun moveAndUndoUseThePlayersItemWhenItsMediaWasReplacedInPlace() {
        val video = song("vid", entry = "e1")
        val snapshot = listOf(song("now"), song("c1"), video)
        // The service swapped the video for its catalogue audio, keeping entry and tier.
        val audio = song("aud", entry = "e1")
        player.setMediaItems(listOf(song("now"), song("c1"), audio).map { it.toMediaItem() })

        assert(player.moveQueueRowsToUser(QueueEdits.targetsAt(snapshot, listOf(2)), true, snapshot))
        assertEquals(listOf("now", "aud", "c1"), liveIds())

        val removal = player.removeQueueRows(QueueEdits.targetsAt(snapshot, listOf(2)), snapshot)!!
        assertEquals("aud", removal.rows.single().song.videoId)
        player.restoreQueueRows(removal)
        assertEquals(listOf("now", "aud", "c1"), liveIds())
    }
}
