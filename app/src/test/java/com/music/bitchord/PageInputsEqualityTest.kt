package com.music.bitchord

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.music.bitchord.ui.replay.ReplayState
import com.music.bitchord.ui.replay.rememberReplayState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [PageInputs] is rebuilt on every recomposition of the app root and handed to
 * RootPage, which is skipped only while the new value equals the last. So the
 * root-level build has to produce equal inputs when nothing changed: every
 * field either compares by value or is a holder that is the same instance for
 * the root's lifetime.
 *
 * Plain JVM: a Recomposer, a frame clock and an applier that builds nothing,
 * so the real composables run without Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageInputsEqualityTest {

    private class NoTreeApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /**
     * The root calls rememberReplayState in its body and puts the result in
     * PageInputs. Recomposing with nothing changed must give an equal state
     * (it is a new instance each time, so equality is what matters) and the
     * same setter.
     */
    @Test fun `rememberReplayState gives equal state across root recompositions`() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val running = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val tick = mutableIntStateOf(0)
        val states = mutableListOf<ReplayState>()
        val setters = mutableListOf<Any>()
        val composition = Composition(NoTreeApplier(), recomposer)
        composition.setContent {
            tick.intValue // read, so a tick recomposes this scope like a root recomposition
            val (state, setPeriod) = rememberReplayState(active = false)
            states += state
            setters += setPeriod
        }
        runCurrent()
        repeat(2) {
            Snapshot.withMutableSnapshot { tick.intValue++ }
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
        }
        assertEquals("composed once, then recomposed twice", 3, states.size)
        assertNotSame("a new value on each call", states[0], states[1])
        assertEquals(states[0], states[1])
        assertEquals(states[1], states[2])
        assertSame(setters[0], setters[1])
        assertSame(setters[1], setters[2])
        composition.dispose()
        recomposer.cancel()
        running.cancel()
    }

    /** Field types the root hands over as the same instance for its whole lifetime. */
    private val identityHolders = setOf(
        "com.music.bitchord.ui.MainViewModel",
        "kotlinx.coroutines.CoroutineScope",
        "com.music.bitchord.RootNav",
        "com.music.bitchord.RootSheets",
        "com.music.bitchord.PodcastUiState",
        "com.music.bitchord.DetailListStates",
        "com.music.bitchord.playback.PlayerReads",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.MutableState",
        "androidx.compose.foundation.lazy.LazyListState",
        "androidx.compose.foundation.lazy.grid.LazyGridState",
        "androidx.compose.material3.pulltorefresh.PullToRefreshState",
    )

    /** Interfaces whose implementations compare by value. */
    private val valueInterfaces = setOf(
        "java.util.List",
        "java.util.Map",
        "java.util.Set",
        "androidx.compose.foundation.layout.PaddingValues",
    )

    private fun isDataClass(type: Class<*>): Boolean =
        type.declaredMethods.any { it.name == "component1" } &&
            type.declaredMethods.any { it.name == "equals" && it.parameterTypes.contentEquals(arrayOf(Any::class.java)) }

    /** Why [type] keeps PageInputs' equality honest, or null if it does not. */
    private fun verdict(type: Class<*>, allowFunctions: Boolean): String? = when {
        type.isPrimitive || type == String::class.java || type.isEnum -> "value"
        type.name.startsWith("java.lang.") && Number::class.java.isAssignableFrom(type) -> "value"
        type.name in valueInterfaces -> "value (interface)"
        type.name in identityHolders -> "holder"
        allowFunctions && type.name.startsWith("kotlin.jvm.functions.Function") -> "remembered lambda"
        isDataClass(type) -> "data class"
        else -> null
    }

    private fun constructorFields(type: Class<*>): List<Class<*>> =
        type.declaredConstructors.maxBy { it.parameterCount }.parameterTypes
            .filterNot { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }

    @Test fun `every PageInputs field compares by value or is a lifetime holder`() {
        val inputs = Class.forName("com.music.bitchord.PageInputs")
        assertTrue(isDataClass(inputs))
        val bad = mutableListOf<String>()
        for (field in constructorFields(inputs)) {
            val why = verdict(field, allowFunctions = false)
            if (why == null) bad += field.name
            // The data classes it holds (PageActions, PageScrollStates, ShelfItem, ReplayState...)
            // are only as good as their own fields. Lambdas are allowed one level down:
            // PageActions holds the remembered ones, never function references (see its KDoc).
            if (why == "data class" && field.name.startsWith("com.music.bitchord.") && !Modifier.isAbstract(field.modifiers)) {
                for (inner in constructorFields(field)) {
                    if (verdict(inner, allowFunctions = true) == null) bad += "${field.simpleName}.${inner.name}"
                }
            }
        }
        assertTrue("fields without value equality or a lifetime identity: $bad", bad.isEmpty())
    }

    @Test fun `ReplayState compares by value`() {
        val a = ReplayState(com.music.bitchord.data.stats.ReplayPeriod.THIS_YEAR, null, true, "01/26")
        val b = ReplayState(com.music.bitchord.data.stats.ReplayPeriod.THIS_YEAR, null, true, "01/26")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
