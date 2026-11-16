package com.music.bitchord.playback

import android.os.SystemClock
import com.music.bitchord.data.sources.SourceStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Which copy of a track is being served, remembered for as long as the track
 * is being served from it.
 *
 * ExoPlayer opens a source many times over one play: the initial read, a seek
 * past the buffer, a resume, and — the one that matters here — the
 * continuation fetch when playback reaches the end of what the cache holds.
 * Every one of those goes back through the resolving data source, and until
 * this existed, every one of them was free to come back with a *different*
 * answer.
 *
 * That is not a hypothetical. Measured on this device: a track started on
 * YouTube with 57 seconds pre-buffered, played to the end of those 57 seconds,
 * and its continuation fetch resolved — correctly, by its own lights — to a
 * module's 320kbps AAC instead. The player was handed the middle of an MP4
 * where it expected the rest of a WebM, and `MatroskaExtractor` threw
 * `EOFException`:
 *
 * ```
 *   12:09:04  first audio          (YouTube Opus, buffered position 57761)
 *   12:10:12  Source error
 *             Caused by: java.io.EOFException
 *               at MatroskaExtractor.read
 * ```
 *
 * Two sources cannot share one cache entry, and [AudioCache]'s key factory
 * cannot tell them apart: it runs *before* the resolve, off the
 * `bitchord://watch?v=…` URI, and at that point which server will answer is
 * not yet known. So the fix goes the other way round — the entry does not
 * learn who is filling it, the resolver is held to whoever filled it first.
 *
 * [QualityUpgrade] already worked this way for the streams it swaps in, for
 * exactly the same reason, and the reasoning simply hadn't been carried back
 * to the first resolve.
 *
 * ### Why a time limit
 *
 * A module's stream URL is signed and expires — around five hours, on the
 * catalogues in use here. Holding one indefinitely would eventually hand the
 * player a dead URL rather than a wrong one, which is no better. [TTL_MS] is
 * far inside every expiry seen, so a choice is either reused while it is
 * certainly still good or made again from scratch.
 */
object StreamChoice {

    private class Choice(val stream: SourceStream, val at: Long, val substituted: Boolean)

    private val chosen = ConcurrentHashMap<String, Choice>()

    /**
     * The stream already serving [videoId], or null if this is the first read
     * for it — or the last one was long enough ago that its URL is no longer
     * worth trusting.
     */
    fun of(videoId: String): SourceStream? {
        val choice = chosen[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - choice.at > TTL_MS) {
            // By value: a fresh pin made since the read above is not this one.
            chosen.remove(videoId, choice)
            return null
        }
        return choice.stream
    }

    /**
     * Records [stream] as the one copy of [videoId] this play is reading.
     *
     * A pin is a promise about *who is filling the cache entry*, and the cache
     * entry is shared by every writer for that track — see the class doc. So
     * the first pin for a track is the one that stands: a later answer from
     * another server cannot take the entry over, because the bytes already
     * written into it came from the first one and a second server's bytes at
     * those same offsets are a different recording of the same track.
     *
     * That matters most for the read-ahead pin, which is chosen by the patient
     * search and so is the better copy of the two: it must not be replaced by
     * the live resolve's YouTube answer when the live resolve happens to
     * finish first. It also matters in the other direction — a live answer
     * arriving after the read-ahead pin must not displace it either — and both
     * directions are covered by the same rule.
     *
     * A pin that has outlived [TTL_MS] is not a promise any more, and its
     * track may be pinned afresh.
     *
     * @param substituted whether this came from a source standing in for
     *   YouTube rather than from YouTube itself. Only the resolver knows, and
     *   only [refuseSubstitutes] needs it — a substitution that turns out to be
     *   unplayable has somewhere else to fall back to, and a YouTube stream
     *   that fails has not.
     * @return whether this pin became the one in force.
     */
    fun remember(videoId: String, stream: SourceStream, substituted: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val existing = chosen[videoId]
        if (existing != null && now - existing.at <= TTL_MS) {
            // Already pinned, and the promise still holds. The track is being
            // filled by that server; this answer does not get to change who
            // that is.
            return false
        }
        if (chosen.size >= MAX_REMEMBERED) {
            // Drop what can no longer be honoured, and only then the oldest of
            // what can. This used to `clear()`, which is the one eviction
            // capable of causing the corruption this class exists to prevent:
            // every live entry is a promise that a half-filled cache entry will
            // be finished by the stream that started filling it, and emptying
            // the map frees *all* of those entries to be finished by a
            // different server instead.
            //
            // Harmless while only playback wrote here — an entry was made and
            // consumed within one track. Read-ahead now pins the next track
            // before it is reached and caches its bytes on the strength of that
            // pin, so a promise can outlive several other tracks' worth of
            // entries, and the blunt version became reachable.
            chosen.entries.removeIf { now - it.value.at > TTL_MS }
            if (chosen.size >= MAX_REMEMBERED) {
                chosen.minByOrNull { it.value.at }?.let { chosen.remove(it.key, it.value) }
            }
        }
        // The check above is only a fast path. Two opens of one track (the
        // player's and read-ahead's, say) can both pass it, so the decision
        // that counts is this one, made atomically per track: an unexpired
        // pin that arrived in between wins, and only one caller gets true.
        var won = false
        chosen.compute(videoId) { _, current ->
            if (current != null && now - current.at <= TTL_MS) {
                current
            } else {
                won = true
                Choice(stream, now, substituted)
            }
        }
        if (!won) return false
        preparationChanged()
        return true
    }

