package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.theme.BitChordTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A podcast's episode list draws a [MediaDownloadButton] per episode. While one
 * episode downloads, its progress is published many times a second, and when
 * it lands it is saved. Only that episode's button has anything to redraw.
 *
 * Counts the runs of [MediaDownloadButton]'s body across [BUTTONS] buttons
 * while one of them downloads: [TICKS] progress updates, then the save.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MediaDownloadButtonRecomposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> downloadsFlow(name: String): MutableStateFlow<T> =
        Downloads::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(Downloads) as MutableStateFlow<T>

    private val activeFlow get() = downloadsFlow<Map<String, DownloadState>>("_active")
    private val savedFlow get() = downloadsFlow<Map<String, String>>("_saved")

    @After fun clear() {
        activeFlow.value = emptyMap()
        savedFlow.value = emptyMap()
    }

    /** Runs of [MediaDownloadButton]'s body; it opens its own restart group. */
    private class Counter : CompositionObserver {
        var runs = 0
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            val function = walker.walk { frames ->
                frames.filter {
                    !it.className.startsWith("androidx.compose.runtime.") &&
                        !it.className.startsWith(MediaDownloadButtonRecomposeTest::class.java.name)
                }
                    .findFirst().map { it.className + "." + it.methodName.substringBefore('-') }.orElse("")
            }
            if (function == "com.music.bitchord.ui.components.MediaDownloadButtonKt.MediaDownloadButton") runs++
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}

        private companion object {
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

    @Test fun `one episode downloading recomposes only its own button`() {
        var recomposer: Recomposer? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val composition = currentComposer.composition
            SideEffect { if (recomposer == null) recomposer = recomposerOf(composition) }
            BitChordTheme(darkTheme = true) {
                Column {
                    repeat(BUTTONS) { i -> MediaDownloadButton(videoId = "e$i", onDownload = {}) }
                }
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()

        val counter = Counter()
        val handles = mutableListOf<CompositionObserverHandle>()
        val reg = recomposer!!.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handles += composition.setObserver(counter)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })

        activeFlow.value = mapOf("e0" to DownloadState.Queued)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        for (t in 1..TICKS) {
            activeFlow.value = mapOf("e0" to DownloadState.Running(t / TICKS.toFloat()))
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        savedFlow.value = mapOf("e0" to "file:///e0.m4a")
        activeFlow.value = emptyMap()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()

        val publishes = TICKS + 2
        println("PROBE media-download-button buttons=$BUTTONS publishes=$publishes runs=${counter.runs}")
        reg.dispose()
        handles.forEach { it.dispose() }

        assertTrue("the downloading button sees its progress (${counter.runs})", counter.runs >= TICKS)
        assertTrue(
            "buttons recompose for another episode's download (${counter.runs} runs for $publishes publishes)",
            counter.runs <= publishes,
        )
    }

    private companion object {
        const val BUTTONS = 20
        const val TICKS = 30
    }
}
