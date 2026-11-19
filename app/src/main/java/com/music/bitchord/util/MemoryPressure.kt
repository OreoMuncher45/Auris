package com.music.bitchord.util

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Passes the system's `onTrimMemory` on to the app's own in-memory caches.
 *
 * Coil already handles this for its own memory cache: it clears the cache at
 * [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] and halves it when memory is
 * running low. The app's own caches had no such hook, so a process that the
 * system was about to kill kept its analysis results, widget composites and
 * search pages until it died. Those go first when the system picks a cached
 * process to reclaim.
 *
 * Listeners say for themselves what they let go of, because only they know
 * what a later rebuild costs. Each one is called on the main thread. A
 * listener tied to something shorter-lived than the process (a ViewModel, a
 * service) has to [unregister] when that thing ends, or this object becomes
 * what keeps it alive.
 */
object MemoryPressure {

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /**
     * Calls [listener] when the app is running but memory is short
     * (`RUNNING_LOW`, `RUNNING_CRITICAL`, `onLowMemory`), or when this process
     * is cached and can be killed (`BACKGROUND` and above). It should drop
     * whatever is cheap to rebuild.
     */
    fun register(listener: () -> Unit) {
        listeners += listener
    }

    fun unregister(listener: () -> Unit) {
        listeners -= listener
    }

    /** Call once from [Application.onCreate]. */
    fun install(app: Application) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (shouldTrim(level)) dispatch()
            }

            override fun onLowMemory() = dispatch()

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
    }

    /**
     * `UI_HIDDEN` only means the user went to another app. Playback, widgets
     * and the notification are often still running then, and the caches are
     * serving them, so that level and `RUNNING_MODERATE` change nothing.
     */
    @Suppress("DEPRECATION") // RUNNING_* levels are still sent below API 34.
    private fun shouldTrim(level: Int): Boolean = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> true
        level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
        else -> level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    }

    private fun dispatch() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
