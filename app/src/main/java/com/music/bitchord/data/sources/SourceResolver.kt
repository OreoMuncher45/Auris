package com.music.bitchord.data.sources

import android.net.Uri
import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException

/**
 * Turns a queued track into an openable stream, using whichever source can
 * best serve it.
 *
 * Two things happen here that don't happen in any single [MusicSource]:
 *
 *  1. **The quality question is answered once**, from the connection in hand
 *     and the user's ceiling for it — see [requestForNow]. Sources are told
 *     what to serve; they don't each re-derive it.
 *
 *  2. **The order is applied.** A track is pinned to the source that produced
 *     it, but a pin is a starting point, not a cage: with lossless on, a
 *     higher-priority source that can serve the same recording bit-exact gets
 *     asked first, and a source that fails gets stepped over rather than
 *     failing the track.
 *
 * Whether another source *has* the same recording is [TrackMatcher]'s question,
 * not this one's. Everything here does with a candidate list is ask that, and
 * everything a source is asked for comes from the same place — so the library,
 * a playlist, radio, search and the home feed all substitute on identical
 * terms, whichever of them a track was queued from.
 */
object SourceResolver {

    private const val TAG = "BitChord"

    /**
     * What to ask a source for, right now.
     *
     * The lossless switch is a preference, not an override — it loses to the
     * connection's own ceiling, which is the setting someone reached for
     * specifically to protect a data plan. A capped connection gets a capped
     * transcode whether or not lossless is on, because the alternative is a
     * switch in one part of Settings quietly undoing a switch in another, and
     * the one being undone is the one attached to a bill.
     */
    fun requestForNow(): StreamRequest {
        val ceiling = AppSettings.effectiveAudioQuality
        return when {
            ceiling != AudioQuality.HIGH -> StreamRequest.Capped(ceiling.maxKbps)
            AppSettings.losslessAudio.value -> StreamRequest.Lossless
            else -> StreamRequest.Best
        }
    }

    /**
     * @param uri a `bitchord://source?...` URI as built by [SourceRegistry.trackUri].
     * @return the stream, or null when nothing enabled could serve the track.
     */
    suspend fun resolve(uri: Uri): SourceStream? {
        val configId = uri.getQueryParameter("s") ?: return null
        val trackId = uri.getQueryParameter("t") ?: return null
        return resolve(
            configId = configId,
            trackId = trackId,
            target = targetIn(uri),
        )
    }

    /**
     * The recording a playback URI describes, for matching it elsewhere.
     *
     * Title, artist and runtime ride in the URI because they are what a
     * cross-source match is made on, and the resolver runs on ExoPlayer's
     * loader thread with nothing but a DataSpec in hand — see
     * [toMediaItem][com.music.bitchord.playback.toMediaItem].
     */
    fun targetIn(uri: Uri) = TrackMatcher.Target(
        title = uri.getQueryParameter("n").orEmpty(),
        artist = uri.getQueryParameter("a").orEmpty(),
        durationSec = uri.getQueryParameter("d")?.toIntOrNull(),
    )

