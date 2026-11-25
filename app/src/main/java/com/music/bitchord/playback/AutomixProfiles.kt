package com.music.bitchord.playback

import com.music.bitchord.playback.smart.AutomixOverride
import com.music.bitchord.playback.smart.AutomixProfileStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The listener's own choices for each track's transition.
 *
 * Automix works out a transition from the two recordings. This is where the
 * listener's answer to the same question lives: a blend they want on every
 * track, a size they want on this one, a filter shape they always prefer —
 * kept with the song, so it means the same thing every time it plays.
 *
 * The store is disk-backed, because a choice that forgets itself when the app
 * is closed is not a choice. What is in memory is a cache of what is on disk,
 * so the playback thread can ask for a track's profile without touching the
 * filesystem on every tick.
 */
object AutomixProfiles {

    private var store: AutomixProfileStore? = null

    /** What is known of each track's profile this session. */
    private val known = ConcurrentHashMap<String, AutomixOverride>()

    /** Called once, when the app has a place to keep files. */
    fun init(filesDir: File) {
        store = AutomixProfileStore { filesDir }
    }

    /**
     * The choice stored for [trackId], or the planner's own behaviour.
     *
     * Read off disk once per track per session: the file is small, but the
     * playback thread asks for this on every tick of every transition.
     */
    fun profileFor(trackId: String): AutomixOverride {
        if (trackId.isBlank()) return AutomixOverride.NONE
        return known.getOrPut(trackId) { store?.load(trackId) ?: AutomixOverride.NONE }
    }

    /** Stores [override] for [trackId]. */
    fun set(trackId: String, override: AutomixOverride) {
        if (trackId.isBlank()) return
        known[trackId] = override
        // The write goes to a thread of its own: this is called from the
        // panel, which is the main thread, and a file write there is a stall
        // the listener can see. The choice is already in memory by the time
        // this returns, so the next transition uses it either way.
        writer.execute {
            store?.save(trackId, override)
        }
    }

    /** Forgets [trackId]'s choice, leaving the planner to decide again. */
    fun clear(trackId: String) {
        if (trackId.isBlank()) return
        known.remove(trackId)
        writer.execute {
            store?.clear(trackId)
        }
    }

    /**
     * The thread the disk work happens on.
     *
     * One thread, so two writes for the same track cannot overtake each
     * other: the last one the listener made is the one that ends up on disk.
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "automix-profiles").apply { isDaemon = true }
    }
}