    /**
     * Whether the copy currently serving [videoId] came from a source standing
     * in for YouTube.
     *
     * Read on the recovery path, before [forget], to tell a track that died on
     * a substitution from one that died on YouTube's own stream. The two have
     * opposite right answers: the first should stop substituting, the second
     * has nothing better to try.
     */
    fun isSubstitute(videoId: String): Boolean = chosen[videoId]?.substituted == true

    /**
     * Releases [videoId] to be resolved afresh.
     *
     * Called when the bytes behind the choice are gone or were never any good:
     * the queue moved past the track, its cache entry was discarded after a
     * read error, or [QualityUpgrade] replaced the whole stream with a better
     * one.
     */
    fun forget(videoId: String) {
        chosen.remove(videoId)
    }

    /**
     * Tracks outside [keep] pinned to a source the listener has switched off,
     * each with whether that source is YouTube: a source whose config id is
     * not in [enabled], or a plain YouTube pin while [youtubeEnabled] is false.
     * A substitute pin with no config id names no source and is left alone.
     *
     * A pin outlives its source being switched off, and nothing else lets go
     * of it: [remember] keeps an unexpired pin over any replacement, so every
     * later open of the track is served from the switched-off source until the
     * pin expires. It can't simply be dropped where it is found, because the
     * cache entry it filled may hold that source's bytes and may still be
     * written by a reader that opened earlier. The cache purge hands these
     * tracks over instead, for tracks away from the playhead: their entry is
     * dropped first, then [handOver] releases the pin.
     */
    fun inactivePins(
        enabled: Set<String>,
        youtubeEnabled: Boolean,
        keep: Set<String>,
        youtubeConfigIds: Set<String>,
    ): Map<String, Boolean> =
        buildMap {
            for ((id, choice) in chosen) {
                if (id in keep) continue
                val source = choice.stream.sourceConfigId
                when {
                    // YouTube's own row in the source list: a source-backed
                    // track that fell back to YouTube carries its id. The bytes
                    // are YouTube's, so they are dropped as YouTube's.
                    source != null && source in youtubeConfigIds ->
                        if (!youtubeEnabled || source !in enabled) put(id, true)
                    source != null -> if (source !in enabled) put(id, false)
                    !choice.substituted -> if (!youtubeEnabled) put(id, true)
                }
            }
        }

    /** Releases the pins of [videoIds]; see [inactivePins]. */
    fun forgetAll(videoIds: Set<String>) {
        videoIds.forEach(chosen::remove)
    }

