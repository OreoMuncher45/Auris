/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.playback.AudioCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import java.util.Locale

/**
 * The least decoded audio a head-only pass is allowed to measure a tempo from.
 *
 * Twelve seconds is around 24 beats at 120 bpm — enough for the grid's own
 * confidence measure to mean something, and not enough for the estimator to be
 * reading the tail of a fade-in.
 *
 * Public because the cache fetches *for* this number. The read-ahead of a
 * source-backed track sizes its opening from it, and the analyser's own gate
 * measures against it; the two have to agree, or the read-ahead fetches a
 * megabyte that is one second of a lossless rendition while the analyser waits
 * for eleven more. See `AudioCache.analysisOpeningBytes`.
 */
const val ANALYSIS_HEAD_SECONDS = 12.0

/**
 * How many seconds of a track's **ending** a tail-only pass measures.
 *
 * The outgoing half of a transition reads the track's exit — its mix-out anchor,
 * its content end, and the envelope over its last seconds — and none of that is
 * inside [ANALYSIS_HEAD_SECONDS]'s window. Matching [BeatTracker.WINDOW_SECONDS]
 * would cost one inference; this is deliberately a little longer, because the
 * panel's viewport is the transition window plus the music either side of it
 * (`MIN_VIEW_SECONDS`, 44 s), and a lane that stops two thirds of the way
 * across reads as a broken lane rather than as a partial one.
 *
 * Public because the cache fetches *for* this number, exactly as it does for
 * [ANALYSIS_HEAD_SECONDS]: the analizer's gate and the fetch have to agree, or
 * the fetch pulls a window the pass then refuses as too short.
 */
const val ANALYSIS_TAIL_SECONDS = 30.0

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it
 * to [TransitionPlanner].
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never
 * blocks or computes: it returns what is already known, and an unanalysed
 * track simply reads as no evidence, which the policy ladder answers with a
 * plain fade.
 */
@UnstableApi
class TrackAnalyzer(private val context: Context, private val cache: AudioCache) {

    private val tracker = BeatTracker(
        modelPath = { unpackedAsset(BeatTracker.MODEL_ASSET) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
    )
    private val vocals = VocalTracker(
        modelPath = { unpackedAsset(VocalTracker.MODEL_ASSET) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
    )

    /**
     * A model's weights on disk, unpacked from assets the first time they are
     * asked for.
     *
     * ONNX Runtime wants a file it can memory-map, and an asset inside the APK
     * is not one. This used to live inside each tracker; it moved out when they
     * became shared with the desktop build, which unpacks from the jar instead.
     */
    private fun unpackedAsset(name: String): String {
        val file = java.io.File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    /**
     * Whether analysis is switched off entirely right now.
     *
     * A party never mixes — [com.music.bitchord.playback.CrossfadeController]
     * refuses to plan a transition while one is running, because every member
     * would start the next track at a moment their own copy of the audio
     * decided on. Measuring for a transition that cannot happen is then pure
     * cost, and not a small one: a whole-track decode, two ONNX passes, and for
     * an uncached track a megabyte fetched over the same connection the party's
     * own sync is sharing.
     *
     * Read fresh at every entry point and between the stages of a pass in
     * flight, rather than latched when the party starts, so joining one stops
     * the work already running instead of only the work not yet queued.
     */
    private val stopped: Boolean get() = ListenTogether.state.value.inParty

    /**
     * Resolved on first use, not at construction, for the reason [AnalysisStore]
     * documents about its own directory: this class is a field initializer on
     * the playback service, which runs before the service has a base context
     * attached, and asking a context for anything there returns null.
     */
    private val resolver by lazy { context.contentResolver }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** The last thermal status the system reported; see [overheated]. */
    @Volatile private var thermalStatus = 0

    /**
     * Registered on first use rather than at construction, for the reason
     * [resolver] gives: there is no base context to ask for a service yet.
     */
    private val thermalWatch by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.let { power ->
                thermalStatus = power.currentThermalStatus
                power.addThermalStatusListener(Runnable::run) { thermalStatus = it }
            }
        }
    }

    /**
     * Whether the phone has asked apps to shed load. No new pass is started
     * while it has: a pass is tens of seconds of decoding and inference, and
     * started on a phone already throttled to half its clocks it is the load
     * that keeps it there — measured, a session of back-to-back analyses held
     * a phone at SEVERE with every frame of the UI missing its vsync. A track
     * left unmeasured gets a plain crossfade, and is measured on a later tick
     * once the phone has cooled. A pass already running finishes.
     */
    private val overheated: Boolean
        get() {
            thermalWatch
            return thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }

    /** Tracks whose result came from [analyzeHead] and is waiting to be superseded. */
    private val provisional = ConcurrentHashMap.newKeySet<String>()

    /**
     * The partial halves of a track's analysis, kept apart as each pass lands.
     *
     * A streamed track is measured in pieces: its opening from [analyzeHead],
     * its ending from [analyzeTail], and the whole of it only once every byte
     * the track will ever have is on disk — which for a track still playing is
     * its own play time away. The panel is drawn from these pieces, so both are
     * kept and re-merged rather than one publishing over the other.
     *
     * Kept *apart* rather than as one growing merge, because the merge has an
     * order to it: the head pass's numbers are the ones the policy's entry
     * evidence comes from, and a tail window's tempo is measured over the least
     * representative music in the track. Storing the merged result and merging
     * into *that* would make whichever pass finished last the authority on the
     * tempo — the tail, half the time.
     */
    private val partials = ConcurrentHashMap<String, Partial>()

    /** One track's two partial answers. Null until that half has been measured. */
    private data class Partial(val head: TrackAnalysis? = null, val tail: TrackAnalysis? = null) {
        /** The pair as one analysis, or null when neither half has landed. */
        fun merged(): TrackAnalysis? {
            val opening = head
            val ending = tail
            return when {
                opening == null -> ending
                ending == null -> opening
                else -> opening.mergedWith(ending)
            }
        }
    }

    /**
     * Tracks whose result is a **whole-track** answer: either restored from
     * [store] or produced by [analyze].
     *
     * A partial pass must never publish over one of these. The two run
     * concurrently — a head or tail pass queued a tick before the whole-track
     * one landed is exactly the ordering that was observed — and a merged
     * half-measurement replacing a complete one would take the mix-out anchor
     * and the content end away from a transition that had them.
     */
    private val wholeResults = ConcurrentHashMap.newKeySet<String>()

    /** Tracks with a tail pass in flight; see [analyzeTail]. */
    private val tailsRunning = ConcurrentHashMap.newKeySet<String>()

    /**
     * Tail bytes on disk at the last tail attempt, per *rendition*.
     *
     * The same retry-on-growth shape as [headAttempts] and for the same reason:
     * one attempt per track would spend it on a window that arrived
     * half-written, and one per tick would re-decode the same short window four
     * times a second. Keyed by rendition because a track can move between
     * copies, and "how much of this file's ending is here" belongs to the file.
     */
    private val tailAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Cached prefix size, in bytes, at the last head attempt on each
     * *rendition*. See [headWorthTrying].
     *
     * Keyed by rendition rather than by track because the growth guard is
     * asking "have I already decoded roughly this much of this copy", and a
     * track can move between copies: a first attempt on a half-cached lossless
     * rendition recorded six megabytes, and a much lighter Opus head arriving
     * afterwards — the one that would actually have produced a result — was then
     * refused for being smaller than a number belonging to a different file.
     */
    private val headAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Track-and-rendition pairs that have already reported waiting for a head,
     * so the tick doesn't spam. Not keyed by track alone: a track that gains a
     * second copy of itself is in a genuinely new situation, and the first
     * version of this hid exactly that.
     */
    private val headSkipLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Tail-and-rendition pairs that have already reported waiting for an ending,
     * for the same reason as [headSkipLogged].
     */
    private val tailSkipLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each track's whole-track pass has been refused for
     * decoding short. Counted rather than flagged so a rendition still filling
     * in its holes gets a few more chances, while one that is genuinely
     * truncated stops being re-decoded on every tick.
     */
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore { context.filesDir }

    /**
     * Cache keys of renditions that decoded short despite the cache calling them
     * complete. Keyed by rendition rather than by track, because the point is to
     * send the next attempt at the *same* track to a different copy of it.
     */
    private val badRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cache keys already put through the whole-track pass, whatever came of it.
     *
     * What reopens a track that was written off: a copy of it nobody has read
     * yet. Without this the write-off is final for the session, and a rendition
     * that arrives seconds later — a quality upgrade, or the head fetch the
     * analyzer itself asked for — is never looked at. Measured, a track was
     * given up on at 22:09:24 and its `#hifi` copy finished downloading at
     * 22:09:41.
     */
    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each rendition has been thrown off disk for being
     * undecodable, so a clean slate stays a one-off.
     *
     * The refetch that follows a discard is not guaranteed to be any better —
     * a source can serve the same broken file twice — and without a bound the
     * two halves feed each other: refuse, delete, refetch, refuse, delete, on a
     * 250ms tick, spending the listener's data in a loop. One clean-slate retry
     * is enough for the case this exists for, which is an entry spliced from two
     * different encodings and unrecoverable only because nothing would ever
     * overwrite it.
     */
    private val discarded = ConcurrentHashMap<String, Int>()

    private fun discardsOf(key: String): Int = discarded[key] ?: 0

    /**
     * The urgent lane: stored results and head passes.
     *
     * These are what a transition is waiting for — the tempo of the track
     * that is coming, and the numbers a previous session already worked out —
     * so they never queue behind anything long.
     */
    private val fastExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis-fast").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * The patient lane: whole-track passes.
     *
     * A whole-track pass decodes the entire file and runs two models over it,
     * which takes tens of seconds — and it cannot finish before the track has
     * been downloaded to its last byte, which for a streamed track means after
     * it has already played. So it describes the track's *outro* for the next
     * time round, and it must never stand between the listener and the
     * analysis of the track that is about to start.
     *
     * That was the shape of the old single lane, and it is why Automix felt
     * slow however many threads the performance setting gave it: every track
     * that finished queued a full-length decode, and the next track's beat
     * grid waited behind it. More threads only ever added more full-length
     * decodes to the queue.
     */
    private val wholeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis-whole").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished, which
     * [assessTransitionTier] reads as no evidence rather than as a failure.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /**
     * Looks [trackId] up on disk, once, off the playback thread.
     *
     * Deliberately not folded into [analysisFor], which is called several times
     * per tick from the playback thread and must never touch the filesystem.
     * The result lands in [results] a tick or two later, which is immaterial
     * against the seconds a real analysis takes — and against the alternative,
     * which is not having it at all.
     */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        // The set doubles as the once-guard: add() is true only for the first
        // caller, so a track with nothing stored is looked for once per session
        // rather than on every tick.
        if (!restoreAttempted.add(trackId)) return
        // The urgent lane: a stored result is the fastest answer there is, and
        // it must not wait behind a decode.
        fastExecutor.execute {
            val stored = store.load(trackId) ?: return@execute
            Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
            // Marked whole before it is published, so a partial pass running
            // beside this one cannot merge over it once it lands — see
            // [wholeResults].
            wholeResults.add(trackId)
            results.putIfAbsent(trackId, stored)
        }
    }

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * Whether [trackId]'s current result came from the track's opening only.
     *
     * A head pass measures the first part of a track and knows nothing of its
     * outro, so its numbers are good enough to plan a transition and not good
     * enough to be described as the track's analysis. The Automix panel says
     * so rather than showing a partial answer as a complete one.
     */
    fun isProvisional(trackId: String): Boolean = trackId in provisional

