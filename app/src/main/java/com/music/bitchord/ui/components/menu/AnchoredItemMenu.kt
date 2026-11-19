package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect

/**
 * An [AnchoredMenu] about one item — a track, a release, an episode — opened
 * off the button that was tapped, and kept up through its exit after the
 * caller has already let go of the item.
 *
 * [item] is non-null while the menu is wanted. [anchorInWindow] is the button
 * in window coordinates, or null to open at the top end; it is read while
 * the menu is placed, never while it is composed.
 *
 * Each opening composes [content] afresh, so state a previous opening left
 * behind — a sleep-timer page, a delete waiting to be confirmed — never
 * carries over to the next.
 */
@Composable
internal fun <T : Any> AnchoredItemMenu(
    item: T?,
    anchorInWindow: () -> Rect?,
    onDismiss: () -> Unit,
    /** Forces dark (true) or light (false) colours; null follows the theme. */
    dark: Boolean? = null,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val presence = rememberMenuPresence(item != null)
    // Plain fields, written while composing: they only have to outlive the
    // caller's item for the frames of the exit, and nothing reads them as state.
    val shown = remember { ShownItem<T>() }
    val opening = remember(item != null) { Any() }
    if (item != null) {
        shown.item = item
        shown.opening = opening
    }
    if (!presence.isShowing) {
        shown.item = null
        return
    }
    val current = shown.item ?: return
    // Where this host sits in the window. Read only while the menu is placed,
    // so it relayouts the menu alone, and only when the host is not at the
    // window's origin — once, on the menu's first, still transparent, frame.
    val hostOrigin = remember { mutableStateOf(Offset.Zero) }
    AnchoredMenu(
        presence = presence,
        anchor = { anchorInWindow()?.translate(-hostOrigin.value)?.roundToIntRect() },
        onDismiss = onDismiss,
        modifier = Modifier.onPlaced { hostOrigin.value = it.positionInWindow() },
        hostOffsetInWindow = { hostOrigin.value.round() },
        // Over a page of rows rather than the artwork: frosted, so the rows
        // behind don't read through the menu's own.
        frost = MenuTokens.PageFrost,
        dark = dark,
    ) {
        key(shown.opening) { content(current) }
    }
}

private class ShownItem<T : Any> {
    var item: T? = null
    var opening: Any? = null
}
