package com.music.bitchord.playback

/**
 * The way the Automix panel reaches the engine to ask for a preview.
 *
 * ## Why this exists
 *
 * The panel is shared code, drawn by both the phone and the desktop, and it
 * knows nothing about either engine. The preview, though, is the one thing in
 * the panel that has to reach into the thing that is playing: it seeks the
 * session player back to just before the handoff and lets the real blend run.
 *
 * The engine that can do that lives inside the playback service, and the
 * service is not something the shared player holds a reference to. So the
 * service leaves a handler here, and the panel asks for a preview through the
 * host.
 *
 * One handler, not a queue: a preview is a request about what is happening
 * now, and the newest one is the only one that means anything.
 */
object AutomixPreview {
    @Volatile
    private var handler: ((Boolean) -> Unit)? = null

    /**
     * Installs [handler] as the thing that answers a preview request.
     *
     * Called by the playback service whenever it builds a crossfade
     * controller, so the handler always belongs to the engine that is
     * actually playing.
     */
    fun install(handler: (Boolean) -> Unit) {
        this.handler = handler
    }

    /** Forgets the handler, for a service that is going away. */
    fun clear() {
        handler = null
    }

    /**
     * Asks for the transition to be played, or stopped.
     *
     * A request with no engine behind it is not an error: the desktop has no
     * second deck to blend with, and a panel opened there simply does nothing.
     */
    fun request(start: Boolean) {
        handler?.invoke(start)
    }
}
