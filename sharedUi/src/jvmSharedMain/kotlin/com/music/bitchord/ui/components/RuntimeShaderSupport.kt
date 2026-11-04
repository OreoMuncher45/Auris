package com.music.bitchord.ui.components

/**
 * Whether this platform can soften a page's edge with a runtime shader.
 *
 * The phone draws the foot of an album page with a blur that follows the
 * artwork behind it, which needs Android's runtime shaders and so Android 12.
 * The desktop has no such shader; the page is drawn without the softening
 * there, which is the same answer the phone gives on an older device.
 */
expect fun runtimeShaderSupported(): Boolean
