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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.theme.BitChordTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
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
 *
 * Ticket v4-09: a batch download saving its files must not recompose the root
 * either. The same probe, with the files saved through `Downloads` the way the
 * download service saves them, one frame apart, while each page that answers
 * to them is open: Quick Picks offline, the Library's On Device shelf, the
 * Downloads page. Each must still see every save. Also counted:
 * - quickPicks / library: runs of those pages' own composables.
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
        Downloads.init(app)
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

        /**
         * Runs by the composable function whose body ran, as `FileKt.function`.
         * A function opens its own restart group, so the frame that called
         * into the runtime is the function itself, whatever its scope's
         * restart lambda is compiled to.
         */
        val runsByFunction = HashMap<String, Int>()

        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            if (root == null && host != null && stack.lastOrNull() === host) root = scope
            stack += scope
            if (enteredToRun()) {
                runs[scope] = (runs[scope] ?: 0) + 1
                val function = callerOfComposer()
                runsByFunction[function] = (runsByFunction[function] ?: 0) + 1
            } else {
                rereads++
            }
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

        /** Times the body of the composable [function] in [fileClass] ran. */
        fun runsOf(fileClass: String, function: String): Int = runsByFunction["$fileClass.$function"] ?: 0

        fun clear() {
            runs.clear()
            runsByFunction.clear()
            rereads = 0
        }

        private fun callerOfComposer(): String = walker.walk { frames ->
            frames.filter { !it.className.startsWith(RUNTIME) && !it.className.startsWith(TEST) }
                .findFirst()
                .map { it.className + "." + it.methodName.substringBefore('-') }
                .orElse("")
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
            const val RUNTIME = "androidx.compose.runtime."
            val TEST: String = RootRecomposeOnPageAppendTest::class.java.name
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

    private data class Counts(
        val root: Int,
        val slot: Int,
        val detail: Int,
        val total: Int,
        val quickPicks: Int = 0,
        val library: Int = 0,
    )

    /** A playlist of [songs] as the only page on the detail stack. */
    private fun MainViewModel.open(songs: List<Song>) {
        detailStackFlow().value = listOf(page(songs))
    }

    @Suppress("UNCHECKED_CAST")
    private val partyFlow: MutableStateFlow<ListenTogether.State>
        get() = ListenTogether::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<ListenTogether.State>

    @After fun leaveParty() {
        partyFlow.value = ListenTogether.State()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> downloadsFlow(name: String): MutableStateFlow<T> =
        Downloads::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(Downloads) as MutableStateFlow<T>

    private val savedFlow get() = downloadsFlow<Map<String, String>>("_saved")
    private val savedMetadataFlow get() = downloadsFlow<Map<String, SavedSongMetadata>>("_savedMetadata")
    private val collectionsFlow get() = downloadsFlow<Map<String, SavedCollection>>("_collections")

    @After fun clearDownloads() {
        savedFlow.value = emptyMap()
        savedMetadataFlow.value = emptyMap()
        collectionsFlow.value = emptyMap()
        // The verified listing is memoised per generation; drop it, or the
        // next test starts from this one's.
        Downloads::class.java.getDeclaredField("allSongsCache").apply { isAccessible = true }
            .get(Downloads).let { (it as java.util.concurrent.atomic.AtomicReference<*>).set(null) }
        AppSettings.setOfflineMode(false)
    }

    /**
     * One file of a batch download landing, recorded the way the download
     * service records it: through `Downloads.remember`, which writes the
     * record, moves the listing generation and patches the memoised listing.
     * The file is real, because the Downloads page verifies each record on
     * reload and forgets one whose file is gone.
     */
    private fun save(i: Int) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(app.filesDir, "d$i.m4a").apply { writeBytes(ByteArray(16)) }
        val song = Song(videoId = "d$i", title = "Downloaded $i", artist = "Artist", thumbnailUrl = null)
        Downloads::class.java.getDeclaredMethod(
            "remember",
            Song::class.java,
            Song::class.java,
            android.net.Uri::class.java,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
            .invoke(Downloads, song, song, android.net.Uri.fromFile(file), null, null)
    }

    /** A playlist downloaded whole, its [SAVES] tracks not yet on the disk. */
    private fun recordBatch() {
        collectionsFlow.value = mapOf(
            BATCH_ID to SavedCollection(
                id = BATCH_ID,
                title = "A downloaded playlist",
                playlist = true,
                videoIds = (1..SAVES).map { "d$it" },
            ),
        )
    }

    /** One file that is already on the disk, so every page has a row to start from. */
    private fun seedOneSave() = save(0)

    /**
     * Sets the app up with [setUp], then runs each of [publishes] a frame
     * apart and counts what recomposed.
     */
    private fun measure(
        name: String,
        setUp: (MainViewModel) -> Unit,
        publishes: List<(MainViewModel) -> Unit>,
        nav: RootNav = RootNav(),
        sheets: RootSheets = RootSheets(),
        probe: String = "v4-08",
    ): Counts {
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
                        nav = nav,
                        sheets = sheets,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val viewModel = vm!!
        setUp(viewModel)
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

        counter.clear()
        publishes.forEach { publish ->
            publish(viewModel)
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
            quickPicks = counter.runsOf("com.music.bitchord.ui.screens.QuickPicksScreenKt", "QuickPicksScreen"),
            library = counter.runsOf("com.music.bitchord.ui.screens.LibraryScreenKt", "LibraryScreen"),
        )
        val n = publishes.size.toDouble()
        println(
            "PROBE $probe $name publishes=${publishes.size} root=${counts.root} slot=${counts.slot} " +
                "detail=${counts.detail} quickPicks=${counts.quickPicks} library=${counts.library} " +
                "total=${counts.total} rereads=${counter.rereads} " +
                "perPublish(root=${counts.root / n}, detail=${counts.detail / n}, total=${counts.total / n})",
        )
        reg.dispose()
        handles.forEach { it.dispose() }
        return counts
    }

    @Test fun `appending playlist pages recomposes the detail screen, not the root`() {
        val pages = (1..PAGES).map { p -> List(100 * (p + 1)) { song(it) } }
        val counts = measure(
            "append",
            setUp = { it.open(List(100) { i -> song(i) }) },
            publishes = pages.map { songs -> { vm: MainViewModel -> vm.open(songs) } },
        )
        assertTrue("the detail screen sees every page", counts.detail >= PAGES)
        assertEquals("root recomposes on page append", 0, counts.root)
    }

    @Test fun `matching rows in place recomposes the detail screen, not the root`() {
        val pending = List(100) { song(it, matched = false) }
        val matches = (1..MATCHES).map { m -> List(100) { song(it, matched = it < m) } }
        val counts = measure(
            "match",
            setUp = { it.open(pending) },
            publishes = matches.map { songs -> { vm: MainViewModel -> vm.open(songs) } },
        )
        assertTrue("the detail screen sees every match", counts.detail >= MATCHES)
        assertEquals("root recomposes on a row match", 0, counts.root)
    }

    /**
     * A listener in a party, with a playlist open. What the server and the
     * clock keep sending while a track plays: a `state` heartbeat (the same
     * track, a later playhead), a `pong` (a new round trip) and a `members`
     * frame (the same members, seen later). None of it changes anything the
     * root draws.
     */
    @Test fun `party heartbeats do not recompose the root`() {
        val track = PartyTrack(videoId = "v0", title = "Track 0")
        val members = listOf(
            PartyMember(memberId = "host", displayName = "Host", isHost = true, connected = true),
            PartyMember(memberId = "me", displayName = "Me", connected = true),
        )
        val party = ListenTogether.State(
            code = "ABCDEF",
            you = members[1],
            members = members,
            playback = PartyPlayback(seq = 1, track = track, isPlaying = true, startedBy = "host"),
            connection = ListenTogether.Connection.LIVE,
            clockSynced = true,
            roundTripMs = 40,
        )
        val beats = (1..HEARTBEATS).map { i ->
            { _: MainViewModel ->
                partyFlow.value = when (i % 3) {
                    0 -> party.copy(
                        playback = party.playback.copy(seq = 1L + i, positionMs = i * 5_000L, anchorMs = i * 5_000L),
                    )
                    1 -> party.copy(roundTripMs = 40L + i)
                    else -> party.copy(members = members.map { it.copy(lastSeenMs = i * 1_000L) })
                }
            }
        }
        val counts = measure(
            "party",
            setUp = {
                partyFlow.value = party
                it.open(List(100) { i -> song(i) })
            },
            publishes = beats,
        )
        assertEquals("root recomposes on a party heartbeat", 0, counts.root)
    }

    // ---- Ticket v4-09: a batch download saving its files ----

    private val saves get() = (1..SAVES).map { i -> { _: MainViewModel -> save(i) } }

    /**
     * Offline, on the Quick Picks page: the page is the device's library, so
     * it must gain a row for every file saved, and the root must not move.
     */
    @Test fun `saving files offline updates Quick Picks, not the root`() {
        val sheets = RootSheets().apply { showQuickPicks = true }
        val counts = measure(
            "save-quickpicks",
            setUp = {
                seedOneSave()
                AppSettings.setOfflineMode(true)
                val deadline = System.currentTimeMillis() + 5_000
                while (!AppSettings.effectiveOffline.value && System.currentTimeMillis() < deadline) {
                    compose.mainClock.advanceTimeByFrame()
                    Thread.sleep(5)
                }
                check(AppSettings.effectiveOffline.value) { "never went offline" }
            },
            publishes = saves,
            sheets = sheets,
            probe = "v4-09",
        )
        assertTrue("Quick Picks sees every save (${counts.quickPicks})", counts.quickPicks >= SAVES)
        assertTrue(
            "the newest save is on the page",
            compose.onAllNodesWithText("Downloaded $SAVES").fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals("root recomposes on a saved file", 0, counts.root)
    }

    /**
     * On the Library tab, with a playlist downloading whole: its card on the
     * On Device shelf counts the tracks on the disk, so the Library must see
     * every save, and the root must not move.
     */
    @Test fun `saving a batch download's files updates the Library, not the root`() {
        val nav = RootNav().apply { selectedTab = TAB_LIBRARY }
        val counts = measure(
            "save-library",
            setUp = {
                recordBatch()
                save(1)
            },
            publishes = saves.drop(1),
            nav = nav,
            probe = "v4-09",
        )
        assertTrue("the Library sees every save (${counts.library})", counts.library >= SAVES - 1)
        assertEquals("root recomposes on a saved file", 0, counts.root)
    }

    /**
     * The Downloads page open: it is a snapshot of the folder, retaken on
     * every save, and must be retaken for each one. The root must not move.
     */
    @Test fun `saving files reloads the open Downloads page, not the root`() {
        val published = ArrayList<List<DetailPage>>()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        val counts = try {
            measure(
                "save-local-page",
                setUp = { vm ->
                    seedOneSave()
                    vm.detailStackFlow().value = listOf(
                        page(Downloads.snapshotSongs()).copy(browseId = "local:downloads", title = "Downloads"),
                    )
                    val flow = vm.detailStackFlow()
                    watcher.launch { flow.collect { published += it } }
                },
                publishes = saves,
                probe = "v4-09",
            )
        } finally {
            watcher.cancel()
        }
        val snapshots = published.mapNotNull { stack ->
            (stack.lastOrNull()?.takeIf { it.browseId == "local:downloads" }?.songs as? UiState.Success)?.data?.size
        }.toSet()
        for (k in 1..SAVES) {
            assertTrue("the Downloads page was retaken after save $k (saw $snapshots)", (k + 1) in snapshots)
        }
        assertEquals("root recomposes on a saved file", 0, counts.root)
    }

    private companion object {
        const val SAVES = 8
        const val BATCH_ID = "PLbatch"
        const val HEARTBEATS = 9
        const val PAGES = 5
        const val MATCHES = 10
    }
}
