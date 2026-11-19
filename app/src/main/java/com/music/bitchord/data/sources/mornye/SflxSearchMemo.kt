package com.music.bitchord.data.sources.mornye

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The answers a host has already given, and the questions currently being
 * asked.
 *
 * A search is not free. The extension runs its own catalogue walk in
 * JavaScript and answers with kilobytes of JSON, and the app asks the same
 * question from several places: the search screen, the row warming, the
 * playback resolve, the upgrade, the next-track preparation. Measured on a
 * device log, one track's worth of looking up ran the extension's search
 * eight times in twelve seconds — every one of them the same question with
 * the same answer.
 *
 * Two things stop that:
 *
 *  - **The answer is kept**, for a moment. Long enough to cover one track's
 *    whole life — the search, the warming, the resolve, the upgrade and the
 *    preparation all happen inside a couple of minutes of each other — and
 *    short enough that a catalogue which changed while the app was open is
 *    not answered from the past for the rest of the session.
 *
 *  - **A question asked while another is running waits for that one** rather
 *    than starting a second. The two callers want the same answer, and the
 *    extension has no reason to be asked twice at once.
 *
 * The work runs in a scope of its own, not the caller's: a search started for
 * one caller must not be cancelled when that caller goes away, because
 * another caller may be waiting on the same answer — and an answer nobody
 * heard is still an answer, which is the whole point of keeping it.
 *
 * @param ttlMs how long an answer is kept.
 * @param clock what "now" is. A test says what it wants; the app says the
 *   wall clock.
 */
internal class SflxSearchMemo<V>(
    private val ttlMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class Entry<V>(val value: V, val at: Long)

    private val answers = ConcurrentHashMap<String, Entry<V>>()
    private val flights = ConcurrentHashMap<String, CompletableDeferred<Result<V>>>()

    /**
     * Questions that were running when their answers were invalidated. Their
     * callers still hear them — they asked before the change — but the answer
     * is not kept, because it is the old script's or the old settings'.
     */
    private val fenced = ConcurrentHashMap.newKeySet<CompletableDeferred<Result<V>>>()

    /** Held across "is this flight fenced" and the store, and across an invalidation. */
    private val storing = Any()

    /**
     * Bumped by every invalidation. A caller notes it before looking in the
     * cache and registers its question only if it has not moved, so a caller
     * that missed the cache, paused, and arrived at the board after an
     * invalidation cannot put a question there for later callers to join.
     */
    @Volatile private var generation = 0L

    /** For tests: runs between a cache miss and the question being registered. */
    internal var beforeRegister: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The answer to a question already asked, or null when there is none. */
    fun cached(key: String): V? {
        val entry = answers[key] ?: return null
        if (clock() - entry.at > ttlMs) {
            answers.remove(key, entry)
            return null
        }
        return entry.value
    }

    /** Keeps an answer. */
    fun put(key: String, value: V) {
        answers[key] = Entry(value, clock())
    }

    /** Forgets one answer. */
    fun forget(key: String) {
        answers.remove(key)
    }

    /**
     * Forgets every answer whose key starts with [prefix], and the questions
     * still running under it: a caller that arrives after this asks afresh
     * rather than joining one of them, and what they answer is not kept.
     */
    fun forgetMatching(prefix: String) {
        synchronized(storing) {
            generation++
            answers.keys.removeIf { it.startsWith(prefix) }
            flights.entries.removeIf { (key, flight) ->
                if (key.startsWith(prefix)) {
                    fenced.add(flight)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Takes a finished question off the board, and keeps its answer unless an
     * invalidation fenced it — one step under [storing], so an invalidation
     * cannot land between the store and the removal and leave a fence behind
     * that nothing would ever clear.
     */
    private fun retire(key: String, flight: CompletableDeferred<Result<V>>, answer: Result<V>?) {
        synchronized(storing) {
            val wasFenced = fenced.remove(flight)
            flights.remove(key, flight)
            if (!wasFenced) answer?.onSuccess { put(key, it) }
        }
    }

    /** For tests: how many fences are being held. */
    internal fun fencesHeld(): Int = fenced.size

    /**
     * The answer to [key], asked once.
     *
     * The answer is taken from the cache when it is there, from the question
     * already running when one is, and from [ask] otherwise — in which case
     * the answer is kept for the next caller.
     */
    suspend fun once(key: String, ask: suspend () -> Result<V>): Result<V> {
        val seen = generation
        cached(key)?.let { return Result.success(it) }
        beforeRegister?.invoke()
        val flight = CompletableDeferred<Result<V>>()
        var straddled = false
        val running = synchronized(storing) {
            if (generation != seen) {
                straddled = true
                null
            } else {
                flights.putIfAbsent(key, flight)
            }
        }
        // Asked before an invalidation, registered after it: the question is
        // answered for this caller alone, and its answer is not kept.
        if (straddled) {
            return try {
                ask()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Result.failure(SflxException(error.message ?: "the question failed"))
            }
        }
        if (running != null) return running.await()
        scope.launch {
            val answer = try {
                ask()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    retire(key, flight, null)
                    flight.completeExceptionally(error)
                    return@launch
                }
                Result.failure(SflxException(error.message ?: "the question failed"))
            }
            // Off the board before anyone hears the answer. Completing first
            // resumes the waiting caller, which may ask again — after a
            // forget, say — before the flight is retired, find it still there,
            // and be handed its old answer without asking. With the order this
            // way round, a caller that arrives in between finds the answer in
            // the cache, or nothing, and asks afresh.
            retire(key, flight, answer)
            flight.complete(answer)
        }
        return flight.await()
    }
}
