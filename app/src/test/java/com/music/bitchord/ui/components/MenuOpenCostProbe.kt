package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.FrostedSortMenu
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.screens.QualitySheet
import com.music.bitchord.ui.screens.QualityTarget
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.music.bitchord.ui.components.menu.AnchoredMenu
import com.music.bitchord.ui.components.menu.rememberMenuPresence
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What opening the representative menus costs (ticket 12): composition
 * scopes entered on the opening frame, scopes re-entered on later frames of the
 * open animation, frames that recomposed at all, and bytes allocated on the
 * test thread from the open to the end of a 600 ms window. JVM proxies; the
 * device numbers come from gfxinfo.
 *
 * A measuring tool rather than a check, so it only runs when asked:
 * `MENU_PROBE=1 ./gradlew :app:testProdDebugUnitTest --tests '*MenuOpenCostProbe*'`,
 * and the numbers are printed as `PROBE` lines.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MenuOpenCostProbe {

    @get:Rule
    val compose = createComposeRule()

    private class Counter : CompositionObserver {
        var enters = 0
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) { enters++ }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}
    }

    private fun recomposerOf(composition: Composition): Recomposer {
        var c: Any? = composition
        repeat(6) {
            if (c is Recomposer) return c as Recomposer
            val next = c!!.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                f.isAccessible = true
                (f.get(c) as? androidx.compose.runtime.CompositionContext)
            }
            c = next
        }
        error("no recomposer")
    }

    // java.lang.management is on the JVM but not on the android.jar test classpath.
    private val threadBean: Any = Class.forName("java.lang.management.ManagementFactory")
        .getMethod("getThreadMXBean").invoke(null)!!
    private val allocMethod = Class.forName("com.sun.management.ThreadMXBean")
        .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private fun allocated(): Long = allocMethod.invoke(threadBean, Thread.currentThread().id) as Long

    private fun measure(name: String, content: @Composable (open: Boolean, close: () -> Unit) -> Unit) {
        Assume.assumeTrue("set MENU_PROBE=1 to run", System.getenv("MENU_PROBE") == "1")
        var open by mutableStateOf(false)
        var recomposer: Recomposer? = null
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        com.music.bitchord.ui.player.PlayerPlatform.install(com.music.bitchord.ui.player.AndroidPlayerHost(ctx))
        com.music.bitchord.ui.AppUi.install(com.music.bitchord.ui.AndroidAppUiHost)
        compose.mainClock.autoAdvance = false
        var root: Composition? = null
        compose.setContent {
            val composition = currentComposer.composition
            SideEffect { if (recomposer == null) { recomposer = recomposerOf(composition); root = composition } }
            MaterialTheme { content(open) { open = false } }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val counter = Counter()
        val handles = mutableListOf<CompositionObserverHandle>()
        root!!.setObserver(counter)?.let { handles.add(it) }
        val reg = recomposer!!.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handles += composition.setObserver(counter)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })
        val results = mutableListOf<String>()
        repeat(4) { run ->
            counter.enters = 0
            val a0 = allocated()
            open = true
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val first = counter.enters
            val aFirst = allocated() - a0
            var later = 0
            var framesRecomposing = 0
            repeat(36) { // ~600 ms
                val before = counter.enters
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val d = counter.enters - before
                later += d
                if (d > 0) framesRecomposing++
            }
            val aTotal = allocated() - a0
            if (run > 0) {
                results += "first=$first later=$later framesRecomposing=$framesRecomposing allocFirstKB=${aFirst / 1024} allocWindowKB=${aTotal / 1024}"
            }
            open = false
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            repeat(40) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
        }
        reg.dispose()
        handles.forEach { it.dispose() }
        println("PROBE $name :: " + results.joinToString(" | "))
    }

    private val song = Song(
        videoId = "abc123",
        title = "Test Track",
        artist = "Test Artist",
        thumbnailUrl = null,
        albumId = "album-1",
        albumName = "Test Album",
        artistId = "artist-1",
    )

    @Composable
    private fun songRows(presentation: SongActionsPresentation, player: Boolean) {
        val noop: () -> Unit = {}
        SongActionsSheet(
            song = song,
            signedIn = true,
            likeStatus = LikeStatus.INDIFFERENT,
            onPlayNext = noop, onAddToQueue = noop, onStartRadio = noop, onDownload = noop,
            onToggleLike = noop, onToggleDislike = noop, onAddToPlaylist = noop,
            onAddToFolder = noop,
            onOpenAlbum = {}, onOpenArtist = {},
            showSleepTimer = player,
            onRollbackToOriginal = if (player) noop else null,
            onToggleAudioVersion = if (player) noop else null,
            onShare = noop, onSaveArtwork = noop,
            onLyricsOffset = if (player) noop else null,
            onOpenSing = if (player) noop else null,
            onOpenReplay = if (player) noop else null,
            spatialAudioOn = if (player) false else null,
            onToggleSpatialAudio = if (player) noop else null,
            onOpenListenTogether = if (player) noop else null,
            onOpenAutomix = if (player) noop else null,
            presentation = presentation,
        )
    }

    @Test
    fun songActionsSheet() = measure("SongActionsSheet(ModalBottomSheet)") { open, close ->
        if (open) {
            ModalBottomSheet(onDismissRequest = close, containerColor = Color.Transparent, dragHandle = null) {
                songRows(SongActionsPresentation.Sheet, player = false)
            }
        }
    }

    @Test
    fun anchoredMenu() = measure("AnchoredMenu(NowPlaying)") { open, close ->
        Box(Modifier.fillMaxSize()) {
            val presence = rememberMenuPresence(expanded = open)
            if (presence.isShowing) {
                AnchoredMenu(presence = presence, anchor = { IntRect(950, 150, 1060, 260) }, onDismiss = close) {
                    songRows(SongActionsPresentation.Glass, player = true)
                }
            }
        }
    }

    @Test
    fun frostedSortMenu() = measure("FrostedSortMenu") { open, close ->
        val haze = androidx.compose.runtime.remember { HazeState() }
        Box(Modifier.fillMaxSize().hazeSource(haze))
        if (open) {
            FrostedSortMenu(
                hazeState = haze,
                selected = SongSort.DEFAULT,
                onSelect = {},
                onFlipDateDirection = {},
                onDismiss = close,
            )
        }
    }

    @Test
    fun qualitySheet() = measure("QualitySheet(ModalBottomSheet)") { open, close ->
        if (open) {
            ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.background) {
                QualitySheet(target = QualityTarget.WIFI, selected = AudioQuality.HIGH, onSelect = {})
            }
        }
    }

    @Test
    fun dropdownMenu() = measure("M3 DropdownMenu(LocalMusic sort)") { open, close ->
        Box {
            DropdownMenu(expanded = open, onDismissRequest = close) {
                listOf("Title A-Z", "Title Z-A", "Date added", "Date modified").forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = if (i == 0) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = close,
                    )
                }
            }
        }
    }

}
