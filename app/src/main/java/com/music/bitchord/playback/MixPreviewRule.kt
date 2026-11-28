package com.music.bitchord.playback

/**
 * Whether the mix popup's blend preview is cleared rather than published for
 * the pair being planned: when the loop mix takes the pair (it shows the loop
 * once armed), and when the planner blocked it (no blend will run, so there
 * is no overlap or fader curve to draw). An allowed fallback fade still shows.
 */
internal object MixPreviewRule {
    fun clears(loopChosen: Boolean, planBlocked: Boolean): Boolean = loopChosen || planBlocked
}
