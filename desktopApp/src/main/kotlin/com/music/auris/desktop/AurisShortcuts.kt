package com.music.auris.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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
class AurisTypingState {
    var focused by mutableStateOf(false)
        internal set
}

val LocalAurisTypingState = staticCompositionLocalOf { AurisTypingState() }

/**
 * Marks a text field: while it has the focus, the app's own keys are left to it.
 *
 * Applied to the field itself rather than watched from outside, because Compose gives no way to
 * ask what kind of thing holds the focus — the text input service's own state is internal.
 */
@Composable
internal fun Modifier.aurisTextInput(): Modifier {
    val typing = LocalAurisTypingState.current
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
 *
 * The tab keys follow what a window is expected to do with them rather than what a phone does:
 * ctrl+T opens a tab, ctrl+W closes one, ctrl+Tab walks through them and ctrl+1…9 goes straight
 * to one. The pages themselves move to alt+1…9, where a phone's own digits are free.
 */
@Composable
internal fun Modifier.desktopShortcuts(
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** By how much the volume moves; negative is quieter. */
    onVolumeBy: (Float) -> Unit,
    onFocusSearch: () -> Unit,
    onDestination: (AurisDestination) -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
    onNewTab: () -> Unit = {},
    onCloseTab: () -> Unit = {},
    /** The tab at a position, counting from one. */
    onSwitchTab: (Int) -> Unit = {},
    /** The next tab (one) or the one before it (minus one). */
    onCycleTab: (Int) -> Unit = {},
    onShowShortcuts: () -> Unit = {},
    onToggleFullScreen: () -> Unit = {},
): Modifier {
    val typing = LocalAurisTypingState.current
    return this
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val command = event.isCtrlPressed || event.isMetaPressed
            when {
                event.key == Key.Spacebar && !typing.focused -> {
                    onPlayPause()
                    true
                }
                command && event.key == Key.F -> {
                    onFocusSearch()
                    true
                }
                command && event.key == Key.L -> {
                    onFocusSearch()
                    true
                }
                command && event.key == Key.Comma -> {
                    onOpenSettings()
                    true
                }
                command && event.key == Key.Q -> {
                    onQuit()
                    true
                }
                command && event.key == Key.T -> {
                    onNewTab()
                    true
                }
                command && event.key == Key.W -> {
                    onCloseTab()
                    true
                }
                command && event.key == Key.Slash && event.isShiftPressed -> {
                    onShowShortcuts()
                    true
                }
                command && event.key == Key.Tab -> {
                    onCycleTab(if (event.isShiftPressed) -1 else 1)
                    true
                }
                event.key == Key.F11 -> {
                    onToggleFullScreen()
                    true
                }
                else -> false
            }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            // While a field has the focus, the control keys belong to the field.
            if (typing.focused) return@onKeyEvent false
            val command = event.isCtrlPressed || event.isMetaPressed
            if (command) {
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
                        onSwitchTab(0)
                        true
                    }
                    Key.Two -> {
                        onSwitchTab(1)
                        true
                    }
                    Key.Three -> {
                        onSwitchTab(2)
                        true
                    }
                    Key.Four -> {
                        onSwitchTab(3)
                        true
                    }
                    Key.Five -> {
                        onSwitchTab(4)
                        true
                    }
                    Key.Six -> {
                        onSwitchTab(5)
                        true
                    }
                    Key.Seven -> {
                        onSwitchTab(6)
                        true
                    }
                    Key.Eight -> {
                        onSwitchTab(7)
                        true
                    }
                    Key.Nine -> {
                        onSwitchTab(8)
                        true
                    }
                    else -> false
                }
            } else if (event.isAltPressed) {
                val page = when (event.key) {
                    Key.One -> 0
                    Key.Two -> 1
                    Key.Three -> 2
                    Key.Four -> 3
                    Key.Five -> 4
                    Key.Six -> 5
                    Key.Seven -> 6
                    Key.Eight -> 7
                    Key.Nine -> 8
                    else -> -1
                }
                if (page >= 0) {
                    onDestination(AurisDestination.entries[page.coerceAtMost(AurisDestination.entries.lastIndex)])
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
}

/** How much one press of ctrl+up or ctrl+down moves the volume. */
private const val VOLUME_STEP = 0.05f
