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
     * follows the theme. [content] is handed a `close` to call once a row has
     * done its work.
     */
    fun open(
        anchorInWindow: Rect?,
        dark: Boolean? = null,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    )

    /** Closes whatever this host has up. */
    fun close()
}

val LocalMenuHost = staticCompositionLocalOf<MenuHost?> { null }
