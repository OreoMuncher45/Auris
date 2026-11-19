package com.music.bitchord.data.podcast

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The smart download: the episodes already played, and the few that came
 * after them.
 *
 * The feed is newest-first, so "after it" means *above* it in the list —
 * the episodes published since. Getting that backwards would hand the
 * listener five episodes they have already heard and none of the ones they
 * have not.
 */
class PodcastSmartDownloadTest {

    private fun episode(id: String) = Song(
        videoId = "https://example.com/$id.mp3",
        title = "Episode $id",
        artist = "A Show",
        thumbnailUrl = null,
        fromAutoplay = false,
    )

    /** Newest first, as a feed hands them over. */
    private val feed = listOf("6", "5", "4", "3", "2", "1").map(::episode)

    private fun ids(songs: List<Song>) = songs.map { it.title.removePrefix("Episode ") }

    @Test
    fun takesThePlayedEpisodeAndTheEpisodesPublishedAfterIt() {
        val picked = PodcastSmartDownload.select(feed, listOf(episode("3")))

        assertEquals(listOf("3", "4", "5", "6"), ids(picked))
    }

    @Test
    fun takesAtMostFiveEpisodesAfterTheAnchor() {
        val long = (1..20).map { episode(it.toString()) }.reversed()

        val picked = PodcastSmartDownload.select(long, listOf(episode("1")))

        assertEquals(6, picked.size)
        assertEquals("1", picked.first().title.removePrefix("Episode "))
    }

    @Test
    fun takesTheNewestEpisodesWhenThereIsNoHistory() {
        val picked = PodcastSmartDownload.select(feed, emptyList())

        assertEquals(5, picked.size)
        assertEquals("6", picked.first().title.removePrefix("Episode "))
    }

    @Test
    fun takesTheAnchorOnItsOwnWhenNothingCameAfterIt() {
        val picked = PodcastSmartDownload.select(feed, listOf(episode("6")))

        assertEquals(listOf("6"), ids(picked))
    }

    @Test
    fun keepsEveryPlayedEpisodeAlongsideTheNewOnes() {
        val picked = PodcastSmartDownload.select(feed, listOf(episode("3"), episode("2")))

        assertEquals(listOf("3", "2", "4", "5", "6"), ids(picked))
    }

    @Test
    fun keepsAnAnchorTheFeedNoLongerCarries() {
        val picked = PodcastSmartDownload.select(feed, listOf(episode("9")))

        assertEquals("9", picked.first().title.removePrefix("Episode "))
        assertEquals(6, picked.size)
    }
}
