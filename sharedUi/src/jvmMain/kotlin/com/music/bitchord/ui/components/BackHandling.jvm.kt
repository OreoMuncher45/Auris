package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * The desktop's back key belongs to the window, so the page's own action is
 * offered to it rather than claimed outright.
 */
@Composable
actual fun AurisBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val register = LocalAurisBackAction.current
    DisposableEffect(enabled, onBack, register) {
        if (!enabled || register == null) {
            onDispose { }
        } else {
            val dispose = register(onBack)
            onDispose { dispose() }
        }
    }
}
