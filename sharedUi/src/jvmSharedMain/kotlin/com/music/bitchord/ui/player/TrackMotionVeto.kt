package com.music.bitchord.ui.player

/** When the sleeve's track-change slide may run. */
internal object TrackMotionVeto {
    /**
     * On a [visible] player, unless the Automix reveal is drawing this change
     * ([revealHoldsCover]). Only the portrait sleeve draws the reveal: in
     * [landscape] nothing takes the slide's place, so the slide runs.
     */
    fun allows(visible: Boolean, landscape: Boolean, revealHoldsCover: () -> Boolean): Boolean =
        visible && (landscape || !revealHoldsCover())
}