    /**
     * True while a decode and inference for [trackId] is actually in flight.
     *
     * Distinct from "not analysed": a track waiting on bytes and a track being
     * worked on right now are the same absence of a result, and the difference
     * is the difference between something being wrong and something simply
     * taking the several seconds it takes.
     */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight. Cheap to call repeatedly; callers re-request as
     * caching progresses.
     *
     * Runs in up to two passes, because waiting for a full cache is what kept
     * the *incoming* track of every transition unanalysed. A track only
     * finishes downloading once it is already playing, so the whole-track pass
     * lands in time to describe a track's own mix-out and never in time to
     * describe its entry — which is the half the listener hears at the moment
     * of the blend.
     *
     * So a track with enough of a head on disk gets [analyzeHead] first: beat
     * grid only, over the opening window, which is all the incoming side is
     * read for. That result is provisional and is replaced by the whole-track
     * [analyze] as soon as the remaining bytes arrive.
     *
     * None of that applies to a track playing off the device, which goes
     * straight to [analyze] on the first tick that reaches it — there is nothing
     * to wait for and nothing to escalate through. See [LocalAudioSource] for
     * why that needed saying at all.
     */
    fun request(trackId: String, uri: Uri, durationSeconds: Double, outgoing: Boolean = false) {
        if (trackId.isBlank()) return
        // Before anything else this would spend: [analysisUriFor] alone can put
        // a catalogue match on the network for a track the party will never
        // mix. See [stopped].
        if (stopped) return
        if (trackId in running) return
        // A track measured in an earlier session costs nothing to restore, so
        // only the work below waits for the phone to cool.
        if (overheated) {
            restoreOnce(trackId)
            return
        }
        val analysisUri = analysisUriFor(trackId, uri)

        // Any complete copy of this recording will do, not only the one being
        // played: [chooseRendition] takes the lightest and checks it is the
        // same cut. The copy being played is where the search starts, because
        // that is the recording the evidence has to describe — and it is what
        // the whole cache is keyed by, so every sibling rendition of it comes
        // back with the same question.
        // Queued before the cache is even consulted, so a track measured in an
        // earlier session short-circuits the whole path rather than being
        // re-earned from audio the cache may since have evicted.
        restoreOnce(trackId)

        // A track playing off the device is not a download in progress. Every
        // byte of it is already there, and none of those bytes are in the cache:
        // local URIs are routed past it rather than written into it a second
        // time. So everything below answers "nothing on disk" for a track that
        // is entirely on disk, and the head fetch that answer falls back to is a
        // no-op for anything without a YouTube id — which is why a local file
        // was never queued for analysis at all. See [LocalAudioSource].
        val local = LocalAudioSource.isLocal(analysisUri)

        // Complete *and* not already written off. A copy that decoded to
        // nothing is not a copy worth routing to: counting it as "fully cached"
        // sent this down the whole-track path on every tick with nothing left
        // for that path to read, and each of those empty passes was then
        // counted as a failed decode.
        //
        // Not asked of a local file, whose bytes the cache has never seen and
        // whose completeness is not a question: it is whole from the first tick.
        val complete = if (local) {
            emptyList()
        } else {
            cache.renditionsOf(analysisUri)
                .filter { it.isAnalysable(durationSeconds) && it.key !in badRenditions }
        }
        val usableComplete = local || complete.isNotEmpty()

        // The outgoing half of the pair, measured from its *end*.
        //
        // A track the listener is listening to is the one the panel draws down
        // to its own last seconds and the one the planner mixes out of, and for
        // a streamed track none of that music is on disk yet: every other writer
        // works forwards from byte zero, so the end of a stream arrives when
        // playback reaches it and not before. The whole-track pass covers the
        // same ground and needs the entire file; this needs a window.
        //
        // Asked for here rather than when the transition is due, because that is
        // the moment there is time to fetch it: the track's own play time.
        // Skipped for a track on the device and for one already whole on disk,
        // where the whole-track pass either has everything or is about to.
        if (outgoing && !local && !usableComplete) {
            val tailRendition = tailWorthTrying(analysisUri, durationSeconds)
            if (tailRendition == null) {
                // Nothing to read yet, so ask for the bytes. The cache guards
                // this to one round per track and re-arms it when a round comes
                // back empty, so a tick every 250 ms costs a set lookup.
                cache.requestAnalysisTail(analysisUri, durationSeconds)
            } else if (tailsRunning.add(trackId)) {
                fastExecutor.execute {
                    try {
                        if (stopped) return@execute
                        // A whole-track answer may have landed while this sat in
                        // the queue, and it covers the ending by definition.
                        if (trackId in wholeResults) return@execute
                        Process.setThreadPriority(
                            if (AppSettings.automixPerformanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                                Process.THREAD_PRIORITY_BACKGROUND
                            } else {
                                Process.THREAD_PRIORITY_DEFAULT
                            },
                        )
                        analyzeTail(trackId, analysisUri, durationSeconds, tailRendition)
                            ?.let { publishPartial(trackId, it, fromHead = false) }
                    } catch (error: Throwable) {
                        Log.w(TAG, "Tail analysis of $trackId failed", error)
                    } finally {
                        tailsRunning.remove(trackId)
                    }
                }
            }
        }

        val recorded = results[trackId]
        // Two things are worth superseding, and nothing else is. A provisional
        // head result, because replacing it with the whole-track pass is the
        // entire point of it — and a recorded failure, but only once a copy of
        // the track nobody has read yet turns up. Re-deciding a failure against
        // the same renditions that produced it would just spend the decode
        // again for the same answer.
        //
        // A local file is its own single copy, keyed by URI — see [copyToRead] —
        // so a failure there is read once and stays read, which is correct: no
        // second copy of it is ever going to arrive.
        val untried = if (local) {
            uri.toString() !in triedRenditions
        } else {
            complete.any { it.key !in triedRenditions }
        }
        val supersedable = when {
            recorded == null -> false
            trackId in provisional -> usableComplete
            else -> !recorded.isUsable && untried
        }
        if (recorded != null && !supersedable) {
            // One exception to returning empty-handed. A provisional result is a
            // placeholder, not an answer — it says the opening decoded, not that
            // the track is measured — and the thing that supersedes it is bytes.
            // Without this nudge the byte escalation stops at whatever the first
            // successful head happened to cost, nothing else ever asks for the
            // rest, and a queued track reaches its own transition carrying an
            // entry-only estimate: no content end, no mix-out anchor, no vocal
            // mask, which is most of what the outgoing half of a blend reads.
            if (trackId in provisional && !usableComplete) {
                cache.requestAnalysisHead(analysisUri, durationSeconds)
            }
            return
        }
        // The strike count belongs to the attempt that was given up on, not to
        // the track for the rest of the session; a reopened track starts level.
        if (recorded != null && !recorded.isUsable) shortDecodes.remove(trackId)
        // A head pass is worth running whenever there is no answer yet, and
        // *whether or not the whole file is already on disk*.
        //
        // That second half is not a detail, it is the difference between an
        // answer in seconds and one in minutes. The whole-track pass decodes
        // the entire recording and runs two models over it, and the forced
        // round the Automix panel asks for pulls the track's bytes up front —
        // which routinely means the cache entry is *complete* by the time
        // anybody looks. Completeness used to be the test that sent a track
        // straight down the whole-track lane, so opening the panel on a track
        // whose bytes had just been fetched skipped the head pass entirely and
        // waited for the full analysis: minutes on a phone, on the very screen
        // the listener had opened to watch it.
        //
        // The head pass costs a window of audio and one model, and what it
        // publishes is a provisional result — the BPM, the grid and the
        // opening's own envelope, which is what the panel draws and what the
        // incoming side of a transition reads. The whole-track pass replaces
        // it on a later tick, from the same bytes, with no fetch left to wait
        // for.
        //
        // One attempt per track per growth step: a partial container that will
        // not parse now is unlikely to parse ten ticks later, and retrying a
        // decode every 250 ms would cost more than the analysis it is trying
        // to bring forward. Skipped for a local file, which is `usableComplete`
        // from the first tick — the head pass exists to get ahead of a
        // download, and there is no download to get ahead of.
        val headRendition = headWorthTrying(trackId, analysisUri, durationSeconds)
        if (!usableComplete && headRendition == null) {
            // Nothing on disk worth decoding, so ask for something. Every other
            // writer either fetches this track's opening too late to matter or
            // never fetches it at all — see [AudioCache.requestAnalysisHead],
            // which is a no-op after the first call and, until it learned how,
            // for anything that isn't a YouTube-backed track.
            //
            // The duration travels with the ask because the length of the
            // opening is a question about seconds: the cache cannot turn a
            // byte count into a meaningful one without knowing how long the
            // track is, and this is the only place in the app that knows.
            cache.requestAnalysisHead(analysisUri, durationSeconds)
            return
        }
        if (!running.add(trackId)) return
        // Which pass this is, and so which lane it belongs to: the urgent one
        // for a head pass, the patient one for a whole-track pass. The two
        // never share a queue, so the track that is coming is measured while
        // the track that just finished is still being measured properly.
        //
        // A *provisional* result is deliberately not a reason to run the head
        // pass again: it is the head pass's own answer, the lane it came from
        // is single-threaded, and asking for the same window twice would turn
        // the escalation to the whole-track pass into a loop that never
        // reaches it. What a provisional result wants is bytes, and that is
        // asked for above.
        val headOnly = headRendition != null && recorded?.isUsable != true
        val lane = if (headOnly) fastExecutor else wholeExecutor

        lane.execute {
            try {
                // Efficient mode yields to decoding and playback rather than
                // competing for a core. Thread count remains the speed knob.
                Process.setThreadPriority(
                    if (AppSettings.automixPerformanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                        Process.THREAD_PRIORITY_BACKGROUND
                    } else {
                        Process.THREAD_PRIORITY_DEFAULT
                    },
                )
                // A stored result for this track has landed by now if there was
                // one and the restore ran ahead of this task — but the decision
                // to get here was taken a tick earlier, when it had not.
                // Without this check a track measured in an earlier session is
                // restored and then immediately spends seven seconds
                // recomputing the identical numbers. A provisional result is
                // exempt: superseding one is the whole point of it.
                val landed = results[trackId]
                if (landed != null && landed.isUsable && trackId !in provisional) return@execute
                // The lane this task sat in is single-threaded, so a party
                // started while the task ahead of it was being measured. Every
                // task behind that one turns into this return, which drains the
                // backlog without needing to reach into the executor's queue.
                if (stopped) return@execute
                if (headOnly) {
                    // Marked before it is published, so a reader on the playback
                    // thread can never see a provisional result that is not
                    // flagged as one. Merged rather than assigned: the tail pass
                    // may already have published the track's ending, and the two
                    // measure different halves of it — see [partials].
                    analyzeHead(trackId, analysisUri, durationSeconds, headRendition!!)?.let { head ->
                        publishPartial(trackId, head, fromHead = true)
                    }
                } else {
                    val outcome = analyze(trackId, analysisUri, durationSeconds)
                    val measured = outcome.analysis
                    if (measured != null) {
                        results[trackId] = measured
                        provisional.remove(trackId)
                        // The partial halves are spent: they were the answer
                        // until this one arrived, and merging them into it would
                        // put a window of the outro back over the whole track's
                        // own numbers.
                        wholeResults.add(trackId)
                        partials.remove(trackId)
                        shortDecodes.remove(trackId)
                        // Only the whole-track pass is persisted. A head result
                        // is missing everything past its window — the outro, the
                        // mix-out anchor, the energy curve — and storing one
                        // would freeze a deliberately partial answer in place of
                        // the complete one that supersedes it minutes later.
                        store.save(trackId, measured)
                        restoreAttempted.add(trackId)
                    } else if (outcome.decodedShort &&
                        shortDecodes.merge(trackId, 1, Int::plus)!! >= MAX_SHORT_DECODE_ATTEMPTS
                    ) {
                        // Bounded, so a container that is genuinely truncated
                        // isn't re-decoded on every tick for the rest of the
                        // session. Any provisional head result already published
                        // stays: a partial analysis beats an empty one.
                        Log.w(TAG, "Giving up on $trackId after $MAX_SHORT_DECODE_ATTEMPTS short decodes")
                        if (trackId !in provisional) {
                            results[trackId] = TrackAnalysis(
                                status = TrackAnalysis.STATUS_READY,
                                trackId = trackId,
                                duration = durationSeconds,
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                // Throwable, not Exception: decode leans on MediaCodec, and an
                // OOM or a codec-level Error uncaught on a pool thread that is
                // nobody's parent takes the whole app down for work whose
                // entire failure mode is meant to be "this track goes
                // unanalysed".
                Log.w(TAG, "Analysis of $trackId failed", error)
                // A failed head pass records nothing: the whole-track pass reads
                // a different, complete file and deserves its own attempt.
                // [headWorthTrying] has already made sure the head is not tried
                // twice, so this cannot spin.
                if (!headOnly) {
                    // Recorded as ready-but-empty so a track that cannot be
                    // analysed is not retried on every tick for the rest of the
                    // session.
                    results[trackId] = TrackAnalysis(
                        status = TrackAnalysis.STATUS_READY,
                        trackId = trackId,
                        duration = durationSeconds,
                    )
                    provisional.remove(trackId)
                }
            } finally {
                running.remove(trackId)
                // The session holds the model's arena and a parsed ONNX graph in native heap for
                // as long as it is open, which a backgrounded music player cannot justify between
                // transitions. Released the moment nothing is in flight; reloading costs under a
                // second against an analysis that already takes several.
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    /**
     * Asks for [trackId]'s analysis *now*, at the listener's request rather
     * than the tick's.
     *
     * The ordinary [request] is a poll: it notices what is on disk and asks
     * for what is missing, and every guard in it is a guard against spending
     * the same work twice on a 250 ms tick. That is the right shape for work
     * that runs in the background and the wrong shape for the Automix panel
     * opening, which is the one moment the listener is *looking* at the
     * answer and waiting for it.
     *
     * So this asks the cache for the bytes whatever its one-round guard says,
     * and then runs the ordinary path so that whatever is already on disk is
     * measured straight away rather than at the next tick. The two together
     * are what "at all costs" means here: the bytes go out even if a round
     * for this track is already in the air, and the decode happens the moment
     * there is something to decode.
     *
     * Cheap to call repeatedly — the listener may open and close the panel
     * several times — because the cache's own guards still stop a second
     * fetch of the same bytes, and the analyzer's guards still stop a second
     * decode of the same file.
     */
    fun force(trackId: String, uri: Uri, durationSeconds: Double, outgoing: Boolean = false) {
        if (trackId.isBlank()) return
        if (stopped) return
        // The bytes first: everything else is downstream of them, and a
        // decode of nothing is a decode of nothing.
        cache.requestAnalysisHead(uri, durationSeconds, force = true)
        // The outgoing track's lane is drawn over its ending, so that is the
        // window the listener is waiting to see measured — and its bytes are the
        // ones nothing else has fetched. See [AudioCache.requestAnalysisTail].
        if (outgoing) cache.requestAnalysisTail(uri, durationSeconds, force = true)
        // Then the ordinary path, so a track whose bytes are already on disk
        // is measured now rather than at the next tick.
        request(trackId, uri, durationSeconds, outgoing)
    }

    /**
     * Publishes one half of a track's analysis, merged with the half that has
     * already landed.
     *
     * A streamed track is measured in pieces and the panel is drawn from
     * whatever has arrived: the opening from a head pass, the ending from a tail
     * pass, and nothing at all in between until the whole file is on disk. Each
     * piece is merged into one result rather than replacing the last, because the
     * two are genuinely different evidence about the same track — and because a
     * reader that saw only the newer one would watch a track's tempo disappear
     * when its outro arrived.
     */
    private fun publishPartial(trackId: String, piece: TrackAnalysis, fromHead: Boolean) {
        // A whole-track answer always wins. It covers both halves, and the two
        // passes run concurrently — a partial queued a tick before the whole
        // one landed is the ordering that was actually observed.
        if (trackId in wholeResults) return
        val pair = partials.compute(trackId) { _, current ->
            if (fromHead) (current ?: Partial()).copy(head = piece)
            else (current ?: Partial()).copy(tail = piece)
        } ?: return
        val merged = pair.merged() ?: return
        provisional.add(trackId)
        results[trackId] = merged
    }

    /**
     * A rendition whose **ending** is on disk far enough to decode a tail window
     * from, claiming the attempt; null when nothing is there to read yet.
     *
     * The mirror of [headWorthTrying], with one deliberate difference: the
     * rendition does *not* have to be complete. That is the whole point of this
     * pass — the ending of a streamed track is the region that is not on disk
     * until the track has been played to its end, and requiring completeness is
     * what made the whole-track pass useless for it. What is required instead is
     * that the region the pass will actually decode is there, measured as
     * contiguous bytes from the tail's own start ([AudioCache.cachedTailBytes]),
     * which is what the decoder stops at.
     *
     * Retried on growth rather than once, like the head: a window that arrived
     * half-written is not a measurement, and one attempt per track would spend
     * itself on exactly that.
     */
    private fun tailWorthTrying(uri: Uri, durationSeconds: Double): AudioCache.Rendition? {
        // The window is addressed backwards from the stream's own length, so
        // without a duration there is no end to count back from.
        val seconds = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return null
        for (candidate in readableRenditions(uri)) {
            if (candidate.key in badRenditions) continue
            val total = candidate.contentLength
            if (total <= 0L) continue
            val want = cache.analysisTailBytes(total, seconds)
            if (want <= 0L) continue
            val held = cache.cachedTailBytes(candidate, want)
            if (held < want) {
                if (tailSkipLogged.add("${candidate.key}@$total")) {
                    Log.d(
                        TAG,
                        "Tail pass for ${candidate.key} waiting: ${held / 1024}kB of ${want / 1024}kB at the end",
                    )
                }
                continue
            }
            if (tailAttempts[candidate.key] == held) continue
            tailAttempts[candidate.key] = held
            return candidate
        }
        return null
    }

    /**
     * The track's **ending** only: the envelope over its last seconds, and the
     * measurements the outgoing half of a transition reads.
     *
     * The mirror of [analyzeHead], and it exists for the mirror-image reason. The
     * panel draws the outgoing track across the *end* of its own timeline and the
     * policy mixes out of that end — content end, outro, mix-out anchors. None of
     * those is inside the head pass's window, and for a streamed track nothing
     * else measures them until the whole file is on disk: the outgoing side of
     * every streamed transition used to be planned from no evidence at all, which
     * is a plain fade and a lane with nothing drawn in it.
     *
     * The window is [ANALYSIS_TAIL_SECONDS], read through the same cache copy the
     * whole-track pass reads — so a rendition that is still filling in is usable,
     * which is the case this exists for.
     *
     * Everything here is a *measurement within the window*, stated in the track's
     * own seconds. It says nothing about the middle of the track, which is why
     * the curve goes to [TrackAnalysis.tailEnergyCurve] and not to `energyCurve`:
     * the policy counts audible seconds from that one, and a curve covering only
     * an outro would describe a whole track from its last half-minute.
     */
    private fun analyzeTail(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        rendition: AudioCache.Rendition,
    ): TrackAnalysis? {
        val expected = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return null
        // How much of an ending there are actually bytes for.
        //
        // The fetch that fills this window is bounded
        // ([AudioCache.MAX_ANALYSIS_TAIL_BYTES]), so a rendition heavy enough to
        // overflow that bound is measured over the window its capped round
        // really covers rather than over one whose first megabytes are missing.
        // That distinction matters: a region the source cannot read from its own
        // start is not a shorter measurement, it is a decode that stops at the
        // first hole — and the length the analyser asked for is the same number
        // the fetch sized itself by, so this cannot disagree with it.
        val want = cache.analysisTailBytes(rendition.contentLength, expected)
        if (want <= 0L || rendition.contentLength <= 0L) return null
        val held = cache.cachedTailBytes(rendition, want)
        if (held <= 0L) return null
        val bytesPerSecond = rendition.contentLength.toDouble() / expected
        // A second of slack off the front of the window: the extractor seeks to a
        // sync sample at or before where the window starts, and a seek into the
        // gap before the cached bytes begin reads as the end of the file.
        val window = minOf(ANALYSIS_TAIL_SECONDS, held / bytesPerSecond - TAIL_WINDOW_SLACK_SECONDS)
        if (window < MIN_HEAD_SECONDS) {
            Log.d(
                TAG,
                "Tail pass for $trackId has only ${"%.1f".format(Locale.ROOT, held / bytesPerSecond)}s " +
                    "of bytes at its end; too short to measure",
            )
            return null
        }
        val start = max(0.0, expected - window)
        val copy = Copy(key = rendition.key, rendition = rendition) {
            cache.renditionDataSource(uri, rendition)
        }
        // The same guard the head and whole-track passes apply, for the same
        // reason: a sibling rendition can be a different cut of the song — a
        // radio edit, a version with a longer outro — and a mix-out anchor or a
        // content end borrowed across that difference sits seconds off, which is
        // worse than no anchor at all. Skipped for the copy the player is reading
        // itself, which is the track by definition.
        if (rendition.key != cache.cacheKeyOf(uri)) {
            val length = copy.open()?.use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // The analyser's own copy is exempt, as in the head pass: it is
                // this recording by construction, and a window of a container
                // need not carry a duration. Only a length that disagrees is
                // evidence against it.
                if (!isOwnCopy(uri, rendition)) {
                    Log.d(TAG, "Tail rendition ${rendition.key} for $trackId has no readable duration yet")
                    return null
                }
            } else if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Tail rendition ${rendition.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against " +
                        "${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(rendition.key)
                return null
            }
        }
        // Features are derived here, unlike the whole-track pass's own tail
        // region: this pass has nothing else to go on, and the DSP half is what
        // measures a content end and the mix-out anchors the policy reads.
        val tail = region(copy.open, start, expected, features = null, deriveFeatures = true)
            ?: run {
                if (stopped) return null
                Log.d(TAG, "Tail pass for $trackId could not decode rendition ${rendition.key}")
                return null
            }
        val grid = tail.grid
        val features = tail.features
        if (grid == null && features == null) {
            Log.d(TAG, "Tail pass for $trackId produced nothing usable")
            return null
        }
        // What was decoded, not what was asked for: a window the cache only
        // part-covers decodes to where the bytes stop, and a content end read off
        // three seconds is not a weaker measurement than one read off thirty, it
        // is a different one. The next attempt gets more of the file.
        if (tail.seconds < MIN_HEAD_SECONDS) {
            Log.d(TAG, "Tail pass for $trackId decoded only ${"%.1f".format(Locale.ROOT, tail.seconds)}s; too short")
            return null
        }
        // The features' own times start at the window, not at the track, and the
        // extractor seeks to a sync sample at or before what was asked for — so
        // the offset is what it actually decoded from, not [start].
        val offset = tail.startSeconds
        fun at(value: Double?): Double =
            value?.takeIf { it > 0.0 }?.plus(offset)?.coerceIn(0.0, expected) ?: 0.0
        Log.d(
            TAG,
            "Analysed tail of $trackId: bpm=${grid?.bpm ?: features?.bpm} " +
                "contentEnd=${at(features?.contentEndTime)} mixOut=${features?.mixOutCandidates?.size ?: 0} " +
                "over ${"%.1f".format(Locale.ROOT, tail.seconds)}s from " +
                "${"%.1f".format(Locale.ROOT, offset)}s",
        )
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = expected,
            bpm = grid?.bpm ?: features?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: features?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: features?.beatConfidence ?: 0.0,
            firstBeat = grid?.firstBeat ?: features?.firstBeat ?: 0.0,
            downbeats = grid?.downbeats.orEmpty(),
            key = features?.key.orEmpty(),
            keyConfidence = features?.keyConfidence ?: 0.0,
            contentEndTime = at(features?.contentEndTime),
            outroStartTime = at(features?.outroStartTime),
            mixOutTime = at(features?.mixOutTime),
            mixOutCandidates = features?.mixOutCandidates.orEmpty()
                .map { MixCandidate(it.time + offset, it.score, it.type) },
            // Kept apart from [TrackAnalysis.energyCurve] on purpose; see the
            // field's own note and [analyzeHead]'s.
            tailEnergyCurve = features?.energyCurve.orEmpty()
                .map { EnergySample(it.time + offset, it.energy) },
            // The ending's peaks, in the track's own seconds — which is where
            // the outgoing lane is drawn.
            tailPeakCurve = tail.peaks,
        )
    }

    /**
     * How far the fetch of [uri]'s analysis bytes has got, 0..1.
     *
     * Read by the panel so it can say "fetching, 40%" rather than "waiting",
     * which is the difference between a listener who knows what is happening
     * and a listener who thinks the app has stopped.
     */
    fun analysisProgress(uri: Uri): Float = cache.analysisProgressOf(uri)

    /**
     * The renditions of [uri]'s recording that the analyser may read.
     *
     * Two kinds, and the distinction between them is the whole of the fix for
     * a panel that sat on "Waiting for analysis" for every track the player
     * happened to be holding the file of:
     *
     *  - the analyser's **own** copy, under the `#analysis` key, which the
     *    analysis rounds write and which nothing else writes;
     *  - a copy that is **complete**, which nobody is writing any more.
     *
     * What is left out is the player's own live entry: the file the player is
     * streaming into, held by the player's reader, and — because the analysis
     * round used to write its own Opus copy into the same file — liable to
     * hold two encodings where the two met. Such a file does not parse, the
     * head pass refuses it, and the track is never measured however many bytes
     * it has.
     *
     * A local file is not in this list at all: it is read in place, off the
     * device, and there is nothing to race with.
     */
    private fun readableRenditions(uri: Uri): List<AudioCache.Rendition> {
        val own = cache.analysisRenditionOf(uri)
        val others = cache.renditionsOf(uri)
            .filter { it.isComplete && it.key != own?.key && it.key !in badRenditions }
        return listOfNotNull(own) + others
    }

    /**
     * Whether [rendition] is the analyser's own copy of [uri]'s recording.
     *
     * The copy the analysis rounds write and nothing else writes, so it is
     * this recording by construction: there is nothing to cross-check it
     * against, and a container that will not report its duration says nothing
     * about it either — the rounds fetch a *window* of the file, and a window
     * of a container often carries no duration at all.
     *
     * A length that disagrees is still evidence against it, and is still
     * refused.
     */
    private fun isOwnCopy(uri: Uri, rendition: AudioCache.Rendition): Boolean =
        rendition.key == cache.analysisKeyFor(uri)

    /**
     * Whether enough of [uri]'s head is on disk to be worth a decode, claiming
     * the attempt if so.
     *
     * The byte threshold is derived from the rendition's own average bitrate
     * where the duration is known, because "30 seconds of audio" is a wildly
     * different number of bytes at 96 kbps and at lossless.
     * [HEAD_BYTES_MARGIN] covers the container header and the fact that a
     * track's opening is rarely at its own average bitrate.
     *
     * Where the duration isn't known — which is the common case, since callers
     * request analysis before anything has read the container — the estimate is
     * unavailable and [MIN_HEAD_BYTES] stands in. That is about 30 s of a
     * typical stream but only a few seconds of lossless, so a single attempt
     * gated on it would be spent on too little audio for exactly the tracks
     * that carry the most bytes per second.
     *
     * Hence retrying on growth rather than attempting once: an attempt is
     * allowed again only when the cached prefix has [HEAD_RETRY_GROWTH]-fold
     * grown since the last one. A track therefore gets a handful of tries
     * spread across its download instead of either one try or one per tick.
     */
    private fun headWorthTrying(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        // Across every rendition of the recording the analyser may read, not
        // just the one the player happens to be on. The same track can be
        // part-downloaded under a sibling cache key, and the live URI's own
        // copy is frequently the one holding nothing — a track that reported
        // six megabytes cached twenty minutes earlier reported zero here,
        // because the question was being asked of the wrong copy of it.
        //
        // And never the player's live entry, which is being written while the
        // question is asked; see [readableRenditions].
        val candidate = readableRenditions(uri)
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            // The growth guard, applied as a filter rather than to the winner.
            // Applied afterwards it did not skip a copy, it ended the search: the
            // single best candidate was chosen, refused for not having grown, and
            // the second-best — frequently the one that would have worked — was
            // never reached. A track therefore got exactly one head attempt ever,
            // against whichever copy of it happened to rank highest at the time.
            .filter { rendition ->
                val previous = headAttempts[rendition.key] ?: return@filter true
                rendition.cachedPrefix >= previous * HEAD_RETRY_GROWTH
            }
            // Most *audio*, not most bytes: renditions differ in bitrate, so the
            // largest prefix is not necessarily the longest playable head.
            .maxByOrNull { headSecondsOf(it, durationSeconds) }
            ?: return null

        val prefix = candidate.cachedPrefix
        val total = candidate.contentLength
        val needed = if (durationSeconds.isFinite() && durationSeconds > MIN_HEAD_SECONDS && total > 0) {
            val bytesPerSecond = total / durationSeconds
            // Sized to [MIN_HEAD_SECONDS] — the shortest decode [analyzeHead]
            // will accept — not to the model's full window. Gating on the full
            // window meant demanding two and a half times the input the analysis
            // would actually settle for: a lossless rendition needs nine
            // megabytes on disk for thirty seconds of audio, and a track sitting
            // at six was refused outright despite holding twice what was needed
            // to produce a result. Whatever *is* cached still gets decoded — the
            // read simply runs out — so a larger prefix is used when there is
            // one, and [HEAD_RETRY_GROWTH] comes back for a better look as the
            // rest arrives.
            (MIN_HEAD_SECONDS * bytesPerSecond * HEAD_BYTES_MARGIN).toLong()
                .coerceAtLeast(MIN_HEAD_BYTES)
                .coerceAtMost(total)
        } else {
            MIN_HEAD_BYTES
        }
        if (prefix < needed) {
            // Once per track, not per tick: a head pass that never fires is
            // invisible otherwise, which is exactly how the first version of
            // this shipped doing nothing at all.
            if (headSkipLogged.add("$trackId@${candidate.key}")) {
                Log.d(
                    TAG,
                    "Head pass for $trackId waiting: ${prefix / 1024}kB cached of " +
                        "${needed / 1024}kB needed (rendition ${candidate.key})",
                )
            }
            return null
        }

        headAttempts[candidate.key] = prefix
        return candidate
    }

    /**
     * Roughly how many seconds of audio a rendition's cached prefix holds.
     *
     * The ranking this feeds used to be `cachedPrefix / contentLength`, which
     * answers zero whenever the length is unknown — and the length is unknown
     * for precisely the entry that matters most, the head
     * [AudioCache.requestAnalysisHead] just fetched, because a bounded request
     * gets a bounded answer. A megabyte of freshly downloaded opening therefore
     * scored below a sibling holding eight unusable kilobytes, and the analyzer
     * spent its one attempt on the wrong copy.
     *
     * [ASSUMED_BYTES_PER_SECOND] stands in where the length still isn't known.
     * It only has to be the right order of magnitude: this decides which copy to
     * read first, not whether the result is trusted.
     */
    private fun headSecondsOf(rendition: AudioCache.Rendition, durationSeconds: Double): Double =
        if (rendition.contentLength > 0 && durationSeconds.isFinite() && durationSeconds > 0) {
            rendition.cachedPrefix * durationSeconds / rendition.contentLength
        } else {
            rendition.cachedPrefix / ASSUMED_BYTES_PER_SECOND
        }

    /**
     * The opening window only: a beat grid, and nothing that would need the rest
     * of the file.
     *
     * Runs [TrackFeatures] over the head, but copies across only the fields
     * that describe an *entry*: where the file starts making sound, the pickup,
     * the end of the intro, and the mix-in candidates. Those are all measured
     * within the opening seconds, so a head-only pass measures them exactly as
     * a whole-track pass would.
     *
     * Everything that describes the rest of the track is dropped on the floor —
     * content end, outro, mix-out anchors, the energy curve. Over a 30 s head
     * that pass does not fail, it answers confidently about a track that is
     * mostly missing, and the planner has no way to tell the difference. Left at
     * their defaults they read as "no evidence": [contentEndTime] falls back to
     * the real duration and the mix-out list ranks as empty.
     *
     * The energy curve is dropped for the same reason even though it is
     * genuinely measured here: the policy indexes the vocal mask against it and
     * counts audible seconds from it, and a curve that stops at 30 s would have
     * this track's *outgoing* half scored against a window it does not cover.
     * A vocal mask therefore cannot come from this pass either, and waits for
     * the whole-track one.
     */
    private fun analyzeHead(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        rendition: AudioCache.Rendition,
    ): TrackAnalysis? {
        fun openSource(): MediaDataSource? = cache.renditionDataSource(uri, rendition)

        // Same guard the whole-track pass applies, for the same reason: a
        // sibling rendition can be a different cut, and a beat grid borrowed
        // across that would put every anchor seconds out. Skipped for the
        // player's own copy, which is the track by definition. A header that
        // will not parse yet is not held against the rendition — more bytes may
        // well fix it — but a length that genuinely disagrees is.
        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected != null && rendition.key != cache.cacheKeyOf(uri)) {
            val length = openSource()?.use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // The analyser's own copy is exempt: it was fetched from this
                // recording, so there is nothing to cross-check it against, and
                // it is a *window* — a window of a container need not carry a
                // duration at all. Refusing it for that is what left the panel
                // waiting on an analysis that had already been fetched.
                if (!isOwnCopy(uri, rendition)) {
                    // Logged rather than returned quietly. This is the likeliest way
                    // for a head pass to do nothing — a partial container the
                    // extractor will not read a duration out of — and while it was
                    // silent the whole path looked like it had never run.
                    Log.d(TAG, "Head rendition ${rendition.key} for $trackId has no readable duration yet")
                    return null
                }
            } else if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Head rendition ${rendition.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(rendition.key)
                return null
            }
        }

        val window = BeatTracker.WINDOW_SECONDS
        val head = region(::openSource, 0.0, window, features = null, deriveFeatures = true)
            ?: run {
                // An abandoned decode comes back the same way a broken one
                // does, and only one of the two is worth a warning: see
                // [stopped]. Nothing is recorded either way — the caller
                // publishes only a non-null result — so the track is simply
                // left unmeasured, which is what it is.
                if (stopped) return null
                Log.d(TAG, "Head pass for $trackId could not decode rendition ${rendition.key}")
                return null
            }
        // What was decoded, not what was asked for: the source stops where the
        // cache does. A tempo read off a few seconds is not a weaker measurement
        // than one read off thirty, it is a different and much more credulous
        // one, and the planner cannot see the difference — so it is refused here
        // and the next attempt gets more of the file.
        if (head.seconds < MIN_HEAD_SECONDS) {
            Log.d(TAG, "Head pass for $trackId decoded only ${"%.1f".format(Locale.ROOT, head.seconds)}s; too short")
            return null
        }
        val grid = head.grid
        val entry = head.features
        if (grid == null && entry == null) {
            Log.d(TAG, "Head pass for $trackId produced nothing usable")
            return null
        }

        Log.d(
            TAG,
            "Analysed head of $trackId: bpm=${grid?.bpm ?: entry?.bpm} " +
                "conf=${grid?.beatConfidence ?: entry?.beatConfidence} " +
                "audibleStart=${entry?.audibleStartTime} pickup=${entry?.pickupTime} " +
                "introEnd=${entry?.introEndTime} mixInCandidates=${entry?.mixInCandidates?.size ?: 0} " +
                "over ${"%.1f".format(Locale.ROOT, head.seconds)}s",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            bpm = grid?.bpm ?: entry?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: entry?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: entry?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: entry?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: entry?.firstBeat ?: 0.0,
            key = entry?.key.orEmpty(),
            keyConfidence = entry?.keyConfidence ?: 0.0,
            audibleStartTime = entry?.audibleStartTime,
            pickupTime = entry?.pickupTime,
            introEndTime = entry?.introEndTime ?: 0.0,
            mixInTime = entry?.mixInTime ?: 0.0,
            mixInCandidates = entry?.mixInCandidates.orEmpty(),
            // The opening's own envelope, kept for the panel and for nothing
            // else. It describes the window this pass actually decoded and
            // says nothing about the rest of the track, which is why it does
            // not go into [TrackAnalysis.energyCurve] — see that field. The
            // panel draws it where it belongs and leaves the rest of the
            // lane blank, which is honest and better than an empty lane.
            headEnergyCurve = entry?.energyCurve.orEmpty(),
            // The opening's peaks, at the resolution the panel draws: the
            // loudest instant in each twenty milliseconds, measured from the
            // samples rather than averaged out of them.
            headPeakCurve = head.peaks,
        )
    }

    /**
     * Whether this rendition can be read through: every byte, or close enough
     * that the whole-track decode will take it.
     *
     * [AudioCache.Rendition.isComplete] is the strict test, and it is stricter
     * than the decode it gates. [structure] already refuses a decode that stops
     * more than [MIN_DECODED_FRACTION] short of the track — so a rendition
     * missing a couple of per cent was refused twice over: once by the fetch
     * that could not finish it (the analysis round is a window, not the file —
     * see [AudioCache.analysisOpeningBytes]) and again here, for bytes the
     * decoder would have tolerated. Nothing then measured the track whole, and
     * for a copy past the cap that was permanent.
     *
     * The threshold is deliberately a little above [MIN_DECODED_FRACTION] so the
     * decode's own tolerance has margin rather than being hit exactly.
     *
     * ## The length has to be believable
     *
     * A bounded fetch — which is every fetch the analyser makes — is answered
     * with a response that describes the *range* that was asked for, and the
     * cache records that range as the resource's length. The cache then holds
     * a one-megabyte head of a three-and-a-half-minute song and calls it
     * complete, and the whole-track pass decodes twelve seconds of the track
     * and refuses the result: "decoded 12.0s of a 221.0s (container — cached
     * with holes?)". Nothing is broken; the file is simply not all there yet,
     * and a copy too short to *be* this track is not one the whole-track pass
     * can read.
     */
    private fun AudioCache.Rendition.isAnalysable(durationSeconds: Double): Boolean {
        if (contentLength <= 0L) return false
        if (durationSeconds.isFinite() && durationSeconds > 0.0) {
            val floor = (durationSeconds * MIN_PLAUSIBLE_BYTES_PER_SECOND).toLong()
            if (contentLength < floor) return false
        }
        return cachedPrefix >= contentLength * ANALYSABLE_FRACTION
    }

    /**
     * Picks which rendition of [uri]'s recording to analyse: the lightest one
     * that is both readable and the same cut as the track being played.
     *
     * A recording can be on disk two or three times over — the Opus stream
     * YouTube served, a substituted source's copy, a later quality upgrade — and
     * they hold the same music, so an analysis of any of them describes all of
     * them. Analysing the smallest is not merely cheaper: it is the one that
     * finished downloading first, and a lossless upgrade can take most of a
     * track's play time to arrive. Waiting for it is why analysis was landing
     * seconds *after* the transition it was meant to inform.
     *
     * The duration check is what makes the sharing safe. A `#alt` rendition
     * comes from an entirely different source and may be a different cut —
     * a radio edit, a version with a longer intro — and a beat grid borrowed
     * across that difference would put every downbeat and both mix anchors
     * seconds out. Comparing container durations catches exactly that, and
     * costs a header parse per candidate.
     */
    private fun chooseRendition(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val readable = cache.renditionsOf(uri)
            .filter { it.isAnalysable(durationSeconds) && it.key !in badRenditions }
        if (readable.isEmpty()) return null

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        // Without a length to check a sibling against, sharing would be a guess,
        // so only the rendition actually being played can be trusted.
        if (expected == null) {
            val own = cache.cacheKeyOf(uri)
            readable.firstOrNull { it.key == own }?.let { return it }
            // Nothing to cross-check against — but one copy is not ambiguous
            // either, and refusing it outright is a dead end rather than a
            // safeguard. [cacheKeyOf] answers with whichever rendition the key
            // factory resolves to *now*, which with substitution on is the `#alt`
            // entry; the copy actually on disk is routinely the plain one, so
            // this asked for a rendition that did not exist and returned null on
            // every tick, silently, for as long as the track stayed queued.
            //
            // The risk the duration check exists to catch is borrowing a beat
            // grid across two different cuts of a song. That needs two copies to
            // choose wrongly between. With exactly one there is no choice being
            // made, and the worst case degrades from "never analysed" to "a grid
            // measured off the only audio we have".
            return readable.singleOrNull()?.also {
                Log.d(
                    TAG,
                    "Analysing $trackId from its only cached rendition ${it.key}; " +
                        "no duration to check it against",
                )
            }
        }

        for (candidate in readable) {
            val length = cache.renditionDataSource(uri, candidate)
                .use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // Every candidate here is readable, so no more bytes are coming
                // to make the header readable: remembered for the reason below.
                badRenditions.add(candidate.key)
                continue
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Rendition ${candidate.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                // Remembered, as the head pass remembers its own rejections. A
                // cut that is the wrong length now is the wrong length on the
                // next tick too, and while it counted as complete [request]
                // queued a pass on every one of them — reopening and parsing the
                // same file four times a second for as long as the track played.
                badRenditions.add(candidate.key)
                continue
            }
            if (candidate.key != cache.cacheKeyOf(uri)) {
                Log.d(
                    TAG,
                    "Analysing $trackId from lighter rendition ${candidate.key} " +
                        "(${candidate.contentLength / 1024}kB)",
                )
            }
            return candidate
        }
        return null
    }

    /**
     * Where a whole-track pass reads its audio from, and the identity the retry
     * bookkeeping keys off.
     *
     * Two kinds of copy exist and they are not interchangeable. A **cached
     * rendition** is one of several copies of a streamed recording: chosen
     * between by [chooseRendition], cross-checked for being the same cut, and —
     * when it turns out undecodable — thrown off disk so a clean one can replace
     * it. A **local file** is the track itself: exactly one of it, complete from
     * the moment it exists, nothing to choose between, and not ours to delete.
     *
     * [rendition] is what tells the two apart. Null means the audio is a file on
     * the device, which is why the blame-and-discard half of [structure] is
     * conditional on it rather than on a flag.
     */
    private class Copy(
        val key: String,
        val rendition: AudioCache.Rendition?,
        val open: () -> MediaDataSource?,
    )

    /**
     * Which copy of [trackId]'s audio to read, or null when there isn't a usable
     * one yet.
     *
     * A local URI short-circuits the entire rendition search: none of what that
     * search decides between applies to a file the user already has, and asking
     * the cache about one only ever produced the empty answer that kept Automix
     * from analysing local tracks at all. The URI stands in as the cache key,
     * which is what [request]'s `untried` check reads so a file that will not
     * decode is not re-decoded on every tick for the rest of the session.
     */
    private fun copyToRead(trackId: String, uri: Uri, durationSeconds: Double): Copy? {
        if (LocalAudioSource.isLocal(uri)) {
            return Copy(key = uri.toString(), rendition = null) { LocalAudioSource.open(resolver, uri) }
        }
        val rendition = chooseRendition(trackId, uri, durationSeconds) ?: return null
        return Copy(key = rendition.key, rendition = rendition) { cache.renditionDataSource(uri, rendition) }
    }

    /**
     * Where Automix reads [trackId]'s audio from.
     *
     * The copy the track is playing from. Always.
     *
     * A source-backed track used to be measured on a YouTube Opus copy of the
     * same recording instead: a catalogue search found the YouTube id, a
     * YouTube-specific URI named the copy, and it was preferred because it was
     * the lightest thing to decode. That made Automix depend on YouTube to
     * measure a track YouTube had nothing to do with — with YouTube switched
     * off there was no match to find and no copy to measure, so every
     * addon-served track went into its transition unanalysed, and with YouTube
     * switched on the evidence described a different rendition of the song than
     * the one under the listener.
     *
     * The copy actually playing is the one the planner's evidence has to
     * describe, and it is no harder to read: [chooseRendition] already picks
     * the lightest complete copy of it on disk and checks that it is the same
     * cut of the same recording, so the shared-cache and duration checks are
     * unchanged. What is gone is the dependency, not the care.
     */
    private fun analysisUriFor(trackId: String, playbackUri: Uri): Uri = playbackUri

    /**
     * What a whole-track pass came back with.
     *
     * [decodedShort] is the difference between "this copy is broken, strike it"
     * and "there was no copy to read", which the caller counts very differently:
     * three strikes writes a track off for the session. Conflating the two spent
     * all three in 922ms on a track whose only complete copy had just been
     * rejected — the following two attempts decoded nothing because there was
     * nothing left to decode, and were counted as though they had tried.
     */
    private class WholeTrack(val analysis: TrackAnalysis?, val decodedShort: Boolean = false)

    /**
     * What Pass 1 came back with.
     *
     * [decodedShort] has to survive the return rather than collapsing into a null [features]: it is
     * the same distinction [WholeTrack.decodedShort] draws, between a broken copy and no copy, and
     * only one of the two is a strike.
     */
    private class Structural(
        val features: TrackFeatures.Features?,
        val decodedShort: Boolean = false,
        /** The whole track's waveform peaks; see [peakCurveOf]. */
        val peaks: List<EnergySample> = emptyList(),
    )

    /**
     * Decodes the whole track and reduces it to DSP features.
     *
     * A method rather than a block in [analyze] for a reason that is about memory, not tidiness —
     * see the call site. Everything it decodes is dead by the time it returns, and returning is
     * what makes that true of the heap as well as of the program.
     */
    private fun structure(
        trackId: String,
        uri: Uri,
        copy: Copy,
        effectiveDuration: Double,
    ): Structural {
        val structRate = TrackFeatures.sampleRate
        val decoded = copy.open()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) { stopped } }
            ?: return Structural(null)
        val (pcm, _) = decoded

        // A decode that stops early is indistinguishable, downstream, from a
        // track that simply goes quiet: [TrackFeatures] is handed the
        // container's full duration alongside a short buffer, reads the
        // difference as trailing silence, and puts the mix-out anchor where the
        // bytes ran out. Nothing about the result looks wrong — it is a complete
        // analysis with a plausible contentEnd — and the audible symptom is the
        // track being faded out minutes early. Refused outright rather than
        // published, because a missing analysis degrades to a plain crossfade
        // while a confidently wrong one does not degrade at all.
        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            // A copy that is not all here yet is not a broken copy.
            //
            // The decode stops where the bytes stop, and a copy that is still
            // being filled stops short of the track by definition. Treating
            // that as a broken file spends a strike on it, writes it off as
            // bad, and — when it is the copy the player is streaming from —
            // throws the player's own bytes off disk mid-track. The strikes
            // are for files that are whole and still do not decode.
            val rendition = copy.rendition
            if (rendition != null && rendition.cachedPrefix < rendition.contentLength) {
                Log.d(
                    TAG,
                    "Analysis of $trackId waits: ${rendition.key} holds " +
                        "${rendition.cachedPrefix / 1024}kB of ${rendition.contentLength / 1024}kB",
                )
                return Structural(null, decodedShort = false)
            }
            Log.w(
                TAG,
                "Analysis of $trackId refused: ${copy.key} decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s " +
                    // The two causes are different enough to be worth naming. A
                    // cached rendition stops at the first hole read-ahead left in
                    // it; a file on the device has no holes, so a short decode
                    // there means the container itself is truncated or damaged.
                    if (copy.rendition != null) "container — cached with holes?" else "container — truncated file?",
            )
            // Both halves of this are about *renditions*, so both are conditional
            // on there being one. A local file has no sibling copy to be routed to
            // instead and is not ours to delete either way; the three-strike count
            // in [request] is the whole bound there, and it is enough, because a
            // file on disk that decodes short will decode short again.
            copy.rendition?.let { rendition ->
                // Remembered, so the retry reaches for a *different* rendition. This
                // is the whole reason the lightest one is only a preference: a
                // rendition the cache index calls complete can still decode short if
                // it was written badly, and without this the retries would pick the
                // same broken copy three times over and give up on a track whose
                // heavier rendition would have analysed perfectly well.
                badRenditions.add(rendition.key)
                // And thrown off disk, not merely remembered. A file the cache calls
                // complete and the decoder gives up on partway is not going to
                // improve: nothing else will ever write to it, because as far as the
                // cache is concerned it is finished. Remembering it only helps for as
                // long as this process lives — a restart clears the set, the same
                // bytes are read again, and the same seconds are spent reaching the
                // same conclusion. Deleting it is what lets a clean copy be fetched.
                // Skipped for whatever the player is reading from; see
                // [AudioCache.discardBadRendition].
                if (rendition.isComplete && discardsOf(rendition.key) < MAX_RENDITION_DISCARDS &&
                    cache.discardBadRendition(uri, rendition.key)
                ) {
                    // Every memory of that copy goes with the bytes. Deleting the
                    // file and then still refusing its key is the worst of both: the
                    // clean copy [AudioCache.requestAnalysisHead] fetches in its place
                    // is filtered straight back out by [badRenditions], the track is
                    // written off for the session anyway, and the download was spent
                    // on nothing. The strike count goes too — the next attempt reads
                    // genuinely different bytes, so it starts level.
                    discarded.merge(rendition.key, 1, Int::plus)
                    badRenditions.remove(rendition.key)
                    triedRenditions.remove(rendition.key)
                    shortDecodes.remove(trackId)
                }
            }
            return Structural(null, decodedShort = true)
        }

        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return Structural(null)
        } else {
            pcm.samples
        }

