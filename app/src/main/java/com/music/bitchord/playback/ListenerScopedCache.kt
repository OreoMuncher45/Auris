package com.music.bitchord.playback

/**
 * A map whose entries belong to the listener they were read for, the one
 * [owner] names: when the listener changes, everything kept for the previous
 * one is dropped on the next read or write, so a row listed for one account
 * is never handed back while another is signed in.
 *
 * A write names the listener its entries were read for, and is dropped when
 * that is no longer the listener: a listing that lands after a switch, even
 * one away and back to the same listener's key, can't file its rows under
 * the account that took over. Entries that belong to no account (files on
 * the device, downloads) go through [putShared].
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

    /** The entry for [key], in one step: a switch can't land between a membership check and the read. */
    @Synchronized
    operator fun get(key: String): V? = current()[key]

    /** Files [values] (by [keyOf]) read for [listener], unless another listener is current now. */
    @Synchronized
    fun putAll(listener: String?, values: Iterable<V>, keyOf: (V) -> String) {
        val map = current()
        if (listener != scope) return
        values.forEach { map[keyOf(it)] = it }
    }

    /** Files [values] that belong to no account, for whoever is listening now. */
    @Synchronized
    fun putShared(values: Iterable<V>, keyOf: (V) -> String) {
        val map = current()
        values.forEach { map[keyOf(it)] = it }
    }
}
