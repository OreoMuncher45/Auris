package com.music.bitchord.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a backdrop's track-change fade costs in composition: scopes entered on
 * the frames after a palette change, across the legacy backdrop's 1400ms
 * colour fade. A JVM proxy; device numbers come from gfxinfo.
 *
 * A measuring tool rather than a check, so it only runs when asked:
 * `AMBIENT_PROBE=1 ./gradlew :app:testProdDebugUnitTest --tests '*AmbientFadeCostProbe*' --info`,
 * and the numbers are printed as `PROBE` lines.
 */
@OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AmbientFadeCostProbe {

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
            c = c!!.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                f.isAccessible = true
                (f.get(c) as? androidx.compose.runtime.CompositionContext)
            }
        }
        error("no recomposer")
    }

    @Test
    fun legacyColourFade() {
        Assume.assumeTrue("set AMBIENT_PROBE=1 to run", System.getenv("AMBIENT_PROBE") == "1")
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        PlayerPlatform.install(AndroidPlayerHost(ctx))
        val a = MeshPalette(listOf(Color(0xFFE8603C), Color(0xFFB8336A), Color(0xFFF2B134), Color(0xFF7A2E1F)))
        val b = MeshPalette(listOf(Color(0xFF1F9EA8), Color(0xFF2A4FA8), Color(0xFF7FD6C2), Color(0xFF123A55)))
        var palette by mutableStateOf(a)
        var recomposer: Recomposer? = null
        var root: Composition? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val composition = currentComposer.composition
            SideEffect { if (recomposer == null) { recomposer = recomposerOf(composition); root = composition } }
            Box(Modifier.size(400.dp, 800.dp)) {
                MeshGradientBackground(palette = palette, trackKey = "k")
            }
        }
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        val counter = Counter()
        val handles = mutableListOf<CompositionObserverHandle>()
        root!!.setObserver(counter)?.let { handles += it }
        handles += recomposer!!.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handles += composition.setObserver(counter)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })
        val results = mutableListOf<Int>()
        val changeFrame = mutableListOf<Int>()
        repeat(3) { run ->
            counter.enters = 0
            palette = if (run % 2 == 0) b else a
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            changeFrame += counter.enters
            counter.enters = 0
            var frames = 0
            while (frames < 100) { // ~1.6 s at 16 ms
                compose.mainClock.advanceTimeByFrame()
                frames++
            }
            compose.waitForIdle()
            results += counter.enters
            compose.mainClock.advanceTimeBy(10_000)
        }
        handles.forEach { it.dispose() }
        println("PROBE legacyColourFade changeFrame=${changeFrame.joinToString()} scopeEntersAfterChangeFrame=${results.joinToString()}")
    }
}
