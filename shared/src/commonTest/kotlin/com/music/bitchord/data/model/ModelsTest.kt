package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {
    @Test
    fun durationIsSharedAcrossTargets() {
        assertEquals(225_000L, "3:45".durationMillis())
        assertEquals(3_723_000L, "1:02:03".durationMillis())
        assertEquals(0L, "not-a-duration".durationMillis())
        assertEquals(0L, null.durationMillis())
    }

    @Test
    fun artworkSizeHintIsSharedAcrossTargets() {
        assertEquals(
            "https://example.test/w720-h720.jpg",
            "https://example.test/w120-h120.jpg".artworkAt(720),
        )
        assertEquals("https://example.test/cover.jpg", "https://example.test/cover.jpg".artworkAt(720))
    }

    @Test
    fun playerArtworkIsTheSizeOfTheWindowItIsDrawnIn() {
        // A 360dp phone at 3x: the cover is asked for at the width of the
        // screen, not at the largest rung on the ladder.
        assertEquals(1080, playerArtPx(360f, 3f))
        // The same phone at 2x: less of the cover, and less to decode.
        assertEquals(720, playerArtPx(360f, 2f))
    }

    @Test
    fun playerArtworkIsNeverBiggerThanTheLargestRung() {
        // A 1440px-wide phone: the ladder's ceiling holds, so nothing is asked
        // for that the ladder does not serve.
        assertEquals(PLAYER_ART_PX, playerArtPx(411f, 3.5f))
        assertEquals(PLAYER_ART_PX, playerArtPx(2000f, 2f))
    }

    @Test
    fun playerArtworkIsNeverSmallerThanTheFloor() {
        // A window too small to show even the floor: the floor holds rather
        // than the cover arriving as a handful of pixels.
        assertEquals(MIN_PLAYER_ART_PX, playerArtPx(200f, 1f))
        assertEquals(MIN_PLAYER_ART_PX, playerArtPx(0f, 3f))
    }

    private fun album(id: String) = ShelfItem(id, "", null, null, id)
    private fun song(id: String) = ShelfItem(id, "", null, id, null)

    @Test
    fun aReorderedReleaseShelfUnderAnotherTitleIsARepeat() {
        val albums = HomeShelf("Albums & singles", (1..10).map { album("a$it") })
        val reordered = HomeShelf("New albums & singles", (10 downTo 2).map { album("a$it") } + album("b"))
        val other = HomeShelf("Charts", (1..10).map { album("c$it") } + album("a1"))
        assertEquals(listOf(other), listOf(reordered, other).withoutRepeatsOf(listOf(albums)))
        assertEquals(listOf(albums), listOf(albums, reordered).withoutRepeatsOf(emptyList()))
    }

    @Test
    fun songShelvesAndAFewSharedAlbumsAreKept() {
        val recents = HomeShelf("Recents", (1..10).map { song("s$it") })
        val listenAgain = HomeShelf("Listen again", (1..10).map { song("s$it") } + album("a1") + album("a2"))
        val albums = HomeShelf("Albums & singles", (1..10).map { album("a$it") })
        assertEquals(listOf(listenAgain), listOf(listenAgain).withoutRepeatsOf(listOf(recents, albums)))
    }
}
