package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced

/**
 * The last menu button tapped, so the menu it opens can hang off it.
 *
 * The counterpart to [LongPressOrigin] for a *tap*: a ⋮ opens its menu through
 * the same callback a hold does, and the host asks [consume] where the button
 * was. A single slot for the same reasons, with the same freshness guard, so a
 * tap whose host never asked can't be claimed later by an unrelated opening.
 */
object MenuOrigin {
    private var bounds: Rect? = null
    private var recordedAt = 0L

    /** Records [button]'s bounds in window coordinates, just before its menu is opened. */
    fun record(button: Rect) {
        bounds = button
        recordedAt = System.nanoTime()
    }

    /** The button this opening came from, in window coordinates, or null when it didn't come from one. */
    fun consume(): Rect? {
        val button = bounds?.takeIf { System.nanoTime() - recordedAt < FRESH_NANOS }
        bounds = null
        return button
    }

    private const val FRESH_NANOS = 500_000_000L
}

/**
 * Where one menu button sits, kept by reference so it costs nothing until the
 * button is tapped. See [menuOriginOf] and [MenuButtonOrigin.record].
 */
@Stable
class MenuButtonOrigin internal constructor() {
    internal var coordinates: LayoutCoordinates? = null

    /** Tells [MenuOrigin] where this button is. Call it from the click, before opening the menu. */
    fun record() {
        coordinates?.takeIf { it.isAttached }?.let { MenuOrigin.record(it.boundsInWindow()) }
    }
}

@Composable
fun rememberMenuButtonOrigin(): MenuButtonOrigin = remember { MenuButtonOrigin() }

/** Keeps [origin] pointed at this element's bounds. Place it where the visible button is. */
fun Modifier.menuOriginOf(origin: MenuButtonOrigin): Modifier = onPlaced { origin.coordinates = it }
