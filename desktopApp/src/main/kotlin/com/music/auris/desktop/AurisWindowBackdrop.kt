package com.music.auris.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The system material behind the window. */
internal enum class AurisBackdrop(
    /** What [AurisWindowsFrame.setBackdrop] takes: DWM's DWMSBT_* value, or 0 for none. */
    val nativeKind: Int,
    val label: String,
) {
    OFF(0, "Off"),
    MICA(2, "Mica"),
    ACRYLIC(3, "Acrylic"),
}

/**
 * Windows 11's Mica and Acrylic, drawn by DWM behind the window.
 *
 * The window itself is created transparent whenever the material is possible at all — transparency
 * is fixed when the window is made — so switching between the three here is live, with no restart.
 * Compose decides whether the material also reaches the app background via [appBackground].
 */
internal object AurisWindowBackdrop {

    internal const val KEY = "window_backdrop"

    /**
     * Whether this window can carry a material: Windows 11 and the native frame. Decided once, at
     * start, because it decides whether the window is created transparent.
     */
    val available: Boolean =
        AurisPlatform.isWindows && AurisPlatform.drawsOwnWindowFrame &&
            System.getProperty("os.name").orEmpty().contains("11")

    private val _selected = MutableStateFlow(
        runCatching { AurisBackdrop.valueOf(AurisPersistence().string(KEY, AurisBackdrop.MICA.name)) }
            .getOrDefault(AurisBackdrop.MICA),
    )

    /** What Settings shows and writes. */
    val selected: StateFlow<AurisBackdrop> = _selected

    private val _appBackground = MutableStateFlow(
        AurisPersistence().boolean(KEY_APP_BACKGROUND, true),
    )

    /** Whether the selected material replaces the app's solid gray page background too. */
    val appBackground: StateFlow<Boolean> = _appBackground

    private val _active = MutableStateFlow(AurisBackdrop.OFF)

    /**
     * The material actually behind the window right now. Off until the native frame is installed,
     * and off if Windows turned the request down (a Windows 11 build before 22H2 has no backdrop
     * attribute): the chrome only goes translucent over a material that is really there.
     */
    val active: StateFlow<AurisBackdrop> = _active

    fun set(value: AurisBackdrop) {
        AurisPersistence().saveString(KEY, value.name)
        _selected.value = value
        apply()
    }

    fun setAppBackground(value: Boolean) {
        AurisPersistence().saveBoolean(KEY_APP_BACKGROUND, value)
        _appBackground.value = value
    }

    /** Asks DWM for the selected material; called once the native frame is in place, and on every change. */
    fun apply() {
        if (!available) return
        val wanted = _selected.value
        val applied = AurisWindowsFrame.setBackdrop(wanted.nativeKind)
        _active.value = if (applied) wanted else AurisBackdrop.OFF
        if (!applied && wanted != AurisBackdrop.OFF) {
            AurisTrackLog.log("window backdrop: Windows declined ${wanted.label}")
        }
    }

    internal const val KEY_APP_BACKGROUND = "window_backdrop_app_background"
}
