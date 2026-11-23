package com.music.bitchord.ui.screens

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * What Radio's Your Mixes carousel costs, counted in runs of composable bodies
 * (the emulator has no signed-in account, so the carousel cannot be measured
 * there).
 *
 * - The mixes arriving after the page is up composes only the carousel and
 *   the cards on screen; the station tiles below are not composed again
 *   (the rows of the page's list each run once, as when the stations land).
 * - A sideways fling composes each card that comes on screen once, and no
 *   card twice.
 * - Scrolling the page past the carousel composes no card again.
 */
@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RadioMixesRecomposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayerPlatform.install(AndroidPlayerHost(app))
        AppUi.install(AndroidAppUiHost)
        AppSettings.init(app, AuthStore(app))
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    /** Runs of each RadioScreen composable's body, by function name. */
    private class Counter : CompositionObserver {
        val runs = HashMap<String, Int>()
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            // A scope Compose only re-reads, without running its body, enters
            // from recomposeToGroupEnd; that is not a run.
            var reread = false
            val function = walker.walk { frames ->
                val list = frames.toList()
                val first = list.indexOfFirst {
                    !it.className.startsWith("androidx.compose.runtime.") &&
                        !it.className.startsWith(RadioMixesRecomposeTest::class.java.name)
                }
                if (first < 0) return@walk ""
                reread = list.subList(0, first)
                    .firstOrNull { it.methodName == "enterRecomposeScope" || it.methodName == "recomposeToGroupEnd" }
                    ?.methodName == "recomposeToGroupEnd"
                list[first].let { it.className + "." + it.methodName.substringBefore('-') }
            }
            if (reread) return
            if (function.startsWith(PREFIX)) {
                val name = function.removePrefix(PREFIX)
                runs[name] = (runs[name] ?: 0) + 1
            }
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}
        fun of(name: String) = runs[name] ?: 0
        fun reset() = runs.clear()

        private companion object {
            const val PREFIX = "com.music.bitchord.ui.screens.RadioScreenKt."
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

    private val stations = UiState.Success(
        listOf(MoodGenreSection("Moods", List(STATIONS) { MoodGenre("Station $it", "b$it", null) })),
    )
    private val mixes = List(MIXES) { ShelfItem("My Mix ${it + 1}", "Artist A, Artist B and more", null, null, "VLRDmix$it") }

    @Test
    fun theCarouselComposesOnlyWhatIsOnScreen() {
        var shown by mutableStateOf(emptyList<ShelfItem>())
        var recomposer: Recomposer? = null
        compose.setContent {
            val composition = currentComposer.composition
            SideEffect { if (recomposer == null) recomposer = recomposerOf(composition) }
            BitChordTheme(darkTheme = true) {
                RadioScreen(
                    state = stations,
                    listState = rememberLazyListState(),
                    flowItem = null,
                    onFlowClick = {},
                    seeds = emptyList(),
                    onSeedClick = {},
                    onStationClick = {},
                    onStartStation = {},
                    onGenresClick = {},
                    onRetry = {},
                    refreshing = false,
                    onRefresh = {},
                    pullState = rememberPullToRefreshState(),
                    contentPadding = PaddingValues(),
                    mixes = shown,
                )
            }
        }
        compose.waitForIdle()

        val counter = Counter()
        val handles = mutableListOf<CompositionObserverHandle>()
        val reg = recomposer!!.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handles += composition.setObserver(counter)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })

        // The mixes land after the page's first frame.
        shown = mixes
        compose.waitForIdle()
        val arriveCards = counter.of("MixCard")
        val arriveTiles = counter.of("StationTile")
        val arriveFeature = counter.of("RadioFeatureCard")
        println("PROBE radio-mixes arrive: MixCard=$arriveCards StationTile=$arriveTiles RadioFeatureCard=$arriveFeature all=${counter.runs}")

        counter.reset()
        compose.onNodeWithText("My Mix 1").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val flingCards = counter.of("MixCard")
        println("PROBE radio-mixes sideways fling: MixCard=$flingCards all=${counter.runs}")

        counter.reset()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val pageCards = counter.of("MixCard")
        println("PROBE radio-mixes page fling: MixCard=$pageCards StationTile=${counter.of("StationTile")} all=${counter.runs}")

        reg.dispose()
        handles.forEach { it.dispose() }

        // 240dp cards on a 411dp screen: two on screen, a third peeking.
        assertTrue("cards composed on arrival ($arriveCards)", arriveCards in 1..3)
        assertEquals("the station wall must not recompose for the mixes", 0, arriveTiles)
        // The rows above re-run once (the page's list is rebuilt, as it is
        // when the stations land), but nothing inside them does.
        assertTrue("Flow's card runs at most once for the mixes ($arriveFeature)", arriveFeature <= 1)
        assertEquals("scrolling the page composes no card again", 0, pageCards)
        assertTrue("a sideways fling composes each new card once ($flingCards)", flingCards <= MIXES)
    }

    private companion object {
        const val STATIONS = 12
        const val MIXES = 10
    }
}
