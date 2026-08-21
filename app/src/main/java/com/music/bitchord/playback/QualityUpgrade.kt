package com.music.bitchord.playback

import android.net.Uri
import android.util.Log
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

/**
 * The second look: a track that started on less than was asked for gets the
 * question asked again, properly, while it plays.
 *
 * The live path has to answer in the time a listener will wait for a track to
 * start, and it buys that by giving up on the slow catalogue — which is
 * regularly the one holding the lossless copy. Measured on this device: the
 * fastest module answered a search in 1.3s and the slowest in 5.4s, and the
 * slow one then took a further 8.2s to walk its own fallback chain down to a
 * 128kbps MP3. Waiting for all of that costs 14 seconds of silence; not
 * waiting costs the FLAC. Neither is a good trade, and neither has to be made
 * once the search can happen with sound already coming out of the speaker.
 *
 * So: play whatever can be had now, then look again with no time limit, and
 * swap only if the answer is genuinely the same recording *and* genuinely
 * better than what is playing — which is usually the lossless copy that was
 * asked for, but is also a 320kbps module stream against YouTube's 160kbps
 * Opus. See [SourceResolver.worthSwapping] for where that line is drawn.
 * The swap is not free — ExoPlayer cannot change sources
 * gaplessly mid-track, so there is a short break in the audio — which is why
 * every guard here errs towards not doing it. A missed upgrade is a quieter
 * failure than an interrupted song.
 *
 * ### How the swap reaches the player
 *
 * The queue holds `bitchord://watch?v=…` URIs that
 * [PlaybackService][PlaybackService]'s resolving data source turns into real
 * URLs at open time. An upgrade re-points that indirection rather than
 * touching the queue: the stream is parked in [forced], the item is replaced
 * with the same URI plus a `q=` marker, and the marker does two jobs — it
 * makes the item unequal to its old self so Media3 actually rebuilds the media
 * source, and it keys the disk cache separately so the FLAC is not written
 * into the middle of the half-cached MP3 it is replacing.
 */
object QualityUpgrade {

    private const val TAG = "BitChord"

    /** The marker that distinguishes an upgraded item from the one it replaced. */
    const val MARKER = "q"
    private const val UPGRADED = "hifi"

    /**
     * A track playing on less than was asked for.
     *
     * [inFlight] is the live lookup that lost the race rather than ran out of
     * answers — still running, and worth waiting on rather than repeating,
     * because whatever it returns is precisely the stream that would have
     * played had it been quicker. Null once the live path has finished and
     * come back with nothing better; the second look then has to go find its
     * own candidates.
     */
    private data class Pending(
        val target: TrackMatcher.Target,
        val inFlight: Deferred<SourceStream?>? = null,
        /**
         * What the listener is actually hearing — the yardstick a lossy
         * candidate is measured against in [SourceResolver.worthSwapping].
         * Known by the time a track is marked pending: whichever stream won
         * the race has already named its format. Null only when nothing did,
         * and an unknown floor is one nothing lossy clears.
         */
        val playing: StreamFormat? = null,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val forced = ConcurrentHashMap<String, SourceStream>()

    /**
     * Records that [mediaId] is playing on less than was asked for — whether
     * that is a lossy stream a module handed over, or YouTube's own because no
     * module answered in time.
     *
     * Called from the resolving data source, which is the only place that
     * knows both what was requested and what actually came back. The track
     * stays in [NerdStats.racingLossless] from here until the second look
     * finishes, so the player keeps saying "Loading lossless" rather than
     * going blank and then possibly changing its mind — the badge should
     * describe the search that is genuinely still running, and go out for good
     * once the answer is known to be no.
     *
     * Does nothing unless lossless is what the connection and the settings
     * currently add up to. There is no such thing as an upgrade from a stream
     * that is already everything that was asked for, and marking one pending
     * would light the badge for a search with no possible outcome.
     */
    fun settledForLess(
        mediaId: String,
        target: TrackMatcher.Target,
        inFlight: Deferred<SourceStream?>? = null,
        playing: StreamFormat? = null,
    ): Boolean {
        if (target.title.isBlank() ||
            mediaId in refused ||
            SourceResolver.requestForNow() !is StreamRequest.Lossless ||
            !SourceResolver.canSubstituteForYouTube()
        ) {
            inFlight?.cancel()
            return false
        }
        pending[mediaId] = Pending(target, inFlight, playing)
        NerdStats.onLosslessRaceStart(mediaId)
        TrackLog.d(
            TAG,
            if (inFlight != null) {
                "'${target.title}' started on the fallback; its lookup is still running"
            } else {
                "below request for '${target.title}'; will look again during playback"
            },
        )
        return true
    }

    /** Whether [mediaId] is worth a second look — and hasn't already had one. */
    fun isPending(mediaId: String?) = mediaId != null && pending.containsKey(mediaId)

    /**
     * Tracks whose upgrade broke the playback it was supposed to improve.
     *
     * A swapped-in stream that fails to serve its bytes costs a cut in the
     * audio and a recovery, and the search that produced it is deterministic —
     * ask again and the same catalogue returns the same dead URL. Nothing here
     * expires: the entry is worth exactly as long as the queue that holds the
     * track, and the map is cleared with the rest when the process goes.
     */
    private val refused = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Stops offering [mediaId] any further upgrades this session — its last
     * one is what killed it. Called from the recovery path; see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService].
     */
    fun refuseUpgrades(mediaId: String) {
        refused += mediaId
        TrackLog.d(TAG, "$mediaId broke on its upgrade; no more swaps for it")
    }

