package com.music.bitchord.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether a sign-in or channel switch has installed candidate credentials that
 * are not yet validated, or is waiting to.
 *
 * While it is [active], `Innertube` may already sign requests as a candidate
 * while the view model's listener is still the old one, so a request's
 * credentials say nothing about whose data it fetches. A request that would
 * file its answer under a listener waits in [awaitSettled] first, and then
 * reads the listener and the credentials as one settled pair.
 *
 * Validations run one at a time ([during] serializes them), and readers are
 * released only once none is running or queued: the one that finishes first
 * must not let readers through while a later one's candidate credentials are
 * installed.
 */
internal class CandidateValidation {
    companion object {
        /**
         * The app's one sign-in gate. Shared because the playback service
         * reads listener data too and must hold off a candidate's answers
         * the same way the view model does.
         */
        val session = CandidateValidation()
    }

    /** Validations running or queued behind the one that is. */
    private val pending = MutableStateFlow(0)
    private val serial = Mutex()

    val active: Boolean get() = pending.value > 0

    /**
     * Runs [block] — the whole validation, from reading the identity to roll
     * back to, through to accepting or restoring — after any earlier one, with
     * readers held from now until no validation is left.
     */
    suspend fun <T> during(block: suspend () -> T): T {
        pending.update { it + 1 }
        try {
            return serial.withLock { block() }
        } finally {
            pending.update { it - 1 }
        }
    }

    /** Returns at once when nothing is being validated; otherwise once none is left. */
    suspend fun awaitSettled() {
        if (pending.value == 0) return
        pending.first { it == 0 }
    }
}
