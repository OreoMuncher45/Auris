package com.music.bitchord.ui.components

import android.os.Build

/**
 * The phone can: the backdrop engine draws through [android.graphics.RenderEffect],
 * which arrived in Android 12.
 */
actual fun isGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