    /**
     * Hands the [stale] tracks from [inactivePins] over: drops their cached
     * bytes, then releases the pins whose bytes are all gone and whose track
     * is still outside the playhead window. Returns the released ids.
     *
     * [drop] removes the cache entries one source filled (YouTube's when its
     * flag is true) and returns the ids whose entries are all gone. A removal
     * that fails leaves bytes behind, and that track's pin stays.
     *
     * Removal failing is not what protects a track being read: Media3's
     * `SimpleCache.removeResource` ignores readers' locks. The playhead window
     * protects it. It is excluded before the drop, and [window] is read again
     * after it, because the playhead can move onto a track while the drop
     * runs. A track that did keeps its pin, so its reader and every later open
     * agree on the source. Both cases are left for a later purge.
     */
    suspend fun handOver(
        stale: Map<String, Boolean>,
        drop: suspend (ids: Set<String>, youtube: Boolean) -> Set<String>,
        window: () -> Set<String>,
        writers: WriterGate = WriterGate.NONE,
    ): Set<String> {
        if (stale.isEmpty()) return emptySet()
        // A track something is still writing is left alone entirely: removing
        // its committed bytes would not stop the writer committing more.
        val tokens = stale.keys.mapNotNull { id -> writers.token(id)?.let { id to it } }.toMap()
        // The pins as they stand now. Only these are released, so a track that
        // is pinned afresh while the drop runs keeps its new pin.
        val seen = tokens.keys.mapNotNull { id -> chosen[id]?.let { id to it } }.toMap()
        val dropped = stale.filterKeys { it in tokens }.entries.groupBy({ it.value }, { it.key })
            .flatMap { (youtube, ids) -> drop(ids.toSet(), youtube) }
            .toSet()
        return (dropped - window()).filterTo(mutableSetOf()) { id ->
            val pin = seen[id] ?: return@filterTo false
            writers.releaseIf(id, tokens.getValue(id)) { chosen.remove(id, pin) }
        }
    }

    /**
     * What [handOver] asks before it touches a track's cache entry and again
     * before it releases the pin: whether anything is writing that entry.
     *
     * Needed because Media3's `SimpleCache.removeResource` neither refuses an
     * entry that is being written nor stops the writer, so a writer still
     * running would commit old bytes after the drop. Implemented by
     * [AudioCache.writerGate], which counts the fetches its cache makes.
     */
    interface WriterGate {
        /**
         * A token for [videoId]'s writers, or null while one is fetching or
         * has only just finished (its last span may not be committed yet).
         */
        fun token(videoId: String): Long?

        /**
         * Runs [release] only if no writer for [videoId] has started since
         * [token] was taken, atomically with writers starting: a writer that
         * starts after this either sees the pin still in force, which means
         * this did not run, or finds it gone and resolves afresh.
         */
        fun releaseIf(videoId: String, token: Long, release: () -> Boolean): Boolean

        companion object {
            /** No writers to wait for. */
            val NONE: WriterGate = object : WriterGate {
                override fun token(videoId: String): Long = 0L
                override fun releaseIf(videoId: String, token: Long, release: () -> Boolean) = release()
            }
        }
    }

    /**
     * Tracks whose read-ahead preparation is still looking for a stream.
     *
     * [AudioCache.prefetchQueue][com.music.bitchord.playback.AudioCache.prefetchQueue]
     * resolves a queue's next track while the current one plays and pins the
     * answer here. The player may open that next item *before* the pin lands —
     * a playlist's following items are prepared ahead of the transition — and
     * that first open is the one that decides which server's bytes fill the
     * cache entry. Without this the open would race YouTube, which structurally
     * wins, and pin it; the prepared stream would then arrive too late to be
     * the copy the hand-over uses. See `PlaybackService`'s resolving data
     * source, which waits on this rather than opening the question early.
     */
    private val preparing = ConcurrentHashMap.newKeySet<String>()

    /** Announces that [videoId]'s prepared stream is being looked up. */
    fun beginPreparing(videoId: String) {
        if (preparing.size >= MAX_REMEMBERED) {
            preparing.clear()
            preparationChanged()
        }
        preparing += videoId
    }

    /** Withdraws [beginPreparing]'s claim — with a pin if it found one, without if it didn't. */
    fun endPreparing(videoId: String) {
        preparing -= videoId
        preparationChanged()
    }

