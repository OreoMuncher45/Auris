package com.music.bitchord.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether a sign-in or channel switch has installed candidate credentials that
 * are not yet validated.
 *
 * While it is [active], `Innertube` already signs requests as the candidate
 * while the view model's listener is still the old one, so a request's
 * credentials say nothing about whose data it fetches. A request that would
 * file its answer under a listener waits in [awaitSettled] first, and then
 * reads the listener and the credentials as one settled pair.
 */
internal class CandidateValidation {
    private val state = MutableStateFlow(false)

    val active: Boolean get() = state.value

    /** Marks validation as running for the length of [block]. */
    suspend fun <T> during(block: suspend () -> T): T {
        state.value = true
        try {
            return block()
        } finally {
            state.value = false
        }
    }

    /** Returns at once when nothing is being validated; otherwise once it ends. */
    suspend fun awaitSettled() {
        if (!state.value) return
        state.first { !it }
    }
}
