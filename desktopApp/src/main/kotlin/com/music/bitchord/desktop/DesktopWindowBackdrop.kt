package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The system material behind the window's own chrome — the sidebar, the top bar, the title bar. */
internal enum class DesktopBackdrop(
    /** What [DesktopWindowsFrame.setBackdrop] takes: DWM's DWMSBT_* value, or 0 for none. */
    val nativeKind: Int,
    val label: String,
) {
    OFF(0, "Off"),
    MICA(2, "Mica"),
    ACRYLIC(3, "Acrylic"),
}

/**
 * Windows 11's Mica and Acrylic, drawn by DWM behind the sidebar and top bar.
 *
 * Only the chrome lets it through: the page, the lyrics and queue column and the player stay
 * opaque, and are painted over it. The window itself is created transparent whenever the material
 * is possible at all — transparency is fixed when the window is made — so switching between the
 * three here is live, with no restart.
 */
internal object DesktopWindowBackdrop {

    internal const val KEY = "window_backdrop"

    /**
     * Whether this window can carry a material: Windows 11 and the native frame. Decided once, at
     * start, because it decides whether the window is created transparent.
     */
    val available: Boolean =
        DesktopPlatform.isWindows && DesktopPlatform.drawsOwnWindowFrame &&
            System.getProperty("os.name").orEmpty().contains("11")

    private val _selected = MutableStateFlow(
        runCatching { DesktopBackdrop.valueOf(DesktopPersistence().string(KEY, DesktopBackdrop.MICA.name)) }
            .getOrDefault(DesktopBackdrop.MICA),
    )

    /** What Settings shows and writes. */
    val selected: StateFlow<DesktopBackdrop> = _selected

    private val _active = MutableStateFlow(DesktopBackdrop.OFF)

    /**
     * The material actually behind the window right now. Off until the native frame is installed,
     * and off if Windows turned the request down (a Windows 11 build before 22H2 has no backdrop
     * attribute): the chrome only goes translucent over a material that is really there.
     */
    val active: StateFlow<DesktopBackdrop> = _active

    fun set(value: DesktopBackdrop) {
        DesktopPersistence().saveString(KEY, value.name)
        _selected.value = value
        apply()
    }

    /** Asks DWM for the selected material; called once the native frame is in place, and on every change. */
    fun apply() {
        if (!available) return
        val wanted = _selected.value
        val applied = DesktopWindowsFrame.setBackdrop(wanted.nativeKind)
        _active.value = if (applied) wanted else DesktopBackdrop.OFF
        if (!applied && wanted != DesktopBackdrop.OFF) {
            DesktopTrackLog.log("window backdrop: Windows declined ${wanted.label}")
        }
    }
}
