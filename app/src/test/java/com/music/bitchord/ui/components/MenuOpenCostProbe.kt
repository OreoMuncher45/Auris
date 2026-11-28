package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.padding
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
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.screens.QualityTarget
import dev.chrisbanes.haze.hazeSource
import com.music.bitchord.ui.components.menu.AnchoredItemMenu
import com.music.bitchord.ui.components.menu.AnchoredMenu
import com.music.bitchord.ui.components.menu.MenuHeader
import androidx.compose.ui.geometry.Rect
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
 * `MENU_PROBE=1 ./gradlew :app:testProdDebugUnitTest --tests '*MenuOpenCostProbe*' --info`,
 * and the numbers are printed as `PROBE` lines (test output needs `--info`).
 * Allocation is reported as n/a where the JVM doesn't measure it. Runs on
 * x86_64 and macOS arm64; Linux arm64 excludes the class in Gradle (no
 * Conscrypt build for Robolectric there), so the command prints nothing.
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

    // java.lang.management is on the JVM but not on the android.jar test
    // classpath, hence reflection. Null where the JVM lacks the extension or
    // doesn't support allocation measurement; the scope counts still print.
    private val allocationSampler: (() -> Long)? by lazy {
        runCatching {
            val bean = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean").invoke(null)!!
            val ext = Class.forName("com.sun.management.ThreadMXBean")
            if (!ext.isInstance(bean)) return@runCatching null
            if (ext.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) return@runCatching null
            val method = ext.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            val sample: () -> Long = { method.invoke(bean, Thread.currentThread().id) as Long }
            sample
        }.getOrNull()
    }

    // -1 when unavailable, which kb() reports as n/a; -1 is also what the JVM
    // returns while allocation measurement is switched off.
    private fun allocated(): Long = allocationSampler?.let { runCatching(it).getOrDefault(-1L) } ?: -1L

    private fun kb(from: Long, to: Long): String = if (from < 0 || to < 0) "n/a" else "${(to - from) / 1024}"

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
            val aFirst = allocated()
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
            val aTotal = allocated()
            if (run > 0) {
                results += "first=$first later=$later framesRecomposing=$framesRecomposing allocFirstKB=${kb(a0, aFirst)} allocWindowKB=${kb(a0, aTotal)}"
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
            presentation = presentation,
        )
    }

    /** The song row's ⋮, which opened a tinted bottom sheet before UI 12's stage 2. */
    @Test
    fun songRowMenu() = measure("Song row ⋮ (AnchoredItemMenu, header + list rows)") { open, close ->
        Box(Modifier.fillMaxSize()) {
            AnchoredItemMenu(
                item = song.takeIf { open },
                anchorInWindow = { Rect(950f, 600f, 1060f, 710f) },
                onDismiss = close,
            ) { item ->
                MenuHeader(title = item.title, subtitle = item.artist)
                songRows(SongActionsPresentation.Menu, player = false)
            }
        }
    }

    @Test
    fun anchoredMenu() = measure("AnchoredMenu(standalone, Now Playing rows, no player backdrop)") { open, close ->
        Box(Modifier.fillMaxSize()) {
            val presence = rememberMenuPresence(expanded = open)
            if (presence.isShowing) {
                AnchoredMenu(presence = presence, anchor = { IntRect(950, 150, 1060, 260) }, onDismiss = close) {
                    songRows(SongActionsPresentation.Glass, player = true)
                }
            }
        }
    }

    /** The track sort, which was FrostedSortMenu before UI 12's stage 3. */
    @Test
    fun songSortMenu() = measure("Song sort (MenuHost, SongSortMenu)") { open, _ ->
        hostedMenu(open) { close -> com.music.bitchord.SongSortMenu(browseId = "probe", onChosen = close) }
    }

    /** The same content as [qualitySheet], in the app's in-place sheet. */
    @Test
    fun qualityAppSheet() = measure("Sheet rows (AppSheet)") { open, close ->
        Box(Modifier.fillMaxSize()) {
            com.music.bitchord.ui.components.menu.AppSheet(item = Unit.takeIf { open }, onDismiss = close) {
                sheetRows()
            }
        }
    }

    @Test
    fun qualitySheet() = measure("Sheet rows (ModalBottomSheet)") { open, close ->
        if (open) {
            ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.background) {
                sheetRows()
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


    /** The same four choices as [dropdownMenu], on the shared menu through the host. */
    @Test
    fun choiceMenu() = measure("Choices (MenuHost, 4 MenuChoiceRow)") { open, _ ->
        hostedMenu(open) { close ->
            listOf("Title A-Z", "Title Z-A", "Date added", "Date modified").forEachIndexed { i, label ->
                com.music.bitchord.ui.components.menu.MenuChoiceRow(
                    label = label,
                    selected = i == 0,
                    ink = Color.White,
                    accent = Color.Red,
                    selectedDescription = null,
                    onClick = close,
                )
            }
        }
    }

    /**
     * What both sheet cases carry: a heading and eight two-line choice rows,
     * the shape the Settings quality picker had when it was a sheet. The same
     * content in both, so the two differ only in the sheet around it.
     */
    @Composable
    private fun sheetRows() {
        androidx.compose.foundation.layout.Column {
            Text("Audio quality", style = MaterialTheme.typography.titleLarge)
            repeat(8) { i ->
                androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
                    Text("Rung $i", style = MaterialTheme.typography.bodyLarge)
                    Text("${i * 64} kbps · ${i * 30} MB/h", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    /** A page with the root's menu host over it, opening [content] while [open]. */
    @Composable
    private fun hostedMenu(
        open: Boolean,
        content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit,
    ) {
        val host = androidx.compose.runtime.remember { com.music.bitchord.ui.components.menu.MenuHostState() }
        androidx.compose.runtime.LaunchedEffect(open) {
            if (open) host.open(Rect(950f, 150f, 1060f, 260f), content = content) else host.close()
        }
        Box(Modifier.fillMaxSize()) {
            com.music.bitchord.ui.components.menu.MenuHostOverlay(host)
        }
    }
}
