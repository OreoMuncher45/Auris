package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * The Android side of [MenuHost]: the one request that is up, if any. Kept
 * with the root's other overlay state and drawn by [MenuHostOverlay].
 */
@Stable
class MenuHostState : MenuHost {
    internal class Request(
        val anchorInWindow: Rect?,
        val dark: Boolean?,
        val content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    )

    internal var request by mutableStateOf<Request?>(null)
        private set

    override fun open(
        anchorInWindow: Rect?,
        dark: Boolean?,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    ) {
        request = Request(anchorInWindow, dark, content)
    }

    override fun close() {
        request = null
    }
}

/**
 * Draws [state]'s menu: the shared anchored menu, over everything composed
 * before it. Compose it last among the root's overlays.
 */
@Composable
fun MenuHostOverlay(state: MenuHostState) {
    val request = state.request
    // The last request outlives the host's own for the frames of the exit,
    // so the closing menu stays where it hung and keeps its colours.
    val last = remember { arrayOfNulls<MenuHostState.Request>(1) }
    if (request != null) last[0] = request
    AnchoredItemMenu(
        item = request,
        anchorInWindow = { last[0]?.anchorInWindow },
        onDismiss = state::close,
        dark = (request ?: last[0])?.dark,
    ) { shown ->
        shown.content(this, state::close)
    }
}
