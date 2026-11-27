package com.music.bitchord

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.toMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QueueShuffleTierTest {

    // Both are process-wide singletons: what a test changes in them is put
    // back, so no other test class inherits a discarding prefs store or a
    // shuffle state it never set.
    private val prefsField = com.music.bitchord.data.settings.AppSettings::class.java
        .getDeclaredField("prefs").apply { isAccessible = true }
    private val shuffleFields = listOf("_enabled", "original", "requestedForNextQueue").map { name ->
        QueueShuffle::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    private var savedPrefs: Any? = null
    private var savedEnabled = false
    private var savedSettingEnabled = false
    private var savedShuffle: List<Any?> = emptyList()

    @Before
    fun saveSingletons() {
        savedPrefs = runCatching { prefsField.get(com.music.bitchord.data.settings.AppSettings) }.getOrNull()
        savedEnabled = QueueShuffle.enabled.value
        // Set on the flow directly in teardown: setShuffleEnabled would write
        // through whatever prefs store is installed by then.
        savedSettingEnabled = com.music.bitchord.data.settings.AppSettings.shuffleEnabled.value
        savedShuffle = shuffleFields.drop(1).map { it.get(QueueShuffle) }
    }

    @After
    fun restoreSingletons() {
        prefsField.set(com.music.bitchord.data.settings.AppSettings, savedPrefs)
        @Suppress("UNCHECKED_CAST")
        (shuffleFields[0].get(QueueShuffle) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = savedEnabled
        shuffleFields.drop(1).zip(savedShuffle).forEach { (field, value) -> field.set(QueueShuffle, value) }
        com.music.bitchord.data.settings.AppSettings.shuffleEnabled.value = savedSettingEnabled
    }

    private fun testSong(
        id: String,
        tier: QueueTier = QueueTier.CONTEXT,
        entryId: String = "entry-$id",
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        queueTier = tier,
        queueEntryId = entryId,
    )

    @Test
    fun `header play button builds the next queue in order with shuffle left on`() {
        installDiscardingPrefs()
        QueueShuffle.enableForNextQueue() // shuffle left on from the last queue

        QueueShuffle.disableForNextQueue()

        // playSongs reads this flag for the queue it is about to build (PlayerConnection).
        assertFalse(QueueShuffle.enabled.value)
    }

    @Test
    fun `a refused play leaves shuffle and its saved order alone`() {
        installDiscardingPrefs()
        QueueShuffle.enableForNextQueue()
        QueueShuffle.rememberStartingOrder(listOf(testSong("a"), testSong("b")))

        // Play asks for shuffle off; the play takes the request, then is refused
        // (no controller, or the host holds the controls) and never applies it.
        QueueShuffle.requestForNextQueue(false)
        QueueShuffle.takeRequestForNextQueue()

        assertTrue(QueueShuffle.enabled.value)
        assertEquals(listOf("entry-a", "entry-b"), QueueShuffle.savedOrder)
    }

    @Test
    fun `an accepted play applies the shuffle its button asked for`() {
        installDiscardingPrefs()
        QueueShuffle.enableForNextQueue()
        QueueShuffle.requestForNextQueue(false)

        QueueShuffle.applyForNextQueue(QueueShuffle.takeRequestForNextQueue())

        assertFalse(QueueShuffle.enabled.value)
    }

    @Test
    fun `a request is taken once, so it never reaches a later play`() {
        QueueShuffle.requestForNextQueue(true)

        assertEquals(true, QueueShuffle.takeRequestForNextQueue())
        assertNull(QueueShuffle.takeRequestForNextQueue())
    }

    @Test
    fun `a superseded build's starting order does not replace the saved order`() {
        val installed = listOf(testSong("a"), testSong("b"))
        QueueShuffle.rememberStartingOrder(installed)

        // A build that loses to a newer request computes its order but never installs it.
        QueueShuffle.startingOrder(listOf(testSong("x"), testSong("y")), startIndex = 0)

        assertEquals(listOf("entry-a", "entry-b"), QueueShuffle.savedOrder)
    }

    @Test
    fun `startingOrder preserves USER_QUEUE items at front and shuffles context`() {
        val selected = testSong("selected", tier = QueueTier.CONTEXT)
        val u1 = testSong("u1", tier = QueueTier.USER_QUEUE)
        val u2 = testSong("u2", tier = QueueTier.USER_QUEUE)
        val c1 = testSong("c1", tier = QueueTier.CONTEXT)
        val c2 = testSong("c2", tier = QueueTier.CONTEXT)
        val c3 = testSong("c3", tier = QueueTier.CONTEXT)
        val a1 = testSong("a1", tier = QueueTier.AUTOPLAY)

        val input = listOf(selected, u1, u2, c1, c2, c3, a1)
        val result = QueueShuffle.startingOrder(input, startIndex = 0)

        assertEquals("selected", result[0].videoId)
        assertEquals("u1", result[1].videoId)
        assertEquals("u2", result[2].videoId)

        // Context items are between indices 3 and 5 inclusive
        val resultContextIds = result.subList(3, 6).map { it.videoId }.toSet()
        assertEquals(setOf("c1", "c2", "c3"), resultContextIds)

        // Autoplay at index 6
        assertEquals("a1", result[6].videoId)
    }

    @Test
    fun `restoreOrder deterministically restores duplicate songs using unique queueEntryIds`() {
        val entry1 = "uuid-1"
        val entry2 = "uuid-2"
        val original = listOf(entry1, entry2)
        val shuffled = listOf(entry2, entry1)

        val restoredIndices = QueueShuffle.restoreOrder(shuffled, original)
        val restoredEntries = restoredIndices.map { shuffled[it] }

        assertEquals(original, restoredEntries)
    }

    @Test
    fun `reproduce album alternating skip and shuffle toggle`() {
        installDiscardingPrefs()

        val albumSongs = (1..10).map { testSong("track-$it", QueueTier.CONTEXT, "entry-$it") }
        val items = albumSongs.map { it.toMediaItem() }.toMutableList()
        var currentIndex = 0

        val player = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.common.Player::class.java.classLoader,
            arrayOf(androidx.media3.common.Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args[0] as Int]
                "getCurrentMediaItemIndex" -> currentIndex
                "getCurrentMediaItem" -> items.getOrNull(currentIndex)
                "replaceMediaItems" -> {
                    val from = args[0] as Int
                    val to = args[1] as Int
                    val newItems = args[2] as List<androidx.media3.common.MediaItem>
                    for (i in (to - 1) downTo from) {
                        items.removeAt(i)
                    }
                    items.addAll(from, newItems)
                    null
                }
                "seekToNextMediaItem" -> {
                    if (currentIndex < items.size - 1) {
                        currentIndex++
                    }
                    null
                }
                else -> null
            }
        } as androidx.media3.common.Player

        // Start with shuffle off
        QueueShuffle.setEnabled(false)

        repeat(20) { step ->
            println("Step $step: currentIndex=$currentIndex, count=${items.size}, shuffle=${QueueShuffle.enabled.value}")
            player.seekToNextMediaItem()
            // When reaching near the end of album (e.g. index 8), simulate AutoPlay appending tracks with stable unique IDs
            if (currentIndex >= 8 && items.size == 10) {
                val autoplay = (1..5).map { testSong("autoplay-$it", QueueTier.AUTOPLAY, "entry-auto-$it").toMediaItem() }
                items.addAll(autoplay)
            }
            QueueShuffle.toggle(player)

            // Invariants:
            // 1. All queueEntryIds remain distinct across the queue
            val entryIds = items.map { it.queueEntryId ?: it.mediaId }
            assertEquals("All queue entry IDs must remain unique at step $step", items.size, entryIds.toSet().size)
        }
    }

    /** AppSettings writes through to prefs; these tests only read the flows, so writes go nowhere. */
    private fun installDiscardingPrefs() {
        val editor = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            if (method.returnType == android.content.SharedPreferences.Editor::class.java) proxy else null
        }
        val prefs = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, _ ->
            if (method.name == "edit") editor else null
        } as android.content.SharedPreferences
        val field = com.music.bitchord.data.settings.AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(com.music.bitchord.data.settings.AppSettings, prefs)
    }
}