    /**
     * Whether a first open should hold off for a prepared pin: a preparation is
     * running and has not produced a choice yet. Once it has, [of] answers and
     * the ordinary pinned path takes over; once it is withdrawn, there is
     * nothing left to wait for.
     */
    fun isAwaitingPreparation(videoId: String): Boolean =
        videoId in preparing && of(videoId) == null

    /**
     * Bumped after every change that can end a wait in [awaitPreparation]: a
     * pin being made, or a preparation being withdrawn. Its value means
     * nothing; that it changed is what wakes the waiter.
     */
    private val preparationChanges = MutableStateFlow(0L)

    private fun preparationChanged() {
        preparationChanges.update { it + 1 }
    }

    /**
     * Suspends while [isAwaitingPreparation] holds for [videoId], and returns
     * as soon as it doesn't. Returns at once if there is nothing to wait for.
     *
     * The resolver used to ask [isAwaitingPreparation] every 25ms instead,
     * which woke its thread up to sixty times per wait and answered up to
     * 25ms after the pin had already landed. The caller bounds the wait.
     */
    suspend fun awaitPreparation(videoId: String) {
        preparationChanges.first { !isAwaitingPreparation(videoId) }
    }

    /**
     * Tracks whose substituted stream refused to serve its bytes, and when.
     *
     * [forget] alone is not enough to recover from one. The search behind a
     * substitution is deterministic — the same module, asked the same query for
     * the same tier, answers with the same URL — and it is also *fast*, because
     * by the second attempt its index and its module are cached. So a retry
     * that is free to substitute again wins the race against YouTube by the
     * same margin it won it the first time, and resolves straight back to the
     * URL that just failed. Three attempts of that is a track that never plays
     * at all, while a working YouTube URL sits in
     * [StreamResolver][com.music.bitchord.data.innertube.StreamResolver]'s cache
     * unused.
     *
     * ### Why this expires
     *
     * The first version of this held a refusal for the life of the process, on
     * the reasoning that a deterministic search deserves a permanent answer.
     * Nothing measured supports going that far. What still reaches here is a URL
     * that was well formed and would not serve anyway, and the ordinary causes
     * of that — a signature that expired between minting and use, a backend
     * having a bad minute — clear up by themselves. Held forever, one of those
     * would cost the lossless copy of every track it touched for the rest of the
     * session: a real loss traded against an unproven gain. So the refusal is a
     * cooling-off period, not a verdict — long enough to get the track playing
     * and keep it playing, short enough to ask again in the same sitting.
     *
     * Most of what used to arrive here doesn't any more: [ModuleSource.malformed]
     * rejects a URL carrying its own origin twice before anything tries to play
     * it. This is the net under that, for the failures only playback can find.
     */
    private val refusedSubstitutes = ConcurrentHashMap<String, Long>()

    /**
     * Stops [videoId] being substituted for [REFUSAL_MS], and sends it to
     * YouTube instead. Called from the recovery path; see
     * [PlaybackService.recoverFrom].
     */
    fun refuseSubstitutes(videoId: String) {
        if (refusedSubstitutes.size >= MAX_REMEMBERED) refusedSubstitutes.clear()
        refusedSubstitutes[videoId] = SystemClock.elapsedRealtime()
    }

    /** Whether a substitution has broken [videoId] recently enough to still count. */
    fun substitutesRefused(videoId: String): Boolean {
        val at = refusedSubstitutes[videoId] ?: return false
        if (SystemClock.elapsedRealtime() - at <= REFUSAL_MS) return true
        refusedSubstitutes.remove(videoId)
        return false
    }

    /** Long enough to outlast any one play, far short of any signed URL's life. */
    private const val TTL_MS = 15 * 60 * 1000L

    /**
     * How long a track stays off substitution after one broke it.
     *
     * Comfortably longer than the recovery it exists to protect — the retry
     * happens within seconds — and longer than a play of the track, so nothing
     * swaps back mid-song. Short enough that a module which was briefly
     * returning bad URLs gets another chance inside the same listening session.
     */
    private const val REFUSAL_MS = 10 * 60 * 1000L

    private const val MAX_REMEMBERED = 32
}
