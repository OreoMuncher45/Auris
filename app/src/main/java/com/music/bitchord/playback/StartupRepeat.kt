package com.music.bitchord.playback

/**
 * Recognises a collector's first value when it only repeats what was already
 * published at startup.
 *
 * A StateFlow collector gets the flow's current value first. In
 * [PlaybackService] that value lands just after onCreate has drawn the widget
 * and the notification's buttons from the same inputs, so drawing them again
 * would be a repeat. [skip] answers true at most once: for the first value,
 * and only when the inputs are still exactly the ones [published] reports.
 * Any later value, or a first value whose inputs changed in between, is
 * drawn as usual.
 *
 * One per collector, used from one thread.
 */
internal class StartupRepeat<T : Any>(private val published: () -> T?) {
    private var first = true

    fun skip(current: T): Boolean {
        val repeat = first && current == published()
        first = false
        return repeat
    }
}
