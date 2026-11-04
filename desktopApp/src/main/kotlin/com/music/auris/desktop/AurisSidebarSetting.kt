package com.music.auris.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the window keeps its sidebar.
 *
 * Off, the page takes the whole width and the window is the phone's own shape:
 * the shared screens from `:sharedUi`, drawn edge to edge, with the floating
 * navigation along the bottom instead of a column down the side. It is the
 * setting for a screen that is more a phone than a computer — a television, a
 * projector, a touchscreen on a wall.
 */
internal object AurisSidebarSetting {

    internal const val KEY = "window_sidebar"

    /**
     * The command line can overrule the stored choice, for a screen that is never touched by
     * hand — a television, a kiosk, a build that is always full-bleed.
     */
    private fun initial(): Boolean {
        val forced = System.getProperty("auris.sidebar")?.trim()?.lowercase()
        return when (forced) {
            "off", "false", "no", "0" -> false
            "on", "true", "yes", "1" -> true
            else -> AurisPersistence().boolean(KEY, true)
        }
    }

    private val _enabled = MutableStateFlow(initial())

    /** What Settings shows and writes. */
    val enabled: StateFlow<Boolean> = _enabled

    fun set(value: Boolean) {
        AurisPersistence().saveBoolean(KEY, value)
        _enabled.value = value
    }
}
