package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** The phone's back button is the system's, and the activity's back stack takes it. */
@Composable
actual fun AurisBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
