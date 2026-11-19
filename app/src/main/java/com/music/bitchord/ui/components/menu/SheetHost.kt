package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's one [AppSheet] slot at the root, for a screen whose own place in
 * the layout isn't on top of everything — Settings, a page under the app's
 * floating bars. The screen asks; the root draws the sheet over all of it,
 * as a Material sheet's own window used to.
 */
@Stable
class SheetHostState {
    internal class Request(
        val dragFromContent: Boolean,
        val onClosed: () -> Unit,
        val content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    )

    internal var request by mutableStateOf<Request?>(null)
        private set

    internal fun open(
        dragFromContent: Boolean,
        onClosed: () -> Unit,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    ): Request = Request(dragFromContent, onClosed, content).also { request = it }

    /** Closed from the sheet — the scrim, Back, a drag, a row: tells its asker. */
    fun close() {
        val closing = request ?: return
        request = null
        closing.onClosed()
    }

    /** Closed by its asker, who already knows: [request] only, if it is still the one up. */
    internal fun withdraw(request: Request) {
        if (this.request === request) this.request = null
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/** Draws [state]'s sheet. Compose it among the root's last overlays. */
@Composable
fun SheetHostOverlay(state: SheetHostState) {
    val request = state.request
    // Kept for the exit, so the closing sheet keeps the drag rule it had.
    val last = remember { arrayOfNulls<SheetHostState.Request>(1) }
    if (request != null) last[0] = request
    AppSheet(
        item = request,
        onDismiss = state::close,
        dragFromContent = (request ?: last[0])?.dragFromContent ?: true,
    ) { shown ->
        shown.content(this) { state.close() }
    }
}

/**
 * A sheet asked of the root's [SheetHostState] for as long as this is
 * composed: put up on entering, and taken down on leaving. Closed from the
 * sheet itself, it calls [onDismiss], which is where the caller lets go of
 * whatever composed this.
 *
 * The caller's [content] is read live, so a sheet showing the caller's
 * state follows it while up.
 */
@Composable
fun HostedSheet(
    onDismiss: () -> Unit,
    dragFromContent: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val host = LocalSheetHost.current ?: return
    val currentContent by rememberUpdatedState(content)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host) {
        val request = host.open(
            dragFromContent = dragFromContent,
            onClosed = { currentOnDismiss() },
        ) { close -> currentContent(this, close) }
        onDispose { host.withdraw(request) }
    }
}
