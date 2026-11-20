package com.music.bitchord.ui.components.menu

import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
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

    /** Closes [request] if it is still the one up: a row's `close` is its own menu's. */
    internal fun close(request: Request) {
        if (this.request === request) this.request = null
    }

    override fun closeIfShowing(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        if (request?.content === content) request = null
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
        // The menu caps its height to the room around the button and leaves
        // the scroll to its content; a requester hands over rows, not a
        // scrolling list, so the host brings the scroll for all of them.
        // Keyed on the request: one replacing another starts at its own top,
        // not wherever the last one was scrolled to.
        key(shown) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Bound to this request: a row that opened another menu
                // before closing its own leaves the new one up.
                val close = remember { { state.close(shown) } }
                shown.content(this, close)
            }
        }
    }
}
