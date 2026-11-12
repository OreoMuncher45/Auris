package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a Spotify row becomes a queue entry.
 *
 * The rule is small and load-bearing: a row that has not been matched yet is
 * still queued, as Spotify's own catalogue entry, and the player resolves it
 * when it plays. Without that, a Play pressed in the middle of a page's
 * matching either waited for the whole page — thirty seconds of silence — or
 * queued only the rows that had happened to match by then.
 */
class SpotifyQueueRowTest {

    private fun song(videoId: String) = Song(
        videoId = videoId,
        title = "Song",
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun aRowStillBeingMatchedIsQueuedAsSpotifyItself() {
        val row = song("${SPOTIFY_PENDING_PREFIX}abc123").asSpotifyQueueRow()
        assertEquals("${SPOTIFY_ROW_PREFIX}abc123", row.videoId)
        assertFalse(row.isUnresolvedSpotify)
    }

    @Test
    fun aRowNoSourceHoldsIsQueuedAsSpotifyItselfToo() {
        // "Missing" means the matcher looked and found nothing. The player
        // still asks the sources when the row is reached — the sources may
        // hold it by name even when the matcher did not find it — so it is
        // queued rather than dropped.
        val row = song("${SPOTIFY_MISSING_PREFIX}abc123").asSpotifyQueueRow()
        assertEquals("${SPOTIFY_ROW_PREFIX}abc123", row.videoId)
        assertFalse(row.isUnresolvedSpotify)
    }

    @Test
    fun aRowThatHasBeenMatchedKeepsItsOwnId() {
        // A match is a better answer than a name, and it is already in hand.
        val row = song("dQw4w9WgXcQ").asSpotifyQueueRow()
        assertEquals("dQw4w9WgXcQ", row.videoId)
    }

    @Test
    fun aWholePageIsQueuedTheSameWay() {
        val rows = listOf(
            song("${SPOTIFY_PENDING_PREFIX}a"),
            song("yt123"),
            song("${SPOTIFY_MISSING_PREFIX}b"),
        ).asSpotifyQueueRows()
        assertEquals(
            listOf("${SPOTIFY_ROW_PREFIX}a", "yt123", "${SPOTIFY_ROW_PREFIX}b"),
            rows.map { it.videoId },
        )
        assertTrue(rows.none { it.isUnresolvedSpotify })
    }
}
