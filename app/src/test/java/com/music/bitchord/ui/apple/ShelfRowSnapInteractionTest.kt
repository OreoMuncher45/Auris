/*
 * Copyright (C) 2026 BitChord
 *
 * Where a sideways shelf comes to rest, driven by touch.
 *
 * Home's Recents used to stop wherever a fling ran out, usually partway
 * through a column, with the tracks at both edges cut off. `ShelfRow` now
 * snaps: an item always settles with its leading edge on the row's leading
 * content padding (the heading's inset), Recents turns exactly one column per
 * fling, and the last item settles against the trailing padding.
 *
 * Each test does what a finger does and checks where the row rested. The
 * geometry is a 360dp phone: a 16dp gutter, 317dp columns (0.88 of the row,
 * as `trackColumnWidth` gives) and a 12dp gap, so 15dp of the next column
 * peeks in.
 *
 * Robolectric cannot run on an aarch64 host (see ThinSliderInteractionTest),
 * so this class is named to match the exclusion in `app/build.gradle.kts` and
 * runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.components.ShelfSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// The plain Application for the same reason as ThinSliderInteractionTest: the
// manifest one loads Java 21 bytecode a JDK 17 test worker can't define.
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class ShelfRowSnapInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: LazyListState

    /** How many times each item's content has been composed, by index. */
    private val compositions = IntArray(CARDS)

    private fun setShelf(snap: ShelfSnap, itemWidth: Dp = COLUMN, count: Int = ITEMS) {
        compose.setContent {
            state = rememberLazyListState()
            Box(Modifier.width(ROW).testTag(SHELF)) {
                ShelfRow(
                    contentPadding = PaddingValues(horizontal = GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    state = state,
                    snap = snap,
                ) {
                    itemsIndexed(List(count) { it }) { index, _ ->
                        SideEffect { compositions[index]++ }
                        Box(Modifier.width(itemWidth).height(48.dp))
                    }
                }
            }
        }
    }

    private fun shelf() = compose.onNodeWithTag(SHELF)

    /** Drags right-to-left by [distance] of the row's width over [millis]. */
    private fun swipeLeft(distance: Float, millis: Long) {
        shelf().performTouchInput {
            val y = height / 2f
            val start = width * 0.9f
            swipe(Offset(start, y), Offset(start - width * distance, y), millis)
        }
        compose.waitForIdle()
    }

    /** An item's leading edge rests on the leading content padding. */
    private fun assertRestsOnLeadingInset(expectedIndex: Int? = null) {
        assertEquals(
            "the first visible item must rest on the leading inset, not partway through",
            0,
            state.firstVisibleItemScrollOffset,
        )
        expectedIndex?.let {
            assertEquals("which item rests on the inset", it, state.firstVisibleItemIndex)
        }
    }

    /** The bug: a slow drag let go partway through a column used to stay there. */
    @Test
    fun aSlowDragLetGoMidColumnSettlesOnTheNearestColumn() {
        setShelf(ShelfSnap.OneItemPerFling)
        // A quarter of the row over a second is well under fling speed, and
        // short of halfway through the first column.
        swipeLeft(distance = 0.25f, millis = 1_000)

        assertRestsOnLeadingInset(expectedIndex = 0)
    }

    /** Recents: a fast flick turns exactly one column, however hard it is. */
    @Test
    fun aFlingTurnsExactlyOneColumn() {
        setShelf(ShelfSnap.OneItemPerFling)
        swipeLeft(distance = 0.6f, millis = 60)

        assertRestsOnLeadingInset(expectedIndex = 1)
    }

    /**
     * Card shelves may travel several cards on a hard flick, but still land on
     * one. The shelf is long enough that the flick stops short of the end,
     * where the trailing inset rather than the leading one decides the rest.
     */
    @Test
    fun aFlingOnACardShelfLandsOnACard() {
        setShelf(ShelfSnap.Item, itemWidth = CARD, count = CARDS)
        swipeLeft(distance = 0.6f, millis = 60)

        assertTrue("a flick must move the shelf", state.firstVisibleItemIndex >= 1)
        assertTrue("the flick must stop short of the end", state.canScrollForward)
        assertRestsOnLeadingInset()
    }

    /** The far end rests against the trailing padding, with no gap after the last item. */
    @Test
    fun theLastColumnRestsAgainstTheTrailingInset() {
        setShelf(ShelfSnap.OneItemPerFling)
        repeat(ITEMS + 2) { swipeLeft(distance = 0.6f, millis = 60) }

        assertFalse("the row must have reached its end", state.canScrollForward)
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.last()
        assertEquals("the last item must be the last column", ITEMS - 1, last.index)
        assertEquals(
            "the last column ends on the trailing inset",
            info.viewportEndOffset - info.afterContentPadding,
            last.offset + last.size,
        )
    }

    /**
     * Cost: snapping only changes where a fling ends, so no item that stays on
     * screen through a fling may be composed again. Every item is composed at
     * most once across a whole fling to the next column.
     */
    @Test
    fun aSnapRecomposesNoItem() {
        setShelf(ShelfSnap.OneItemPerFling)
        compose.waitForIdle()
        swipeLeft(distance = 0.6f, millis = 60)

        println("ShelfRowSnap compositions per item after one fling: ${compositions.toList()}")
        compositions.forEachIndexed { index, count ->
            assertTrue("item $index composed $count times during one snap", count <= 1)
        }
    }

    private companion object {
        const val SHELF = "shelf"
        const val ITEMS = 6
        const val CARDS = 40
        val ROW = 360.dp
        val GUTTER = 16.dp
        val GAP = 12.dp
        val COLUMN = 317.dp
        val CARD = 150.dp
    }
}
