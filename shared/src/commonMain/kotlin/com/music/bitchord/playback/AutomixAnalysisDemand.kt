package com.music.bitchord.playback

/**
 * The way the Automix panel reaches the engine to ask for the analysis it is
 * waiting on.
 *
 * ## Why this exists
 *
 * The panel is shared code, drawn by both the phone and the desktop, and it
 * knows nothing about either engine. But the bytes it is waiting for live
 * inside the playback service, behind a cache that only the service can reach.
 *
 * The panel's own request is not "please analyse this" — the engine already
 * does that, in the background, on its own schedule. It is "*now*, please":
 * the listener has opened the panel, both lanes are on screen, and they are
 * looking at them. So the panel asks for the bytes to be on their way the
 * moment it opens, and the engine answers with the one thing it can do about
 * it — fetch them, again if need be, and measure whatever has arrived.
 *
 * One handler, not a queue, for the same reason [AutomixPreview] has one: a
 * request about what is happening now is answered by the newest one, and
 * every earlier one is the same request.
 */
object AutomixAnalysisDemand {
    @Volatile
    private var handler: (() -> Unit)? = null

    /**
     * Installs [handler] as the thing that answers an analysis demand.
     *
     * Called by the playback service whenever it builds a crossfade
     * controller, so the handler always belongs to the engine that is
     * actually playing.
     */
    fun install(handler: () -> Unit) {
        this.handler = handler
    }

    /** Forgets the handler, for a service that is going away. */
    fun clear() {
        handler = null
    }

    /**
     * Asks for the pair's analysis to be fetched and measured now.
     *
     * A request with no engine behind it is not an error: a platform that
     * has no analysis engine simply does nothing.
     */
    fun request() {
        handler?.invoke()
    }
}
