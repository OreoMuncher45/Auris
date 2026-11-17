package com.music.bitchord.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.model.HomeChip
import com.music.bitchord.data.model.HomeHeader
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// The plain Application, as in the other Robolectric tests: the manifest one
// loads Java 21 bytecode a JDK 17 test worker can't define.
@Config(sdk = [34], application = Application::class)
class HomeFeedCacheTest {

    private val now = 1_800_000_000_000L

    private val snapshot = HomeFeedCache.Snapshot(
        shelves = listOf(
            HomeShelf(
                title = "Quick picks",
                subtitle = "Start radio from a song",
                items = listOf(
                    ShelfItem("Song", "Artist", "https://img/w120-h120", videoId = "v1", browseId = null),
                    ShelfItem("Album", "Artist · 2024", null, videoId = null, browseId = "MPRE1", year = 2024),
                ),
                moreBrowseId = "FEmusic_more",
            ),
            HomeShelf(
                title = "Popular",
                items = listOf(ShelfItem("Hit", "Band", null, null, "VL1", popularity = 3)),
            ),
        ),
        header = HomeHeader(
            greeting = "Good evening",
            shortcuts = listOf(ShelfItem("Liked Songs", "", "https://i/1", null, "spotify:liked")),
            chips = listOf(HomeChip("music", "Music", listOf(HomeChip("rock", "Rock")))),
        ),
    )

    @Test
    fun aKeptPageComesBackAsItWasKept() {
        val text = HomeFeedCache.encode(snapshot, "account:profile", now)
        assertEquals(snapshot, HomeFeedCache.decode(text, "account:profile", now + 60_000))
    }

    @Test
    fun anotherListenersPageIsNeverDrawn() {
        val text = HomeFeedCache.encode(snapshot, "account:profile", now)
        assertNull(HomeFeedCache.decode(text, "other:profile", now))
        assertNull(HomeFeedCache.decode(text, null, now))
    }

    @Test
    fun theListenerIsNotWrittenDown() {
        assertFalse(HomeFeedCache.encode(snapshot, "account:profile", now).contains("account:profile"))
    }

    @Test
    fun aPageOlderThanADayIsNotDrawn() {
        val text = HomeFeedCache.encode(snapshot, null, now)
        assertEquals(snapshot, HomeFeedCache.decode(text, null, now + HomeFeedCache.MAX_AGE_MS - 1))
        assertNull(HomeFeedCache.decode(text, null, now + HomeFeedCache.MAX_AGE_MS))
    }

    @Test
    fun savesAndLoadsPerCatalogue() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        HomeFeedCache.save(context, "SPOTIFY", null, snapshot, now)
        assertEquals(snapshot, HomeFeedCache.load(context, "SPOTIFY", null, now))
        assertNull(HomeFeedCache.load(context, "YOUTUBE", null, now))
    }
}
