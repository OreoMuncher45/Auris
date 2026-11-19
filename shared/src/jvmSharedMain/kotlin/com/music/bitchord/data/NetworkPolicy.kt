package com.music.bitchord.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the app may talk to the internet, right now.
 *
 * Published by the app's own settings — the offline switch, and the network
 * detector behind it — and obeyed by [Http], which is the one place every
 * request in this app funnels through: InnerTube's own calls, the addons',
 * the lyrics providers', the canvas', the scrobblers', the artwork, and the
 * player's own media fetches.
 *
 * The point of putting it here rather than in each caller is the word
 * *zero*. "Offline mode" that each subsystem has to remember to honour is
 * offline mode that works until the next subsystem is written. A verdict
 * published once, obeyed in one place, is offline mode that works.
 *
 * Held in memory rather than in preferences on purpose: this is a fact about
 * right now, not a setting the listener sets. The app decides it; the network
 * obeys; neither asks the other.
 */
object NetworkPolicy {

    /**
     * True when no request may leave the device.
     *
     * Read on every request, so it is a plain volatile field rather than a
     * flow: the network layer must not suspend to ask.
     */
    @Volatile
    var offline: Boolean = false

    /**
     * The hosts that are the listener's own storage rather than the internet.
     *
     * A WebDAV server on the local network holds files that are already here,
     * and taking them away because the listener asked for no *internet* would
     * be the wrong trade: the point of offline mode is that what is on the
     * device keeps working. A host is listed here only when the listener has
     * configured it themselves, so this is not a hole a remote server can ask
     * for.
     */
    private val localHosts = ConcurrentHashMap.newKeySet<String>()

    /** Publishes the listener's own storage host, or clears it with null. */
    fun setLocalHost(host: String?) {
        val cleaned = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cleaned == null) localHosts.clear() else localHosts.add(cleaned)
    }

    /** Whether requests to [host] are the listener's own storage. */
    fun isLocalHost(host: String): Boolean =
        localHosts.contains(host.lowercase())

    /**
     * Whether a request to [host] may go out at all.
     *
     * The one question [Http] asks before it lets a request leave.
     */
    fun allows(host: String): Boolean = !offline || isLocalHost(host)
}
