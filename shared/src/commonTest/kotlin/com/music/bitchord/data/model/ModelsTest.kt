package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun aYouTubeVideoCoverIsAskedForAtTheRenditionItsSizeWants() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg".artworkAt(1200),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg".artworkAt(320),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg".artworkAt(160),
        )
        assertEquals(
            "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.webp",
            "https://img.youtube.com/vi/dQw4w9WgXcQ/default.webp".artworkAt(720),
        )
    }

    @Test
    fun aYouTubeCardGetsTheCardSizedRendition() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg".artworkAt(CARD_ART_PX),
        )
    }

    @Test
    fun anUpgradedYouTubeCoverComesBackDownForARow() {
        // Read back off the lock screen's artwork, the URL already names the
        // large copy; a row must not load it.
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg".artworkAt(160),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg".artworkAt(160),
        )
    }

    @Test
    fun aRenamedYouTubeCoverDropsTheQueryThatSignedItsOldRendition() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc&rs=def".artworkAt(1200),
        )
    }

    @Test
    fun theFallbackIsTheCopyEveryVideoHas() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youTubeThumbnailFallback("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"),
        )
        assertEquals(null, youTubeThumbnailFallback("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertEquals(null, youTubeThumbnailFallback("https://lh3.googleusercontent.com/x=w544-h544"))
    }

    @Test
    fun aRenditionNamedOnlyInTheQueryIsNotRewritten() {
        val url = "https://i.ytimg.com/vi/abc/0.jpg?ref=/hqdefault.jpg"
        assertEquals(url, url.artworkAt(720))
        assertEquals(null, youTubeThumbnailFallback("https://i.ytimg.com/vi/abc/0.jpg?x=/sddefault.jpg"))
    }

    @Test
    fun userInformationIsNotTheHost() {
        val url = "https://img.youtube.com:pw@images.example.test/vi/abc/hqdefault.jpg?token=abc"
        assertEquals(url, url.artworkAt(720))
        // A real thumbnail host with a port still upgrades.
        assertEquals(
            "https://i.ytimg.com:443/vi/abc/maxresdefault.jpg",
            "https://i.ytimg.com:443/vi/abc/hqdefault.jpg".artworkAt(1200),
        )
    }

    @Test
    fun thumbnailHostIsReadFromTheAuthorityNotTheQuery() {
        assertTrue(isYouTubeThumbnailHost("https://i.ytimg.com/vi/x/hqdefault.jpg"))
        assertTrue(isYouTubeThumbnailHost("//i.ytimg.com/vi/x/hqdefault.jpg"))
        // A CDN URL that merely carries a YouTube URL in its query is the CDN's.
        assertFalse(isYouTubeThumbnailHost("//cdn.example.test/img?u=https://i.ytimg.com/vi/x/hqdefault.jpg"))
        assertFalse(isYouTubeThumbnailHost("https://cdn.example.test/img?u=https://i.ytimg.com/vi/x/hqdefault.jpg"))
    }
}
