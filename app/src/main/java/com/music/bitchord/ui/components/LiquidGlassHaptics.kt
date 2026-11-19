package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/**
 * A short haptic buzz fired on every liquid-glass interaction — nav-bar tabs,
 * toggles, sliders, glass menu rows. Gated by
 * [AppSettings.liquidGlassHaptics] so the listener can turn it off in Settings;
 * when off the returned lambda is a no-op.
 */
@Composable
fun rememberLiquidGlassHaptics(): () -> Unit {
    val enabled by AppSettings.liquidGlassHaptics.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    return remember(enabled, haptic) {
        {
            if (enabled) {
                runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
        }
    }
}
