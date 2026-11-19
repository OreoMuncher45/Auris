package com.music.bitchord

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.theme.BitChordTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Ticket v4-08: publishing to the open detail page must not recompose the app
 * root.
 *
 * Renders the real [BitChordApp] with a 100-row playlist open, then publishes
 * to it the way `MainViewModel` does, one frame apart: [PAGES] more 100-row
 * pages (`fillIn`), or [MATCHES] rows swapped in place (a Spotify page's
 * matcher). Each publish is a new [DetailPage] in the stack. Counts restart
 * scopes entered, across every composition (the list's rows are
 * subcompositions of their own):
 * - root: [BitChordApp]'s own scope;
 * - slot: the [RootPage] slot the detail screen sits in;
 * - detail: [com.music.bitchord.ui.screens.DetailScreen]'s own scope;
 * - total: every scope.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RootRecomposeOnPageAppendTest {

    @get:Rule
    val compose = createComposeRule()

    @Before fun install() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayerPlatform.install(AndroidPlayerHost(app))
        AppUi.install(AndroidAppUiHost)
        AppSettings.init(app, AuthStore(app))
        // No player service in a unit test: the controller simply never connects.
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    private class Counter : CompositionObserver {
        /** The scope of the composable that calls [BitChordApp]; its first child is the root. */
        var host: RecomposeScope? = null
        var root: RecomposeScope? = null
        private val stack = ArrayList<RecomposeScope>()
        /** Times each scope's body actually ran. */
        val runs = HashMap<RecomposeScope, Int>()
        /**
         * Times a scope was invalidated only by derived states whose values
         * had not changed: Compose reports it entering and re-reads what it
         * read, and does not run it. Not a recomposition.
         */
        var rereads = 0

        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            if (root == null && host != null && stack.lastOrNull() === host) root = scope
            stack += scope
            if (enteredToRun()) runs[scope] = (runs[scope] ?: 0) + 1 else rereads++
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) { stack.removeAt(stack.lastIndex) }
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}

        val total get() = runs.values.sum()

        /**
         * Entries into the restart scopes of the composable [function] in
         * [fileClass]. A restartable function's scope re-runs through a
         * lambda its compiler names after it (`FileKt$Function$n`).
         */
        fun countOf(fileClass: String, function: String): Int {
            val name = Regex(Regex.escape("$fileClass\$$function\$") + "\\d+")
            return runs.entries.sumOf { (scope, n) -> if (name.matches(blockOf(scope))) n else 0 }
        }

        private fun blockOf(scope: RecomposeScope): String = runCatching {
            scope.javaClass.getDeclaredField("block").apply { isAccessible = true }.get(scope)?.javaClass?.name
        }.getOrNull().orEmpty()

        /**
         * Whether the composer is entering this scope to run it, or only to
         * re-read it. Compose 1.10 reports both through [onScopeEnter]: a run
         * enters from `ComposerImpl.enterRecomposeScope`, a re-read straight
         * from `recomposeToGroupEnd`.
         */
        private fun enteredToRun(): Boolean = walker.walk { frames ->
            frames.filter { it.className == COMPOSER }.findFirst().map { it.methodName }.orElse("")
        } != "recomposeToGroupEnd"

        private companion object {
            const val COMPOSER = "androidx.compose.runtime.ComposerImpl"
            val walker: StackWalker = StackWalker.getInstance()
        }
    }

    private fun recomposerOf(composition: Composition): Recomposer {
        var c: Any? = composition
        repeat(6) {
            if (c is Recomposer) return c as Recomposer
            c = c!!.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                f.isAccessible = true
                f.get(c) as? CompositionContext
            }
        }
        error("no recomposer")
    }

    private fun song(i: Int, matched: Boolean = true) = Song(
        videoId = if (matched) "v$i" else "pending$i",
        title = "Track $i",
        artist = "Artist",
        thumbnailUrl = null,
    )

    private fun page(songs: List<Song>) = DetailPage(
        browseId = "VLPLtest",
        title = "A long playlist",
        subtitle = "Someone",
        thumbnailUrl = null,
        songs = UiState.Success(songs),
        type = BrowseType.PLAYLIST,
    )

    @Suppress("UNCHECKED_CAST")
    private fun MainViewModel.detailStackFlow(): MutableStateFlow<List<DetailPage>> =
        MainViewModel::class.java.getDeclaredField("_detailStack")
            .apply { isAccessible = true }
            .get(this) as MutableStateFlow<List<DetailPage>>

    private data class Counts(val root: Int, val slot: Int, val detail: Int, val total: Int)

    /**
     * Opens [first] as the only detail page, then hands each of [publishes]
     * to the stack a frame apart and counts what recomposed.
     */
    private fun measure(name: String, first: List<Song>, publishes: List<List<Song>>): Counts {
        val counter = Counter()
        var recomposer: Recomposer? = null
        var vm: MainViewModel? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val composition = currentComposer.composition
            SideEffect { if (recomposer == null) recomposer = recomposerOf(composition) }
            val viewModel: MainViewModel = viewModel()
            vm = viewModel
            BitChordTheme(darkTheme = true) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    counter.host = currentRecomposeScope
                    BitChordApp(
                        darkTheme = true,
                        windowWidth = 411.dp,
                        windowHeight = 891.dp,
                        appBackdrop = backdrop,
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val stack = vm!!.detailStackFlow()
        stack.value = listOf(page(first))
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()

        val handles = mutableListOf<CompositionObserverHandle>()
        val reg = recomposer!!.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handles += composition.setObserver(counter)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })
        // Recompose the host once, under the observer, so the root's scope
        // is seen entering as its first child and can be told apart.
        counter.host!!.invalidate()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val root = checkNotNull(counter.root) { "root scope not found" }

        counter.runs.clear()
        counter.rereads = 0
        publishes.forEach { songs ->
            stack.value = listOf(page(songs))
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()

        val counts = Counts(
            root = counter.runs[root] ?: 0,
            slot = counter.countOf("com.music.bitchord.MainActivityKt", "RootPage"),
            detail = counter.countOf("com.music.bitchord.ui.screens.DetailScreenKt", "DetailScreen"),
            total = counter.total,
        )
        val n = publishes.size.toDouble()
        println(
            "PROBE v4-08 $name publishes=${publishes.size} root=${counts.root} slot=${counts.slot} " +
                "detail=${counts.detail} total=${counts.total} rereads=${counter.rereads} " +
                "perPublish(root=${counts.root / n}, detail=${counts.detail / n}, total=${counts.total / n})",
        )
        reg.dispose()
        handles.forEach { it.dispose() }
        return counts
    }

    @Test fun `appending playlist pages recomposes the detail screen, not the root`() {
        val pages = (1..PAGES).map { p -> List(100 * (p + 1)) { song(it) } }
        val counts = measure("append", first = List(100) { song(it) }, publishes = pages)
        assertTrue("the detail screen sees every page", counts.detail >= PAGES)
        assertEquals("root recomposes on page append", 0, counts.root)
    }

    @Test fun `matching rows in place recomposes the detail screen, not the root`() {
        val pending = List(100) { song(it, matched = false) }
        val matches = (1..MATCHES).map { m -> List(100) { song(it, matched = it < m) } }
        val counts = measure("match", first = pending, publishes = matches)
        assertTrue("the detail screen sees every match", counts.detail >= MATCHES)
        assertEquals("root recomposes on a row match", 0, counts.root)
    }

    private companion object {
        const val PAGES = 5
        const val MATCHES = 10
    }
}