        return Structural(
            TrackFeatures.analyze(samples, effectiveDuration),
            peaks = peakCurveOf(samples, structRate, 0.0),
        )
    }

    /**
     * The whole-track pass. A null [WholeTrack.analysis] means "not now, try
     * again"; see the short-decode guard below, which is the one condition that
     * produces a confident-looking analysis that is wrong by minutes rather than
     * merely absent, and the only one that counts as a strike.
     */
    private fun analyze(trackId: String, uri: Uri, durationSeconds: Double): WholeTrack {
        val copy = copyToRead(trackId, uri, durationSeconds) ?: return WholeTrack(null)
        // Recorded before the outcome is known, because what this gates is
        // whether a *written-off* track is worth reopening, and the answer is
        // only ever "yes" for a copy that has not been read yet. Recording it on
        // success alone would leave a failure looking permanently reopenable and
        // re-decode the same file on every tick.
        triedRenditions.add(copy.key)
        val openSource = copy.open

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: ${copy.key} has no readable duration")
            return WholeTrack(empty(trackId, 0.0))
        }

        // A track longer than the cap is not analysed whole.
        //
        // The whole-track pass decodes the entire recording into one mono
        // float buffer — at the container's rate, that is about ten megabytes
        // a minute, so a two-hour episode is a gigabyte and a half of PCM on a
        // heap this process is allowed a quarter of a gigabyte of. The
        // allocation does not fail politely: it takes the heap with it, and
        // the next thing that asks for memory — the player's own loader, on
        // the next track — dies instead, which is what a crash at the end of
        // a long track looked like from the outside.
        //
        // Nothing is lost by refusing it. The transitions read two windows of
        // a track, the head and the tail, and both are read below whatever
        // this pass does; the one thing that does not happen is the energy
        // curve over the middle, which is what the mix-out anchor is read
        // from. A podcast with a plain crossfade instead of a measured one is
        // a smaller loss than a crash.
        val wholeTrack = effectiveDuration <= MAX_WHOLE_TRACK_SECONDS

        // Pass 1 (Phase 1, DSP-only): the analyzer needs the whole track — the energy curve,
        // phrase structure and mix-out anchor all read the tail, not just a window of it — at its
        // own low sample rate, so this is a much smaller decode than a full-rate pass would be.
        //
        // In a frame of its own, and handing back only the features, because of what it allocates
        // to get them: the whole track decoded to mono at the container's rate (35 MB for a
        // 3.5-minute song) plus the resampled copy the DSP reads (8 MB). Neither is touched again
        // after this line, but a local holding either stays reachable to the end of the method, and
        // the rest of the method is Pass 2 — the most allocation-heavy part of the analysis.
        // Measured on the API 28 emulator: those two buffers were 43 MB of an 82 MB live set, still
        // held while the models ran, in a process that was reaching a 256 MB heap limit and had
        // died on it. Returning is what releases them — a `val` cannot be nulled, and a narrower
        // scope alone does not make ART treat one as dead.
        val structural = if (wholeTrack) {
            structure(trackId, uri, copy, effectiveDuration)
        } else {
            Log.i(TAG, "Not analysing $trackId whole: ${"%.0f".format(Locale.ROOT, effectiveDuration)}s is over the ${MAX_WHOLE_TRACK_SECONDS}s cap")
            Structural(null)
        }
        // A party started while Pass 1 was decoding. Dropped as "no result"
        // rather than as a failure — the track was never measured, so nothing
        // should be recorded against it and nothing should stop it being
        // measured properly once the party ends.
        //
        // Read *before* the two lines below, not after, because an abort comes
        // back from [structure] as a decode that produced nothing, and that is
        // exactly what those lines write a permanent "cannot be analysed" for.
        if (stopped) return WholeTrack(null)
        if (structural.decodedShort) return WholeTrack(null, decodedShort = true)
        // A track over the cap has no features: the energy curve was what the
        // whole-track pass was for, and it was not run. The windows below are
        // read without them, which is how the head pass reads them.
        val features = structural.features
        if (features == null && wholeTrack) return WholeTrack(empty(trackId, effectiveDuration))

        // Pass 2 (Phases 2 and 3, models): the Beat This! grid and the open-unmix vocal mask, over
        // the head and tail only. A transition only ever reads the tail of the outgoing track and
        // the head of the incoming one, and a track is both of those at different moments, so the
        // middle is never decoded for this. Both models read the same decoded region, so the
        // stereo buffer is paid for once.
        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, effectiveDuration - window)
        val head = region(openSource, 0.0, minOf(window, effectiveDuration), features)
        // Between the two model passes, for the same reason as above. Not
        // folded into the `tail` condition below: a head-only result would be
        // written *and persisted* as if it were a whole-track one, freezing a
        // deliberately partial answer in place of the complete one.
        if (stopped) return WholeTrack(null)
        val tail = if (tailStart > window / 2) region(openSource, tailStart, effectiveDuration, features) else null
        // Last of the three. An abort during the tail pass leaves `tail` null,
        // which is indistinguishable from a window the models found nothing in
        // — and that one gets published, with the DSP estimate standing in. A
        // result assembled out of a half-finished analysis is worse than none.
        if (stopped) return WholeTrack(null)

        val headGrid = head?.grid
        val tailGrid = tail?.grid

        // The tail governs where the outgoing track is mixed out, so it takes precedence; the
        // head is what a track uses when it is the *incoming* side of a different transition.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features?.bpm} " +
                "conf=${leading?.beatConfidence ?: features?.beatConfidence} " +
                "key=${features?.key} contentEnd=${features?.contentEndTime} " +
                "mixOutCandidates=${features?.mixOutCandidates?.size ?: 0} " +
                "vocalMask=${if (head?.vocalMask != null || tail?.vocalMask != null) "model" else "dsp"}",
        )

        // A track over the cap has no features: the energy curve was what the
        // whole-track pass was for, and it was not run. What is left is the
        // beat grid over the head and the tail, which is what a transition
        // reads anyway — so the analysis is published without the curve, and
        // the policy falls back to a plain crossfade where it has nothing to
        // go on.
        return WholeTrack(
                TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = trackId,
                    duration = effectiveDuration,
                    contentEndTime = features?.contentEndTime?.takeIf { it > 0 } ?: effectiveDuration,
                    bpm = leading?.bpm ?: features?.bpm ?: 0.0,
                beatInterval = leading?.beatInterval ?: features?.beatInterval ?: 0.0,
                beatConfidence = leading?.beatConfidence ?: features?.beatConfidence ?: 0.0,
                downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                    .ifEmpty { features?.downbeats.orEmpty() }
                    .sorted(),
                firstBeat = headGrid?.firstBeat ?: features?.firstBeat ?: 0.0,
                phraseBoundaries = features?.phraseBoundaries ?: emptyList(),
                key = features?.key.orEmpty(),
                keyConfidence = features?.keyConfidence ?: 0.0,
                audibleStartTime = features?.audibleStartTime,
                pickupTime = features?.pickupTime,
                introEndTime = features?.introEndTime ?: 0.0,
                outroStartTime = features?.outroStartTime ?: 0.0,
                mixInTime = features?.mixInTime ?: 0.0,
                mixOutTime = features?.mixOutTime ?: 0.0,
                mixInCandidates = features?.mixInCandidates.orEmpty(),
                mixOutCandidates = features?.mixOutCandidates.orEmpty(),
                energyCurve = features?.energyCurve.orEmpty(),
                // The whole track's waveform, at the resolution the panel
                // draws it: the energy curve above is an average over a
                // quarter of a second and reads as a bar chart.
                peakCurve = structural.peaks,
                lowEnergyCurve = features?.lowEnergyCurve.orEmpty(),
                // The model's mask where it ran, the DSP heuristic's where it didn't. Falling back to
                // the heuristic rather than to nothing matters because the policy reads an
                // absent mask and a neutral one identically — as "no evidence" — so a failed model
                // pass would otherwise silently discard the estimate Phase 1 already had.
                vocalActivityMask = mergeMasks(features?.energyCurve?.size ?: 0, head?.vocalMask, tail?.vocalMask)
                    ?: features?.vocalActivityMask.orEmpty(),
                vocalProbability = features?.vocalProbability ?: 0.0,
            ),
        )
    }

    /**
     * Everything a decoded region contributes, once its audio has been let go
     * of. [seconds] is what was actually decoded, which for a partially cached
     * file is not what was asked for.
     */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        /**
         * Where the decoded window actually starts in the track, in seconds.
         *
         * The extractor seeks to a sync sample at or before what was asked for,
         * so this is not the `startSeconds` the caller passed. The model's beat
         * times are already stated against it; the DSP features are not, and a
         * pass that decodes anything other than a whole track has to add it — a
         * tail window whose curve was left at zero would draw the track's
         * ending at its opening.
         */
        val startSeconds: Double = 0.0,
        /** Only populated when the caller asked for it; see [region]'s `deriveFeatures`. */
        val features: TrackFeatures.Features? = null,
        /**
         * The window's peaks, in the track's own seconds.
         *
         * The panel's waveform, and the only part of the picture that has the
         * shape of the sound in it: the energy curve is an average over a
         * quarter of a second and reads as a bar chart.
         */
        val peaks: List<EnergySample> = emptyList(),
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only their results.
     *
     * The point of the function boundary is the audio: the stereo buffer, its mono mix and the
     * resampled copies are all local, so they become collectible the moment this returns rather
     * than staying live until the whole analysis finishes. A 30 s stereo region is several
     * megabytes before either model's own working set is counted.
     *
     * Null, or a null field, means "no model evidence for this window" — a codec that will not
     * configure, a region too short, a missing model — which [analyze] already falls back on.
     *
     * The extractor seeks to a sync sample at or before what was asked for, so the region's real
     * start (not [startSeconds]) is what its beat times must be stated against.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        deriveFeatures: Boolean = false,
    ): Region? {
        val decoded = openSource()?.use { AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds) { stopped } }
            ?: run {
                // The two ways this comes back empty mean opposite things and
                // were reported identically, which cost a round of guessing:
                // this one is the extractor refusing the container outright, so
                // the bytes are wrong or not enough of them are there to parse.
                Log.d(TAG, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) {
            // And this one is a container that parsed fine and yielded under a
            // second of audio — a decode that started and ran out, not one that
            // never started.
            Log.d(TAG, "Region [$startSeconds, $endSeconds) decoded ${stereo.left.size} frames; too few")
            return null
        }

        val seconds = stereo.left.size / stereo.sampleRate
        // In a frame of its own so the full-rate mono downmix is released before either model runs.
        // It is 23 MB for this window at 48 kHz — the same size as each of the two channels it
        // averages — and it is read exactly twice, to make the resampled model input and the DSP
        // one. As a local it would nonetheless stay reachable through `tracker.track` and
        // `vocalMask` below, which is where the analysis allocates most heavily and where the
        // process was dying. Same reasoning [derived] already had, one level further out.
        val inputs = regionInputs(stereo, seconds, deriveFeatures, actualStart)

        return Region(
            grid = inputs.forModel?.let { tracker.track(it, offsetSeconds = actualStart) },
            vocalMask = features?.let { vocalMask(stereo, it, actualStart) },
            seconds = seconds,
            startSeconds = actualStart,
            features = inputs.derived,
            peaks = inputs.peaks,
        )
    }

    /** A region's model input, its DSP features, and its waveform peaks. */
    private class RegionInputs(
        val forModel: FloatArray?,
        val derived: TrackFeatures.Features?,
        val peaks: List<EnergySample>,
    )

    /**
     * Reduces a decoded region to the buffers the models and the DSP actually read.
     *
     * Both come off one full-rate mono downmix, which is why they are made together rather than on
     * demand: that downmix is the largest single allocation in the analysis, and returning is the
     * only way to be rid of it before the models run.
     *
     * The peaks come off the same signal: the panel's waveform is drawn from
     * them, and they are the only part of the picture that has the shape of
     * the sound in it rather than the shape of the analyser's own grid.
     */
    private fun regionInputs(
        stereo: AudioDecoder.StereoPcm,
        seconds: Double,
        deriveFeatures: Boolean,
        startSeconds: Double,
    ): RegionInputs {
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel = if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate)
        } else {
            mono
        }

        // Derived here rather than by the caller so the mono buffer is still
        // live: handing it back would keep several megabytes reachable for the
        // rest of the analysis, which is the one thing this function exists to
        // avoid.
        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(stereo.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(mono, stereo.sampleRate, TrackFeatures.sampleRate)
            } else {
                mono
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }

        // The peaks, off the full-rate signal: at the analysis rate a fifth of
        // a second is two hundred samples, which is coarse enough to lose the
        // transient the drawing exists to show.
        val peaks = peakCurveOf(mono, stereo.sampleRate, startSeconds)

        return RegionInputs(forModel, derived, peaks)
    }

    /**
     * The envelope of a decoded signal, as peaks.
     *
     * The loudest instant in each short window of the audio, which is what the
     * eye reads as the shape of a sound.
     *
     * ## Why this is measured rather than derived
     *
     * The analyser's own energy curve is an RMS level per quarter-second,
     * downsampled to at most a couple of hundred points over the whole
     * recording. That is the right shape for "how loud is this part of the
     * song" and the wrong shape for a waveform: drawn as it stands it reads
     * as a bar chart — flat-topped blocks, quantised to the curve's own grid,
     * with the transients nowhere in it. The panel's complaint was exactly
     * that, and no amount of redrawing fixes a drawing whose data is an RMS
     * average.
     *
     * The peaks are therefore taken from the samples themselves, twenty
     * milliseconds at a time, which is finer than a screen pixel over the
     * panel's viewport and fine enough to show a snare.
     *
     * The count is capped so a long recording cannot turn into a hundred
     * thousand numbers: past the cap the window grows instead, and the
     * drawing loses the last of its detail on a track nobody is going to
     * look at closely.
     */
    private fun peakCurveOf(
        samples: FloatArray,
        sampleRate: Double,
        startSeconds: Double,
    ): List<EnergySample> {
        if (samples.isEmpty() || sampleRate <= 0.0) return emptyList()
        val window = max(1, (sampleRate * PEAK_HOP_SECONDS).toInt())
        val buckets = (samples.size + window - 1) / window
        val stride = max(1, ceil(buckets.toDouble() / MAX_PEAK_POINTS).toInt())
        val points = ArrayList<EnergySample>(buckets / stride + 1)
        var index = 0
        while (index < buckets) {
            val from = index * window
            val to = minOf(samples.size, from + window * stride)
            var peak = 0f
            for (sample in from until to) {
                val value = abs(samples[sample])
                if (value > peak) peak = value
            }
            points.add(EnergySample(startSeconds + from / sampleRate, peak.toDouble()))
            index += stride
        }
        return points
    }

    /**
     * A vocal-presence value for every point on the energy curve, filled only where the model
     * actually ran.
     *
     * The policy indexes the mask against energy-curve sample times and requires the two to be the
     * same length, but the model's window is fixed at about 22 seconds, far less than a track. So
     * the mask is built at full length and filled only over this region.
     *
     * Everywhere else stays at [NEUTRAL_VOCAL]. That is not a guess dressed up as data: it sits
     * below the policy's own VOCAL_ACTIVE_THRESHOLD, so unmeasured material can never trip vocal
     * logic in either direction.
     */
    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        // The beat model's window is longer than the vocal model's fixed input, so the region is
        // trimmed rather than handed over whole — [VocalTracker.track] refuses anything wider than
        // its graph, and refusing is how the tail of every region would otherwise go unmeasured.
        // Two frames of margin absorb the ±1 sample a rate conversion can land on.
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right

        val values = vocals.track(left, right, stereo.sampleRate) ?: return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    /**
     * Overlays the head and tail masks onto one full-length curve, or null when neither ran —
     * which the caller answers by keeping the DSP heuristic rather than reporting a mask of
     * nothing but [NEUTRAL_VOCAL].
     */
    private fun mergeMasks(size: Int, head: DoubleArray?, tail: DoubleArray?): List<Double>? {
        if (size <= 0 || (head == null && tail == null)) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in listOfNotNull(head, tail)) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    fun release() {
        fastExecutor.shutdownNow()
        wholeExecutor.shutdownNow()
        headAttempts.clear()
        headSkipLogged.clear()
        tailAttempts.clear()
        tailSkipLogged.clear()
        provisional.clear()
        partials.clear()
        wholeResults.clear()
        tailsRunning.clear()
        restoreAttempted.clear()
        shortDecodes.clear()
        badRenditions.clear()
        triedRenditions.clear()
        discarded.clear()
        tracker.release()
        vocals.release()
    }

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"

        /**
         * What an unmeasured instant reads as. Below the policy's VOCAL_ACTIVE_THRESHOLD by
         * design, so absence of measurement is never mistaken for absence of a vocal, or for the
         * presence of one.
         */
        const val NEUTRAL_VOCAL = 0.5

        /**
         * How much more than the average-bitrate estimate of the opening window
         * to insist on before decoding it. Covers the container header and the
         * fact that a track's opening is rarely at its own average bitrate.
         */
        const val HEAD_BYTES_MARGIN = 1.35

        /**
         * Floor under the computed threshold, and the whole requirement when the
         * duration is unknown. Roughly fifteen seconds at 128 kbps — a little
         * over [MIN_HEAD_SECONDS], so it guarantees a parsable container and a
         * usable decode without quietly reinstating the thirty-second demand the
         * bitrate estimate was just lowered away from.
         */
        const val MIN_HEAD_BYTES = 256L * 1024L

        /**
         * How much of the container's stated duration must actually decode
         * before the whole-track pass is trusted. Not 1.0: a decoder legitimately
         * comes up a frame or two short of the container's rounding, and
         * refusing over that would refuse everything.
         */
        const val MIN_DECODED_FRACTION = 0.95

        /**
         * How much of a rendition's own length has to be on disk before the
         * whole-track pass will read it; see [isAnalysable].
         *
         * Above [MIN_DECODED_FRACTION] on purpose: the decode's 5 % tolerance
         * is for padding and rounding, and a rendition admitted at exactly that
         * line would fail its own decode and burn a strike instead of being
         * measured.
         */
        const val ANALYSABLE_FRACTION = 0.98

        /**
         * The slowest a track can be and still be worth reading whole.
         *
         * Sixty-four kilobits a second — the floor of what any service serves
         * as audio. A copy whose recorded length is below this rate for the
         * track's own runtime is not a recording of that track at all: it is
         * the length of the *range* that was fetched, filed as the length of
         * the file.
         */
        const val MIN_PLAUSIBLE_BYTES_PER_SECOND = 8_000.0

        /**
         * A second shaved off the front of a tail window before it is decoded.
         *
         * The window is drawn from the bytes on disk, and the extractor seeks to
         * a sync sample at or *before* where it was asked to start — so a window
         * that begins exactly at the first cached byte can be seeked into the gap
         * in front of it, which reads as the end of the file. See [analyzeTail].
         */
        const val TAIL_WINDOW_SLACK_SECONDS = 1.0

        /**
         * The longest track the analyzer will decode whole.
         *
         * A song is three to eight minutes; a radio edit is under ten. Past
         * that the thing being decoded is a podcast episode or an audiobook
         * chapter, and the whole-track pass would put more PCM on the heap
         * than the process is allowed altogether.
         */
        const val MAX_WHOLE_TRACK_SECONDS = 10 * 60.0

        /** Refusals before a track is written off as truncated rather than still filling in. */
        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        /**
         * Clean-slate retries per rendition. One: a discard is worth doing when
         * the bytes on disk are unrecoverable and nothing would otherwise
         * overwrite them, and worth doing exactly once, because a second identical
         * answer means the source is serving that file rather than the cache
         * having mangled it.
         */
        const val MAX_RENDITION_DISCARDS = 1

        /**
         * How far two renditions' container durations may differ and still count
         * as the same cut. Generous enough for codec padding and the player's own
         * rounding, tight enough that a different edit of the same song — where a
         * borrowed beat grid would be useless — is rejected.
         */
        const val RENDITION_DURATION_TOLERANCE = 1.0

        /**
         * How much audio each measured peak covers.
         *
         * Twenty milliseconds: finer than a screen pixel across the panel's
         * viewport, and fine enough to show a snare rather than a swell.
         */
        const val PEAK_HOP_SECONDS = 0.02

        /**
         * How many peaks a measured curve may hold.
         *
         * A bound on the numbers rather than on the detail: past it the window
         * grows instead, so a long recording cannot turn into a hundred
         * thousand points — and a hundred thousand points is a hundred
         * thousand objects on a phone's heap for a picture nobody looks at
         * closely.
         */
        const val MAX_PEAK_POINTS = 4000

        /**
         * Stand-in bitrate for a rendition whose real length isn't recorded yet,
         * used only to rank copies against each other in [headSecondsOf]. About
         * 160kbps, the middle of what the streams in play here run at.
         */
        const val ASSUMED_BYTES_PER_SECOND = 20_000.0

        /**
         * The least decoded audio a head-only tempo estimate is allowed to rest
         * on; see [ANALYSIS_HEAD_SECONDS], which is the same number and is the
         * one the cache reads.
         */
        const val MIN_HEAD_SECONDS = ANALYSIS_HEAD_SECONDS

        /**
         * How much more of the file has to be cached before the head is worth
         * decoding again. Doubling bounds the attempts to a handful over a whole
         * download while still catching up quickly on a high-bitrate rendition
         * whose first attempt covered only a few seconds.
         */
        const val HEAD_RETRY_GROWTH = 2
    }
}
