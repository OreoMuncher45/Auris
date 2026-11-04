package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.QUICK_PICKS_FALLBACK_TITLE
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.isQuickPicksShelfTitle
import com.music.bitchord.data.model.normalizeShelfTitle
import com.music.bitchord.data.model.orderHomeShelves
import com.music.bitchord.data.model.withQuickPicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick-picks row: the one row of Home that may be missing from the
 * feed, and the row the Flow and Replay cards ride in.
 *
 * These are the rules the page is built on, written down where they cannot
 * drift: the row is recognised in every language the app can be in, the feed
 * without it still gets one, and a feed with it keeps exactly one.
 */
class HomeQuickPicksTest {

    private fun item(title: String, videoId: String = "v-$title") = ShelfItem(
        title = title,
        subtitle = "Artist",
        thumbnailUrl = null,
        videoId = videoId,
        browseId = null,
    )

    private fun shelf(title: String, vararg titles: String) =
        HomeShelf(title = title, items = titles.map { item(it) })

    @Test
    fun `the row is recognised in every language the app ships`() {
        val titles = listOf(
            "Quick picks",
            "Quick mixes",
            "Lựa chọn nhanh",
            "クイック ミックス",
            "Escolhas rápidas",
            "Hızlı seçimler",
            "Pilihan cepat",
            "Scelte rapide",
            "Schnellauswahl",
            "Selección rápida",
            "Szybki wybór",
            "Sélection rapide",
            "Быстрый выбор",
            "בחירות מהירות",
            "त्वरित चयन",
            "快速选择",
        )
        titles.forEach { assertTrue(it, isQuickPicksShelfTitle(it)) }
    }

    @Test
    fun `a title outside the table is still the row when it says quick`() {
        // An account language the app does not ship: the word is the net.
        assertTrue(isQuickPicksShelfTitle("Snella val"))
        assertTrue(isQuickPicksShelfTitle("Μίγματα γρήγορα"))
        assertTrue(isQuickPicksShelfTitle("Mixtapes rápidos"))
        assertTrue(isQuickPicksShelfTitle("Misturas rápidas"))
    }

    @Test
    fun `an ordinary shelf is not the row`() {
        listOf(
            "Recently played",
            "Listen again",
            "Mixes",
            "Trending",
            "Albums",
            "Recommended for you",
        ).forEach { assertFalse(it, isQuickPicksShelfTitle(it)) }
    }

    @Test
    fun `a title folds to what two spellings agree on`() {
        assertEquals("quick picks", normalizeShelfTitle("  Quick   Picks! "))
        assertEquals("hizli secimler", normalizeShelfTitle("Hızlı Seçimler"))
        assertEquals("seleccion rapida", normalizeShelfTitle("Selección rápida"))
        assertEquals("quick picks", normalizeShelfTitle("Quick-Picks"))
    }

    @Test
    fun `a feed with the row keeps it, and only it`() {
        val feed = listOf(
            shelf("Recents", "one"),
            shelf("Quick picks", "two", "three"),
            shelf("Trending", "four"),
        )
        val result = feed.withQuickPicks()
        assertEquals(3, result.size)
        assertEquals("Quick picks", result[1].title)
        assertEquals(listOf("two", "three"), result[1].items.map { it.title })
    }

    @Test
    fun `two rows claiming to be the row leave one`() {
        val feed = listOf(
            shelf("Quick picks", "one"),
            shelf("Quick mixes", "two"),
        )
        val result = feed.withQuickPicks()
        assertEquals(1, result.count { isQuickPicksShelfTitle(it.title) })
        assertEquals("Quick picks", result.single { isQuickPicksShelfTitle(it.title) }.title)
    }

    @Test
    fun `a feed without the row gets one, built from the feed's own songs`() {
        val feed = listOf(
            shelf("Recents", "recent-one"),
            shelf("Trending", "trend-one", "trend-two"),
        )
        val result = feed.withQuickPicks()
        val picks = result.single { isQuickPicksShelfTitle(it.title) }
        assertEquals(QUICK_PICKS_FALLBACK_TITLE, picks.title)
        // Recents is the account's history, not a recommendation: it stays out
        // of the stand-in.
        assertEquals(listOf("trend-one", "trend-two"), picks.items.map { it.title })
    }

    @Test
    fun `a row that came without songs is stood in for`() {
        val feed = listOf(
            shelf("Quick picks"),
            shelf("Trending", "trend-one"),
        )
        val result = feed.withQuickPicks()
        val picks = result.single { isQuickPicksShelfTitle(it.title) }
        assertEquals(QUICK_PICKS_FALLBACK_TITLE, picks.title)
        assertEquals(listOf("trend-one"), picks.items.map { it.title })
    }

    @Test
    fun `a feed with nothing to stand in with keeps what it has`() {
        val feed = listOf(shelf("Recents", "one"))
        val result = feed.withQuickPicks()
        assertEquals(1, result.size)
        assertEquals("Recents", result.single().title)
    }

    @Test
    fun `without an order of its own the quick-picks row is first`() {
        val feed = listOf(
            shelf("Trending", "one"),
            shelf("Quick picks", "two"),
            shelf("Albums", "three"),
        )
        assertEquals(
            listOf("Quick picks", "Trending", "Albums"),
            orderHomeShelves(feed, emptyList()).map { it.title },
        )
    }

    @Test
    fun `the listener's order is the feed's order`() {
        val feed = listOf(
            shelf("Trending", "one"),
            shelf("Quick picks", "two"),
            shelf("Albums", "three"),
            shelf("Playlists", "four"),
        )
        // The bottom shelf, moved to the top: it is at the top of the feed,
        // not only at the top of the drawing of the feed.
        val ordered = orderHomeShelves(feed, listOf("Playlists", "Albums", "Quick picks", "Trending"))
        assertEquals(
            listOf("Playlists", "Albums", "Quick picks", "Trending"),
            ordered.map { it.title },
        )
    }

    @Test
    fun `an order saved before the quick-picks row keeps the row at the top`() {
        val feed = listOf(
            shelf("Trending", "one"),
            shelf("Quick picks", "two"),
            shelf("Albums", "three"),
        )
        val ordered = orderHomeShelves(feed, listOf("Albums", "Trending"))
        assertEquals("Quick picks", ordered.first().title)
        assertEquals(listOf("Albums", "Trending"), ordered.drop(1).map { it.title })
    }

    @Test
    fun `a shelf the order does not name keeps the feed's place after the named ones`() {
        val feed = listOf(
            shelf("Trending", "one"),
            shelf("Quick picks", "two"),
            shelf("Albums", "three"),
        )
        val ordered = orderHomeShelves(feed, listOf("Albums", "Quick picks"))
        assertEquals(listOf("Albums", "Quick picks", "Trending"), ordered.map { it.title })
    }
}
