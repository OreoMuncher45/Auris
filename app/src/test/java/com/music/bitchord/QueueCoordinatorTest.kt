package com.music.bitchord

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.queueTier
import com.music.bitchord.playback.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy

class QueueCoordinatorTest {

    private fun testSong(
        id: String,
        tier: QueueTier = QueueTier.CONTEXT,
        entryId: String? = null,
        source: String? = null,
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        queueTier = tier,
        queueEntryId = entryId,
        playbackSource = source,
    )

    private fun createFakePlayer(
        items: MutableList<MediaItem>,
        currentIndex: () -> Int = { 0 },
    ): Player {
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItemIndex" -> currentIndex()
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args[0] as Int]
                "getCurrentMediaItem" -> items.getOrNull(currentIndex())
                "removeMediaItem" -> {
                    val index = args[0] as Int
                    items.removeAt(index)
                    null
                }
                "removeMediaItems" -> {
                    val from = args[0] as Int
                    val to = args[1] as Int
                    for (i in (to - 1) downTo from) {
                        items.removeAt(i)
                    }
                    null
                }
                else -> null
            }
        } as Player
    }

    @Test
    fun `asQueueEntry generates unique immutable entryId if null`() {
        val song = testSong("song1")
        val entry = song.asQueueEntry(QueueTier.USER_QUEUE)

        assertNotNull(entry.queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, entry.queueTier)
    }

    @Test
    fun `asQueueEntry preserves existing entryId immutably`() {
        val song = testSong("song1", entryId = "entry-123")
        val entry = song.asQueueEntry(QueueTier.CONTEXT)

        assertEquals("entry-123", entry.queueEntryId)
        assertEquals(QueueTier.CONTEXT, entry.queueTier)
    }

    @Test
    fun `buildContextQueue preserves upcoming user queue and interleave correctly`() {
        // Current timeline: [Track 0 (playing)] + [User Q 1] + [User Q 2] + [Old Autoplay]
        val userQ1 = testSong("u1", tier = QueueTier.USER_QUEUE, entryId = "entry-u1")
        val userQ2 = testSong("u2", tier = QueueTier.USER_QUEUE, entryId = "entry-u2")
        val currentTimeline = listOf(
            testSong("now", tier = QueueTier.CONTEXT),
            userQ1,
            userQ2,
            testSong("old-auto", tier = QueueTier.AUTOPLAY),
        )

        val albumSongs = listOf(
            testSong("album-1"),
            testSong("album-2"),
            testSong("album-3"),
        )

        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = currentTimeline,
            currentIndex = 0,
            newContextSongs = albumSongs,
            selectedIndex = 1, // User tapped album-2
            contextSource = QueueSource("Abbey Road", PlaybackSourceType.BROWSE, "album-id"),
        )

        // Expected: [album-2] + [userQ1, userQ2] + [album-1, album-3]
        assertEquals(5, result.size)
        assertEquals("album-2", result[0].videoId)
        assertEquals(QueueTier.CONTEXT, result[0].queueTier)
        assertEquals("Abbey Road", result[0].playbackSource)

        assertEquals("u1", result[1].videoId)
        assertEquals("entry-u1", result[1].queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, result[1].queueTier)

        assertEquals("u2", result[2].videoId)
        assertEquals("entry-u2", result[2].queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, result[2].queueTier)

        assertEquals("album-1", result[3].videoId)
        assertEquals(QueueTier.CONTEXT, result[3].queueTier)
        assertEquals("Abbey Road", result[3].playbackSource)

        assertEquals("album-3", result[4].videoId)
        assertEquals(QueueTier.CONTEXT, result[4].queueTier)
        assertEquals("Abbey Road", result[3].playbackSource)
    }

    @Test
    fun `buildOneOffQueue preserves upcoming user queue behind tapped song`() {
        val userQ = testSong("u1", tier = QueueTier.USER_QUEUE, entryId = "entry-u1")
        val currentTimeline = listOf(
            testSong("now", tier = QueueTier.CONTEXT),
            userQ,
            testSong("old-auto", tier = QueueTier.AUTOPLAY),
        )

        val searchHit = testSong("search-song")
        val result = QueueCoordinator.buildOneOffQueue(
            currentTimeline = currentTimeline,
            currentIndex = 0,
            tappedSong = searchHit,
            source = QueueSource("Search", PlaybackSourceType.SEARCH),
        )

        assertEquals(2, result.size)
        assertEquals("search-song", result[0].videoId)
        assertEquals(QueueTier.CONTEXT, result[0].queueTier)
        assertEquals("Search", result[0].playbackSource)

        assertEquals("u1", result[1].videoId)
        assertEquals("entry-u1", result[1].queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, result[1].queueTier)
    }

    @Test
    fun `findUserQueueInsertionIndex for playNext inserts at currentIndex + 1`() {
        val timeline = listOf(
            testSong("song0"),
            testSong("u1", tier = QueueTier.USER_QUEUE),
            testSong("c1", tier = QueueTier.CONTEXT),
        )

        val index = QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = true)
        assertEquals(1, index)
    }

    @Test
    fun `findUserQueueInsertionIndex for addToQueue inserts after last user queue item`() {
        val timeline = listOf(
            testSong("song0"),
            testSong("u1", tier = QueueTier.USER_QUEUE),
            testSong("u2", tier = QueueTier.USER_QUEUE),
            testSong("c1", tier = QueueTier.CONTEXT),
            testSong("a1", tier = QueueTier.AUTOPLAY),
        )

        val index = QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = false)
        assertEquals(3, index) // Ahead of c1
    }

    @Test
    fun `findUserQueueInsertionIndex for addToQueue with no user queue inserts ahead of context`() {
        val timeline = listOf(
            testSong("song0"),
            testSong("c1", tier = QueueTier.CONTEXT),
            testSong("c2", tier = QueueTier.CONTEXT),
        )

        val index = QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = false)
        assertEquals(1, index) // Ahead of c1
    }

    @Test
    fun `clearUserQueue removes only USER_QUEUE items after currentIndex`() {
        val items = mutableListOf(
            testSong("playing", tier = QueueTier.CONTEXT).toMediaItem(),
            testSong("u1", tier = QueueTier.USER_QUEUE).toMediaItem(),
            testSong("u2", tier = QueueTier.USER_QUEUE).toMediaItem(),
            testSong("c1", tier = QueueTier.CONTEXT).toMediaItem(),
            testSong("a1", tier = QueueTier.AUTOPLAY).toMediaItem(),
        )
        val player = createFakePlayer(items, currentIndex = { 0 })

        QueueCoordinator.clearUserQueue(player)

        assertEquals(3, items.size)
        assertEquals("playing", items[0].mediaId)
        assertEquals("c1", items[1].mediaId)
        assertEquals("a1", items[2].mediaId)
    }

    @Test
    fun `consumePlayedUserQueue prunes user queue items when entering context`() {
        var currentIndex = 2
        val items = mutableListOf(
            testSong("u1", tier = QueueTier.USER_QUEUE).toMediaItem(),
            testSong("u2", tier = QueueTier.USER_QUEUE).toMediaItem(),
            testSong("c1", tier = QueueTier.CONTEXT).toMediaItem(),
            testSong("c2", tier = QueueTier.CONTEXT).toMediaItem(),
        )
        val player = createFakePlayer(items, currentIndex = { currentIndex })

        QueueCoordinator.consumePlayedUserQueue(player)

        assertEquals(2, items.size)
        assertEquals("c1", items[0].mediaId)
        assertEquals("c2", items[1].mediaId)
    }
}
