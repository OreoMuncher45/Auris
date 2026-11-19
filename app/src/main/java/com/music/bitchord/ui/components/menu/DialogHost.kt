package com.music.bitchord.ui.components.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import dev.chrisbanes.haze.HazeState

/**
 * The app's one dialog slot at the root. A dialog — a confirmation, a name
 * to type — is put up by whatever screen asked, wherever that screen sits in
 * the layout; the root draws it over everything, menus and sheets included,
 * as a dialog window used to, and frosts the page with the root's own haze.
 */
@Stable
class DialogHostState {
    internal class Request(val content: @Composable (HazeState?) -> Unit)

    /**
     * Every dialog up, oldest first. Usually one; a confirmation asked for
     * from a dialog stands over it rather than replacing it.
     */
    internal val requests = mutableStateListOf<Request>()

    internal fun open(content: @Composable (HazeState?) -> Unit): Request =
        Request(content).also { requests += it }

    internal fun withdraw(request: Request) {
        requests.remove(request)
    }
}

val LocalDialogHost = staticCompositionLocalOf<DialogHostState?> { null }

/** True inside the root's dialog overlay: a dialog drawn there draws itself rather than asking again. */
internal val LocalInDialogHost = staticCompositionLocalOf { false }

/**
 * Draws [state]'s dialog, with the root's [hazeState] for its frost. Compose
 * it last of all the root's overlays.
 *
 * It comes up on the menus' spring and goes on their exit, scaled a little
 * and faded — read while drawing only — and its actions stop answering the
 * moment it is let go (they read [LocalMenuInteractive]).
 */
@Composable
fun DialogHostOverlay(state: DialogHostState, hazeState: HazeState?) {
    val requests = state.requests
    val presence = rememberMenuPresence(requests.isNotEmpty())
    // The last dialogs up outlive their askers for the frames of the exit.
    val last = remember { arrayOfNulls<List<DialogHostState.Request>>(1) }
    if (requests.isNotEmpty()) last[0] = requests.toList()
    if (!presence.isShowing) {
        last[0] = null
        return
    }
    val shown = if (requests.isNotEmpty()) requests.toList() else last[0] ?: return
    val progress = presence.animation.progress
    CompositionLocalProvider(
        LocalInDialogHost provides true,
        LocalMenuInteractive provides presence.expanded,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = p
                    val scale = 0.96f + 0.04f * p
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            // Each over the one before; only the newest answers while a newer
            // one stands over it, because the newest's scrim covers the rest.
            shown.forEach { dialog -> key(dialog) { dialog.content(hazeState) } }
        }
    }
}

/**
 * A dialog asked of the root's [DialogHostState] for as long as this is
 * composed. [content] is read live, and handed the root's haze.
 */
@Composable
fun HostedDialog(content: @Composable (HazeState?) -> Unit) {
    val host = LocalDialogHost.current ?: return
    val currentContent by rememberUpdatedState(content)
    DisposableEffect(host) {
        val request = host.open { haze -> currentContent(haze) }
        onDispose { host.withdraw(request) }
    }
}
