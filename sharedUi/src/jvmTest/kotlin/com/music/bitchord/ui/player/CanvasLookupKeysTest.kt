package com.music.bitchord.ui.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player's canvas lookup has to run again whenever the track it answers for changes,
 * including a track that keeps the previous one's `videoId` and album but has a different title
 * or artist. The remembered clip resets on title and artist, so an effect that didn't restart on
 * them too left the player on still artwork with no lookup for the new track.
 *
 * Drives the real [rememberCanvasArtwork] on a headless [Recomposer] with a fake host that
 * answers every lookup with a clip named after the track it was asked about.
 */
class CanvasLookupKeysTest {

    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private val lookups = mutableListOf<String>()

    private fun clipFor(song: Song) =
        CanvasArtwork(url = "https://example.invalid/${song.title}.mp4", title = song.title, artist = song.artist)

    private val settings = Proxy.newProxyInstance(
        PlayerSettingsSource::class.java.classLoader,
        arrayOf(PlayerSettingsSource::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAnimatedCanvas", "getCanvasOverCellular" -> MutableStateFlow(true)
            "getPrioritizeSpotifyCanvas", "getSpotifyCanvasEnabled" -> MutableStateFlow(false)
            "getMeteredConnection" -> MutableStateFlow<Boolean?>(false)
            else -> error("setting ${method.name} isn't part of the canvas lookup")
        }
    } as PlayerSettingsSource

    private val host = Proxy.newProxyInstance(
        PlayerHost::class.java.classLoader,
        arrayOf(PlayerHost::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getSettings" -> settings
            "cachedCanvas" -> null
            // A suspend function that answers without suspending: the continuation is the
            // trailing argument and the return value is the result.
            "canvasFor" -> (args[0] as Song).let { lookups += it.title; clipFor(it) }
            else -> error("host ${method.name} isn't part of the canvas lookup")
        }
    } as PlayerHost

    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @BeforeTest
    fun install() {
        PlayerPlatform.install(host)
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun clear() {
        lookups.clear()
        Dispatchers.resetMain()
    }

    private fun song(title: String, artist: String = "Artist") = Song(
        videoId = "same-video",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = "Same Album",
        fromAutoplay = false,
    )

    @Test
    fun `a new title on the same video and album looks the canvas up again`() {
        val (shown, composes) = drive(song("First"), song("Second"))
        println("title change: body composed $composes times after the change")
        assertEquals(listOf("First", "Second"), lookups)
        assertEquals("Second", shown?.title)
        // Once for the new track, once for its clip landing. Never per frame.
        assertEquals(2, composes)
    }

    @Test
    fun `a new artist on the same video and album looks the canvas up again`() {
        val (shown, _) = drive(song("Same", artist = "One"), song("Same", artist = "Two"))
        assertEquals(listOf("Same", "Same"), lookups)
        assertEquals("Two", shown?.artist)
    }

    /** Composes [before], lets its lookup land, switches to [after] and lets that settle. */
    private fun drive(before: Song, after: Song): Pair<CanvasArtwork?, Int> =
        runBlocking(Dispatchers.Unconfined) {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val current = mutableStateOf(before)
            var shown: CanvasArtwork? = null
            var composes = 0
            val composition = Composition(NoNodes, recomposer)
            composition.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    composes++
                    shown = rememberCanvasArtwork(current.value)
                }
            }
            var now = 0L
            suspend fun pump() = repeat(FRAMES) {
                repeat(4) { yield() }
                Snapshot.sendApplyNotifications()
                clock.sendFrame(now)
                now += FRAME_NS
                repeat(4) { yield() }
            }
            pump()
            check(shown?.title == before.title) { "the first lookup never landed (shown=$shown)" }
            composes = 0
            current.value = after
            pump()
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            shown to composes
        }

    private companion object {
        const val FRAMES = 10
        const val FRAME_NS = 16_666_667L
    }
}
