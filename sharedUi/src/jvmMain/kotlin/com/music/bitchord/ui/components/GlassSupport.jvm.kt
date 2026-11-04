package com.music.bitchord.ui.components

/**
 * The desktop cannot: the specular glass is Android's runtime shader, and the
 * desktop's own surfaces are drawn with haze instead.
 */
actual fun isGlassSupported(): Boolean = false
