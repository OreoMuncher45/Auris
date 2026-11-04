package com.music.bitchord.ui.components

import android.os.Build

/** Android 12 brought the runtime shaders the phone's page edges are drawn with. */
actual fun runtimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