    /**
     * Looks for a stream that actually satisfies the request, for a track
     * already playing.
     *
     * The track stays in [NerdStats.racingLossless] when this returns a stream
     * — the caller ends it once the swap has landed or been given up on. The
     * badge describes the *upgrade*, not the search behind it, and those stop
     * being the same thing as soon as the search can finish before the track
     * it was for comes round. That is now the ordinary case: a track is
     * resolved while its predecessor plays, so by the time a listener is
     * hearing it the lookup has often been sitting on the answer for a minute.
     * Ended here, "Upgrading Quality" was drawn for the few milliseconds
     * between the track becoming current and this returning, and the audio
     * then changed five seconds later with nothing on screen having said so.
     *
     * @param playingDurationSec the runtime the *decoder* reports, which is
     *   the one thing here that is measured rather than claimed. A candidate
     *   has to match it — see [SourceResolver.upgradeFor].
     * @return the better stream, or null if there isn't one, in which case
     *   this track is never asked about again.
     */
    suspend fun lookAgain(mediaId: String, playingDurationSec: Int?): SourceStream? {
        val waiting = pending[mediaId] ?: return null
        var found: SourceStream? = null
        return try {
            // The lookup that was still running when the fallback won the race
            // gets first refusal: what it returns is the stream that would
            // have played with no seam at all had it been a few seconds
            // quicker.
            //
            // It is not taken on trust, though, and the reason is the whole
            // difference between where it was going to be used and where it is
            // used now. The live path's match is made against a runtime
            // *claimed* by whoever queued the track, and where nothing in the
            // results agrees with that runtime, [SourceResolver.preferred]
            // lets the title and artist decide alone — correctly, for picking
            // what to play from the start. Cutting into a track already
            // playing is a stricter question, and it gets the stricter test
            // that [SourceResolver.upgradeFor] applies to its own candidates,
            // against the length the decoder is reporting. Measured here, the
            // difference was a 189-second cut swapped into a 163-second song,
            // which played for five seconds of silence and was then put back.
            waiting.inFlight?.let { lookup ->
                val late = runCatching { lookup.await() }.getOrNull()
                if (late != null &&
                    SourceResolver.worthSwapping(late.format, waiting.playing) &&
                    SourceResolver.sameRecordingAs(late.durationSec, playingDurationSec)
                ) {
                    found = late
                    return late
                }
            }
            // It finished with nothing better, so the question gets asked
            // again from scratch — this time waiting on every module, which is
            // what the live path could not afford to do.
            SourceResolver.upgradeFor(
                waiting.target.copy(durationSec = playingDurationSec ?: waiting.target.durationSec),
                playing = waiting.playing,
            ).also { found = it }
        } finally {
            // Whatever the answer, the question has now been asked. Leaving it
            // pending would re-run the whole search on every pause and resume.
            pending.remove(mediaId)
            // Only a *no* ends the race here. A yes leaves the badge up for
            // the caller to close out when the swap it describes has actually
            // happened — see this function's own documentation.
            if (found == null) NerdStats.onLosslessRaceEnd(mediaId)
        }
    }

    /** Abandons the second look for [mediaId] — the queue has moved on. */
    fun forget(mediaId: String) {
        pending.remove(mediaId)?.inFlight?.cancel()
        forced.remove(mediaId)
        NerdStats.onLosslessRaceEnd(mediaId)
    }

    // ── Handing the stream to the player ────────────────────────────────────

    /** Parks [stream] for [mediaId], to be picked up when the item is reopened. */
    fun force(mediaId: String, stream: SourceStream) {
        forced[mediaId] = stream
    }

    /**
     * The upgraded stream for a request carrying the [MARKER], or null.
     *
     * Read rather than consumed: ExoPlayer reopens a source more than once
     * over a track's life — a seek past the buffer, a resumed playback, a
     * cache miss — and each of those has to arrive at the same bytes.
     */
    fun forcedStream(uri: Uri): SourceStream? {
        if (uri.getQueryParameter(MARKER) != UPGRADED) return null
        return uri.getQueryParameter("v")?.let(forced::get)
    }

    /** The same URI, marked so that Media3 rebuilds the source and the cache keys it apart. */
    fun upgradedUri(uri: String): String = "$uri&$MARKER=$UPGRADED"

    /**
     * The suffix that keeps an upgraded track's bytes off the copy it
     * replaced — see [AudioCache]'s key factory for why sharing one entry
     * between two renditions corrupts both.
     */
    fun cacheTag(uri: Uri): String? = uri.getQueryParameter(MARKER)
}
