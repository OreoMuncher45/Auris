package com.music.bitchord.ui.components

/**
 * Whether this platform can draw the full glass surface.
 *
 * The phone's backdrop blur runs on [android.graphics.RenderEffect] over a
 * [android.graphics.RenderNode], which arrived in Android 12. The desktop draws
 * the same surfaces with haze and has no such limit, but the *specular* glass
 * the phone's engine produces is Android's alone — so the answer differs, and
 * the surfaces that need the engine's rim take the flat edge instead.
 */
expect fun isGlassSupported(): Boolean
