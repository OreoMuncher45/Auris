package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Whether one of the app's own text fields holds the focus.
 *
 * The app's keys would otherwise fight with whatever is being typed: a space in the search box
 * would start and stop the music instead of being a space, and ctrl+left would change the track
 * instead of moving the caret a word. A field that has the focus says so, and the keys wait.
 */
class DesktopTypingState {
    var focused by mutableStateOf(false)
        internal set
}

val LocalDesktopTypingState = staticCompositionLocalOf { DesktopTypingState() }

/**
 * Marks a text field: while it has the focus, the app's own keys are left to it.
 *
 * Applied to the field itself rather than watched from outside, because Compose gives no way to
 * ask what kind of thing holds the focus — the text input service's own state is internal.
 */
@Composable
internal fun Modifier.desktopTextInput(): Modifier {
    val typing = LocalDesktopTypingState.current
    return this.onFocusChanged { typing.focused = it.isFocused }
}

/**
 * The keyboard the desktop app answers to.
 *
 * A desktop window is driven by the keyboard far more than a phone is, and the keys people
 * already have in their fingers are the ones that should work: the space bar for the transport,
 * the control keys for the track and the volume, ctrl+F for the search, ctrl+, for the settings.
 *
 * Two phases, because the difference matters. The preview phase runs before the focused control
 * sees the key, and the space bar is handled there: a button that holds the focus would
 * otherwise swallow the space and press itself again. The bubble phase runs after, and the
 * control keys are handled there: a text field moves its caret by word with ctrl+left and
 * ctrl+right, and that is the caret's business, not the track's.
 */
@Composable
internal fun Modifier.desktopShortcuts(
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** By how much the volume moves; negative is quieter. */
    onVolumeBy: (Float) -> Unit,
    onFocusSearch: () -> Unit,
    onDestination: (DesktopDestination) -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
): Modifier {
    val typing = LocalDesktopTypingState.current
    return this
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.Spacebar && !typing.focused -> {
                    onPlayPause()
                    true
                }
                event.isCtrlPressed && event.key == Key.F -> {
                    onFocusSearch()
                    true
                }
                event.isCtrlPressed && event.key == Key.Comma -> {
                    onOpenSettings()
                    true
                }
                event.isCtrlPressed && event.key == Key.Q -> {
                    onQuit()
                    true
                }
                else -> false
            }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            // While a field has the focus, the control keys belong to the field.
            if (!event.isCtrlPressed || typing.focused) return@onKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> {
                    onPrevious()
                    true
                }
                Key.DirectionRight -> {
                    onNext()
                    true
                }
                Key.DirectionUp -> {
                    onVolumeBy(VOLUME_STEP)
                    true
                }
                Key.DirectionDown -> {
                    onVolumeBy(-VOLUME_STEP)
                    true
                }
                Key.One -> {
                    onDestination(DesktopDestination.LISTEN_NOW)
                    true
                }
                Key.Two -> {
                    onDestination(DesktopDestination.EXPLORE)
                    true
                }
                Key.Three -> {
                    onDestination(DesktopDestination.LIBRARY)
                    true
                }
                Key.Four -> {
                    onDestination(DesktopDestination.SEARCH)
                    true
                }
                Key.Five -> {
                    onDestination(DesktopDestination.DOWNLOADS)
                    true
                }
                else -> false
            }
        }
}

/** How much one press of ctrl+up or ctrl+down moves the volume. */
private const val VOLUME_STEP = 0.05f
