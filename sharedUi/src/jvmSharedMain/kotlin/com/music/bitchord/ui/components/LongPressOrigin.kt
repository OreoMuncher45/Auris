package com.music.bitchord.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced

/**
 * Where on screen the last long-press landed, for a menu that wants to lift the
 * held row out of its list rather than slide a sheet up from the bottom.
 *
 * Only a *hold* records anything. The ⋮ on the same row opens the same menu
 * through the same callback, and that path has to keep opening the sheet — so
 * the menu's host asks [consume] whether this particular opening came from a
 * finger held on a row, and gets null for everything else.
 *
 * A single slot rather than state threaded through every list: only one row can
 * be held at a time, and the host reads it in the same call stack the hold
 * fired in. The timestamp is the guard against a hold whose host never asked —
 * a collection's menu, or the desktop build — being claimed later by an
 * unrelated tap on a ⋮.
 */
object LongPressOrigin {
    private var bounds: Rect? = null
    private var recordedAt = 0L

    fun record(windowBounds: Rect) {
        bounds = windowBounds
        recordedAt = System.nanoTime()
    }

    /** The held row's bounds in window coordinates, or null when this opening wasn't a hold. */
    fun consume(): Rect? {
        val held = bounds?.takeIf { System.nanoTime() - recordedAt < FRESH_NANOS }
        bounds = null
        return held
    }

    private const val FRESH_NANOS = 500_000_000L
}

/**
 * [combinedClickable] that also tells [LongPressOrigin] where the hold landed.
 *
 * The coordinates are kept by reference and only measured when the hold fires,
 * so a row scrolling past pays nothing for being able to be held.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressMenuClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    enabled: Boolean = true,
): Modifier {
    val holder = remember { CoordinatesHolder() }
    val longClick = onLongClick?.let { held ->
        {
            holder.coordinates?.takeIf { it.isAttached }?.let { LongPressOrigin.record(it.boundsInWindow()) }
            held()
        }
    }
    return this
        .onPlaced { holder.coordinates = it }
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = longClick)
}

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}
