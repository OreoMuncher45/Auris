package com.music.bitchord.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether a stream can be started, decided from the default-network callbacks.
 *
 * The verdict follows what each callback *says* — which network is the
 * default, whether it is validated, that it is gone — rather than asking the
 * system again from inside the callback. The system's answer lags its own
 * callbacks: an `onLost` arrived while `activeNetwork` still named the lost
 * network as validated, and a verdict read from the system called that
 * online, with no later callback to put it right. The app then stayed online
 * with the radio off, and only a restart fixed it. The mirror case — every
 * callback of a reconnect reading a stale "not validated" — left it offline
 * with the network back, because the delayed check could only ever say
 * "down".
 *
 * Believed at once when the default network is validated; believed gone only
 * after [graceMs] and only if it is still gone then, so a handover's moment
 * with no network never flips the player between files and streams. The
 * delayed check publishes whatever is true when it runs, up or down.
 *
 * [systemSaysUp] is asked once, at [start], for the case where there is no
 * network at all: the system then sends no callback to learn that from.
 */
internal class ConnectionWatch(
    private val scope: CoroutineScope,
    private val graceMs: Long,
    private val systemSaysUp: () -> Boolean,
    private val publish: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var network: Any? = null
    private var validated = false
    private var pendingCheck: Job? = null

    /** Before the callbacks are registered: with nothing there, no callback ever comes. */
    fun start() {
        if (systemSaysUp()) {
            publish(true)
        } else {
            synchronized(lock) { scheduleCheck() }
        }
    }

    fun onAvailable(network: Any) = settle {
        if (this.network != network) validated = false
        this.network = network
    }

    fun onCapabilities(network: Any, validated: Boolean) = settle {
        this.network = network
        this.validated = validated
    }

    fun onLost(network: Any) = settle {
        // A network that is no longer the default going away says nothing
        // about the one that is.
        if (this.network == network) {
            this.network = null
            validated = false
        }
    }

    private inline fun settle(change: () -> Unit) {
        synchronized(lock) {
            change()
            if (isUp()) {
                pendingCheck?.cancel()
                pendingCheck = null
                publish(true)
            } else {
                scheduleCheck()
            }
        }
    }

    /** Called under [lock]. */
    private fun isUp() = network != null && validated

    /** Called under [lock]. */
    private fun scheduleCheck() {
        pendingCheck?.cancel()
        pendingCheck = scope.launch {
            delay(graceMs)
            synchronized(lock) {
                publish(isUp())
                pendingCheck = null
            }
        }
    }
}