    /**
     * @param target is what a cross-source match is made on. Without it the
     *   only possible behaviour is "the pinned source or nothing", which is
     *   still a correct outcome — just a worse one.
     */
    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
    ): SourceStream? {
        val request = requestForNow()
        val pinned = SourceRegistry.instance(configId)
        val active = SourceRegistry.active()

        // The upgrade path: with lossless asked for and the pinned source
        // unable to serve it, anything ranked above it that can is worth
        // asking first. This is the whole reason the list is ordered — it is
        // what makes "my own FLAC of this, if I have one, else stream it"
        // expressible.
        if (request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStream(source, target, request) ?: continue
                Log.d(TAG, "lossless upgrade: '${target.title}' served by ${source.displayName}")
                return upgraded
            }
        }

        if (pinned != null) {
            attempt(pinned) { pinned.stream(trackId, request) }?.let { return it }
        }

        // Last resort. A track whose own source is down is still a track the
        // user asked for, and another source having it is not unlikely — this
        // is the difference between a dead server skipping the queue forward
        // and a dead server being invisible.
        for (source in active) {
            if (source.configId == configId) continue
            matchAndStream(source, target, request)?.let {
                Log.d(TAG, "fallback: '${target.title}' served by ${source.displayName}")
                return it
            }
        }
        return null
    }

    /**
     * The stream for a YouTube track from a source the user ranked above
     * YouTube, or null when none of them has the recording.
     *
     * A YouTube track keeps its bare video id rather than a
     * [SourceRegistry.trackKey] — see [YouTubeSource] for why — so it reaches
     * playback as `bitchord://watch?v=…` and never passes through [resolve].
     * Without this, ordering a source above YouTube did nothing for anything
     * *queued* from YouTube: the library, a playlist, radio, the home feed —
     * which is very nearly everything. The list said "prefer my server" and
     * only search results honoured it.
     *
     * The match is the same strict one [resolve] uses, for the same reason:
     * this substitutes something else for the track the user picked, and a
     * loose match plays the wrong song under the right title.
     */
    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank()) return null
        val active = SourceRegistry.active()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val request = requestForNow()
        for (source in rankedAbove(youtube.configId, active)) {
            val stream = matchAndStream(source, target, request) ?: continue
            Log.d(TAG, "substituted: '${target.title}' served by ${source.displayName} over YouTube")
            return stream
        }
        return null
    }

    /**
     * Whether anything outranks YouTube right now — i.e. whether a YouTube
     * track is worth offering around before it is resolved.
     *
     * Answerable from the source list alone, without a search, which is what
     * lets the cache and the read-ahead in
     * [AudioCache][com.music.bitchord.playback.AudioCache] decide how to treat
     * a YouTube id before anyone has asked a source for it.
     */
    fun canSubstituteForYouTube(): Boolean =
        SourceRegistry.active().indexOfFirst { it.kind == SourceKind.YOUTUBE } > 0

    /**
     * The sources ranked above [configId], in order.
     *
     * A config that isn't in [active] ranks last: it is disabled or incomplete,
     * and everything that *is* enabled is worth trying ahead of it.
     */
    private fun rankedAbove(configId: String, active: List<MusicSource>): List<MusicSource> =
        active.indexOfFirst { it.configId == configId }
            .let { if (it < 0) active.size else it }
            .let { active.take(it) }

    /**
     * Searches [source] for the recording in [target] and streams it if one of
     * the answers really is that recording — see [TrackMatcher].
     *
     * Each query the matcher offers is tried in turn, because the first one
     * failing is usually the catalogue disagreeing about how a track is
     * *filed*, not about whether it holds it. Stopping at the first empty
     * answer is what made a source look like it was missing half of what it
     * had. A source that *throws* still gets no second chance: that is its
     * server having a problem, and asking it again differently won't fix it.
     */
    private suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
    ): SourceStream? {
        for (query in TrackMatcher.queries(target)) {
            val candidates = attempt(source) { source.search(query, limit = MATCH_CANDIDATES) }
                ?: return null
            val matches = TrackMatcher.ranked(candidates, target)
            if (matches.isEmpty()) continue
            return streamBest(source, matches, request)
        }
        return null
    }

    /**
     * Opens the best of [matches] that can actually serve [request].
     *
     * Two things happen here that a single "take the top match" cannot:
     *
     *  1. **Rows that advertise the tier asked for go first.** Every one of
     *     these is genuinely the recording, so which one plays is a question
     *     about quality, not identity — and a catalogue that has already said
     *     it holds a FLAC is a better place to ask for one than a catalogue
     *     that said nothing. Without this the order was confidence alone, and
     *     a 16-bit FLAC lost to a Deezer row over how its artists were spelt.
     *
     *  2. **What comes back is checked against what was asked for.** A module
     *     that cannot serve lossless does not always say so; some quietly walk
     *     their own fallback chain and hand back a 128kbps MP3 with the right
     *     title on it. Reading [StreamFormat] before accepting the URL is what
     *     turns that into "this one can't, try the next" instead of into the
     *     listener's evening.
     *
     * The under-quality stream is kept rather than dropped: if nothing better
     * exists anywhere, playing the MP3 is still better than skipping the
     * track. It is a floor, not a first choice.
     */
    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        request: StreamRequest,
    ): SourceStream? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = if (wantsLossless) {
            matches.sortedByDescending { it.sourceQuality == ModuleSource.LOSSLESS }
        } else {
            matches
        }
        var settleFor: SourceStream? = null
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val stream = attempt(source) { source.stream(trackId, request) } ?: continue
            val served = stream.format
            if (!wantsLossless || served.isLossless == true || served.statesNothingLossy) {
                Log.d(
                    TAG,
                    "${source.displayName} matched '${match.title}' by '${match.artist}' → ${served.summary}",
                )
                return stream
            }
            Log.d(TAG, "${source.displayName} offered ${served.summary} for '${match.title}'; looking further")
            settleFor = settleFor ?: stream
        }
        return settleFor
    }

    /**
     * Whether a format has said nothing that rules lossless out.
     *
     * Unknown is not the same as lossy, and a source that reports neither a
     * codec nor a bitrate has not failed the request — it has declined to
     * describe it, and the decoder will say soon enough. A stated bitrate is
     * different: nothing states a bitrate for a FLAC.
     */
    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    /**
     * Runs [block], turning any failure into null and a log line.
     *
     * Every call into a source is a call to somebody else's server, and a
     * source that throws must cost the *source* its turn, not the track its
     * playback. Cancellation is re-thrown: that is the caller giving up, and
     * swallowing it would keep walking sources for a track nobody is waiting
     * for any more.
     */
    private suspend fun <T> attempt(source: MusicSource, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * How many answers per query are worth weighing.
     *
     * Wider than it needs to be for a well-behaved catalogue, because
     * [TrackMatcher.best] scores the whole list rather than taking the first
     * acceptable row: a backend that ranks the karaoke version, three covers
     * and a sped-up edit above the album cut still has the album cut in here
     * somewhere, and the extra rows cost one response body, not one request.
     */
    private const val MATCH_CANDIDATES = 15

    /**
     * How many of the matching rows are worth actually opening.
     *
     * Each one is a round trip to a stream endpoint, so this is the budget for
     * "the first copy wasn't the quality asked for" — enough to get past a
     * module whose lossless backend is down, not enough to spend a listener's
     * patience walking a whole result list.
     */
    private const val STREAM_ATTEMPTS = 3
}
