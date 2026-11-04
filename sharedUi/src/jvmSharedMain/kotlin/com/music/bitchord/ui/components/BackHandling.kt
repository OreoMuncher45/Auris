package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf

/**
 * Where a page's own back action is offered to the window.
 *
 * The desktop has one back key and one back stack: the window's, which every
 * page it opened is on. A page with something of its own open — a search field,
 * a selection — hands its action here, and the window runs the innermost one
 * that is registered when the key is pressed.
 *
 * The phone does not need this: its back button belongs to the activity, and
 * the activity's stack takes it in the right order by itself.
 */
val LocalAurisBackAction = compositionLocalOf<((( () -> Unit)) -> (() -> Unit))?> { null }

/**
 * What the hardware's back gesture does on this page.
 *
 * On a phone it is the system back button: the screen's own back stack takes
 * it, and a page that has something of its own open — a search field, a
 * selection — takes it first. On the desktop the same key is Escape, and the
 * window has a back stack of its own; the page offers its action to the window,
 * which runs it in place of going back a page.
 */
@Composable
expect fun AurisBackHandler(enabled: Boolean, onBack: () -> Unit)
