package com.music.bitchord.playback

/**
 * A map whose entries belong to the listener they were written under, the
 * one [owner] names at the time: when the listener changes, everything
 * written for the previous one is dropped on the next read or write, so a
 * row listed for one account is never handed back while another is signed in.
 */
internal class ListenerScopedCache<V : Any>(private val owner: () -> String?) {
    private val entries = HashMap<String, V>()
    private var scope: String? = null

    /** [entries] for the listener now, emptied first when that is another one. Called under the lock. */
    private fun current(): HashMap<String, V> {
        val now = owner()
        if (now != scope) {
            entries.clear()
            scope = now
        }
        return entries
    }

    @Synchronized
    operator fun get(key: String): V? = current()[key]

    @Synchronized
    operator fun set(key: String, value: V) {
        current()[key] = value
    }

    @Synchronized
    fun containsKey(key: String): Boolean = current().containsKey(key)
}
