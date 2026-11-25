package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * The app's anchored menu, for a screen that can't reach it directly.
 *
 * A screen shared with desktop can't call the Android menu: its glass, its
 * back handling and its motion are Android's. It asks the host instead —
 * "these rows, hung off this button" — and the host draws them in the one
 * menu every other screen uses, over everything, the player included.
 *
 * Null where no host is provided (the desktop build): the screen keeps the
 * menu it had.
 */
@Stable
interface MenuHost {
    /**
     * Opens [content] as an anchored menu off [anchorInWindow], replacing any
     * menu this host already has up. [anchorInWindow] null opens at the top
     * end. [dark] forces the menu's dark or light colours for a surface that
     * doesn't follow the app's theme — the player is always dark — and null
     * follows the theme. [centered] centres the panel in its safe band rather
     * than aligning its end with the anchor. [fromPlayer] makes the Android
     * host draw and sample glass inside the player's own backdrop scope;
     * otherwise the root page's backdrop is used. [content] is handed a
     * `close` to call once a row has done its work.
     */
    fun open(
        anchorInWindow: Rect?,
        dark: Boolean? = null,
        centered: Boolean = false,
        fromPlayer: Boolean = false,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    )

    /** Closes whatever this host has up. */
    fun close()

    /**
     * Closes the menu only if it is still the one opened with [content] —
     * for a surface going away, so it takes its own menu with it and leaves
     * a newer one opened elsewhere alone.
     */
    fun closeIfShowing(content: @Composable ColumnScope.(close: () -> Unit) -> Unit)
}

val LocalMenuHost = staticCompositionLocalOf<MenuHost?> { null }

/**
 * The app's confirmation dialog, for a screen shared with desktop: the same
 * frosted card every other confirmation in the app is, drawn by the app. Null
 * where none is provided (desktop), and the screen keeps its own dialog.
 */
interface ConfirmationPresenter {
    @Composable
    fun Confirm(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    )
}

val LocalConfirmation = staticCompositionLocalOf<ConfirmationPresenter?> { null }
