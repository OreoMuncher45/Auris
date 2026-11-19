package com.music.bitchord.ui.components.menu

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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

    /**
     * Every sheet asked for, oldest first. Usually one; a sheet opened from
     * a sheet — a colour picker from the player colours — stands over it,
     * and closing it shows the one beneath again.
     */
    internal val requests = mutableStateListOf<Request>()

    /** Every sheet on screen: the ones asked for, and any still on its way down. */
    internal val shown = mutableStateListOf<Request>()

    internal fun open(
        dragFromContent: Boolean,
        onClosed: () -> Unit,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    ): Request = Request(dragFromContent, onClosed, content).also {
        requests += it
        shown += it
    }

    /** [request] closed from the sheet — the scrim, Back, a drag, a row: tells its asker. */
    internal fun close(request: Request) {
        if (requests.remove(request)) request.onClosed()
    }

    /** [request] let go by its asker, who already knows. */
    internal fun withdraw(request: Request) {
        requests.remove(request)
    }

    /** [request] is all the way down after going. */
    internal fun gone(request: Request) {
        if (request !in requests) shown.remove(request)
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/** Draws [state]'s sheets, each over the one before. Compose it among the root's last overlays. */
@Composable
fun SheetHostOverlay(state: SheetHostState) {
    state.shown.toList().forEach { request ->
        key(request) {
            AppSheet(
                item = request.takeIf { it in state.requests },
                onDismiss = { state.close(request) },
                dragFromContent = request.dragFromContent,
                // Only the top sheet keeps the focus in.
                trapFocus = { state.requests.lastOrNull() === request },
                onGone = { state.gone(request) },
            ) { shown ->
                shown.content(this) { state.close(shown) }
            }
        }
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

/**
 * What was a Material AlertDialog carrying a screen's worth — a list, a form,
 * a store to browse — laid out in the app's sheet instead: the approved plan
 * keeps dialogs for a confirmation or a name, and anything bigger is a sheet.
 * Takes AlertDialog's own slots, so a dialog becomes one by its name alone:
 * the title, the body (which scrolls inside the sheet), and the actions at
 * the end of the last line.
 */
@Composable
fun HostedSheetDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    HostedSheet(onDismiss = onDismissRequest) { _ ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MenuTokens.SheetRowPaddingH)
                .padding(bottom = 12.dp),
        ) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f, fill = false)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
