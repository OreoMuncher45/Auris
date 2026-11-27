package com.music.bitchord.playback

/**
 * A map whose entries belong to the session they were read for, the one
 * [owner] names: when the session changes (another listener, or the same one
 * after a switch away and back), everything kept for the previous one is
 * dropped on the next read or write, so a row read for one session is never
 * handed back in another.
 *
 * A write names the session its entries were read for, and is dropped when
 * that is no longer the current one: a listing or search that lands after a
 * switch, even one away and back, files nothing. Entries that belong to no
 * account (files on the device, downloads) go through [putShared].
 *
 * Holds at most [capacity] entries, the oldest written dropped first.
 */
internal class ListenerScopedCache<S, V : Any>(
    private val capacity: Int = Int.MAX_VALUE,
    private val owner: () -> S,
) {
    private val entries = object : LinkedHashMap<String, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
            (size > capacity).also { if (it && eldest != null) sharedKeys.remove(eldest.key) }
    }
    private var scope: Any? = UNSET
    /** The keys of entries that belong to no account; see [putShared]. Under the lock. */
    private val sharedKeys = HashSet<String>()

    /** [entries] for the session now, emptied first when that is another one. Called under the lock. */
    private fun current(): LinkedHashMap<String, V> {
        val now = owner()
        if (now != scope) {
            entries.clear()
            sharedKeys.clear()
            scope = now
        }
        return entries
    }

    /** The entry for [key], in one step: a switch can't land between a membership check and the read. */
    @Synchronized
    operator fun get(key: String): V? = current()[key]

    /** One entry and whether it belongs to no account, or was read for the session now. */
    class Entry<V>(val value: V, val shared: Boolean)

    /** [get], saying where the entry came from: a hit read for the session is that session's. */
    @Synchronized
    fun entry(key: String): Entry<V>? = current()[key]?.let { Entry(it, key in sharedKeys) }

    /** Files [value] under [key], read for [session], unless another session is current now. */
    @Synchronized
    fun put(session: S, key: String, value: V) {
        val map = current()
        if (session == scope) {
            map[key] = value
            sharedKeys.remove(key)
        }
    }

    /** Files [values] (by [keyOf]) read for [session], unless another session is current now. */
    @Synchronized
    fun putAll(session: S, values: Iterable<V>, keyOf: (V) -> String) {
        val map = current()
        if (session != scope) return
        values.forEach {
            val key = keyOf(it)
            map[key] = it
            sharedKeys.remove(key)
        }
    }

    /** Files [values] that belong to no account, for whichever session is current. */
    @Synchronized
    fun putShared(values: Iterable<V>, keyOf: (V) -> String) {
        val map = current()
        values.forEach {
            val key = keyOf(it)
            map[key] = it
            sharedKeys += key
        }
    }

    private companion object {
        val UNSET = Any()
    }
}
