package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.CompositionLocalProvider
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
        /** The sheet this one was opened from, if any: it goes when that one does. */
        val parent: Request?,
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
        parent: Request?,
        dragFromContent: Boolean,
        onClosed: () -> Unit,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    ): Request = Request(parent, dragFromContent, onClosed, content).also {
        requests += it
        shown += it
    }

    /** [request] closed from the sheet — the scrim, Back, a drag, a row: tells its asker. */
    internal fun close(request: Request) {
        if (requests.remove(request)) {
            withdrawOpenedFrom(request)
            request.onClosed()
        }
    }

    /** [request] let go by its asker, who already knows. */
    internal fun withdraw(request: Request) {
        if (requests.remove(request)) withdrawOpenedFrom(request)
    }

    /**
     * Any sheet opened from [request] goes with it, at once: its asker is
     * inside [request]'s content, which stays composed only for the exit, and
     * would otherwise keep it up — and on top — until that exit ends.
     */
    private fun withdrawOpenedFrom(request: Request) {
        requests.filter { it.parent === request }.forEach(::withdraw)
    }

    /** [request] is all the way down after going. */
    internal fun gone(request: Request) {
        if (request !in requests) shown.remove(request)
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/** The hosted sheet whose content this is, for a sheet opened from it. */
private val LocalSheetRequest = staticCompositionLocalOf<SheetHostState.Request?> { null }

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
                CompositionLocalProvider(LocalSheetRequest provides shown) {
                    shown.content(this) { state.close(shown) }
                }
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
    val parent = LocalSheetRequest.current
    DisposableEffect(host) {
        val request = host.open(
            parent = parent,
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
 * the title, the body, and the actions at the end (wrapping onto another line
 * rather than off the edge). The body is bounded to the sheet's room and
 * brings its own scroll, as an AlertDialog's body has to.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            // Bounded to the room left under the title, so a body that
            // scrolls (they all bring their own, as an AlertDialog's body
            // did) scrolls there, and the actions below stay put. No scroll
            // here: one around theirs would hand them an unbounded height.
            Box(Modifier.weight(1f, fill = false)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            }
            // At the end, and onto a second line rather than off the start
            // edge when three of them don't fit on one.
            FlowRow(
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
