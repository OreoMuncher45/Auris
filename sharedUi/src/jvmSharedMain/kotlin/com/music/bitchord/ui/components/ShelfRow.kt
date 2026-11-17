package com.music.bitchord.ui.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * What a sideways shelf is wrapped in, on top of the row itself.
 *
 * Nothing on the phone, where a finger drags the row. A window's mouse can't
 * swipe sideways, so the desktop wraps each row in the arrows it pages with,
 * the drag that pulls it, and the horizontal wheel.
 */
interface ShelfRowChrome {
    @Composable
    fun Wrap(state: LazyListState, row: @Composable () -> Unit)
}

private object NoShelfRowChrome : ShelfRowChrome {
    @Composable
    override fun Wrap(state: LazyListState, row: @Composable () -> Unit) = row()
}

val LocalShelfRowChrome = staticCompositionLocalOf<ShelfRowChrome> { NoShelfRowChrome }

/**
 * Where a shelf comes to rest once a finger lets go of it.
 *
 * Either way it rests with an item's leading edge on the row's leading content
 * padding, the same inset as the shelf's heading, never partway through an item
 * with both ends clipped. The only difference is how far one fling may carry it.
 */
enum class ShelfSnap {
    /** A flick travels as far as its speed takes it, then settles on the nearest item. */
    Item,

    /**
     * A fling moves exactly one item forward or back, like a pager. For rows
     * whose items are nearly the row's width (Recents' columns of four tracks),
     * where skating past several at once loses the reader.
     */
    OneItemPerFling,
}

/** A sideways-scrolling shelf, in whatever the platform wraps one in — see [ShelfRowChrome]. */
@Composable
fun ShelfRow(
    contentPadding: PaddingValues,
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    snap: ShelfSnap = ShelfSnap.Item,
    content: LazyListScope.() -> Unit,
) {
    val flingBehavior = rememberShelfFlingBehavior(state, snap)
    LocalShelfRowChrome.current.Wrap(state) {
        LazyRow(
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            flingBehavior = flingBehavior,
            content = content,
        )
    }
}

/**
 * Only the fling is replaced: a drag still follows the finger exactly. On
 * release the snap reads the item positions the list has already measured,
 * once, and runs one decay-and-settle through the list's own scroll path, so a
 * snapping shelf does no more work per frame than a free one did.
 *
 * [SnapPosition.Start] is measured from the list's content padding rather than
 * its edge, which is what puts the resting item on the heading's inset. At the
 * end of the row the scroll range itself stops the last item against the
 * trailing padding.
 *
 * Remembered per row, and never read during composition, so snapping adds no
 * recomposition.
 */
@Composable
private fun rememberShelfFlingBehavior(state: LazyListState, snap: ShelfSnap): FlingBehavior {
    val provider = remember(state, snap) {
        val nearestItem = SnapLayoutInfoProvider(state, SnapPosition.Start)
        when (snap) {
            ShelfSnap.Item -> nearestItem
            ShelfSnap.OneItemPerFling -> OneItemPerFling(nearestItem)
        }
    }
    return rememberSnapFlingBehavior(provider)
}

/**
 * No approach phase: the fling goes straight to the snap step, which picks the
 * next item boundary in the fling's direction, or the closest one when the
 * release was too slow to count as a fling.
 */
private class OneItemPerFling(
    private val nearestItem: SnapLayoutInfoProvider,
) : SnapLayoutInfoProvider by nearestItem {
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f
}
