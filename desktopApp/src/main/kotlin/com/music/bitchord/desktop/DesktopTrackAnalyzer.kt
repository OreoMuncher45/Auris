package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackFeatures
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A file on this machine — a download or a local track — rather than anything streamed. */
internal val DesktopStream.isLocalFile: Boolean
    get() = !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)

/**
 * Produces the evidence Automix mixes on: tempo, beat grid, key, structure and where a track can be
 * entered and left.
 */
internal class DesktopTrackAnalyzer(
    /**
     * How much CPU the listener has allowed background analysis, read fresh on every pass so a
     * change takes effect on the next track rather than the next launch.
     */
    private val performance: () -> AutomixPerformanceMode = { AutomixPerformanceMode.BALANCED },
    /**
     * True while analysis has no use — in a party, where Automix is off. Checked on request, before
     * a queued pass starts, and between and during its decodes, as Android's `TrackAnalyzer` does:
     * a request made a moment before the party started would otherwise run a whole-track decode
     * and a model pass for a transition that cannot happen. A stopped pass records nothing.
     */
    private val stopped: () -> Boolean = { false },
    private val onAnalysed: (String) -> Unit = {},
) {
    /** One analysis at a time, on a thread of this class's own making. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "BitChord-Analysis", ANALYSIS_STACK_BYTES).apply {
            isDaemon = true
        }
    }

    /** Analyses kept between runs, so a track measured yesterday is not measured again today. */
    private val store = AnalysisStore { DesktopAnalysisRuntime.analysisHome() }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Tracks the analyser could not measure. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** When each of [failed] last failed, so it is retried on a cooldown rather than every tick. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Said once, not once a tick. */
    private val warnedNoAnalyser = java.util.concurrent.atomic.AtomicBoolean(false)

    private val beats by lazy {
        BeatTracker(
            modelPath = { DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET) },
            inferenceThreads = { performance().inferenceThreads },
        )
    }

    /** What is known now, never a computation — the planner asks this every tick. */
    fun analysisFor(trackId: String): TrackAnalysis =
        results[trackId] ?: restored(trackId) ?: TrackAnalysis(trackId = trackId)

    /** A stored analysis, promoted into memory on first ask. */
    private fun restored(trackId: String): TrackAnalysis? =
        store.load(trackId)?.also { results[trackId] = it }

    fun isAnalysed(trackId: String): Boolean =
        results.containsKey(trackId) || restored(trackId) != null

    /** How far this track has got, for the player's Automix line. */
    fun stateFor(trackId: String): TrackAnalysisState = when {
        trackId.isBlank() -> TrackAnalysisState.WAITING
        !DesktopAnalysisRuntime.available -> TrackAnalysisState.FAILED
        // Usable first and a pass in flight second, the order Android settled on.
        results[trackId]?.isUsable == true ->
            if (trackId in running) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        trackId in running -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyser saying it tried and got nothing, and that
        // it will not try again.
        trackId in failed || results.containsKey(trackId) -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /**
     * Asks for [song] to be analysed, if it has not been already.
     *
     * [stream] is the copy playback is using — a file on this machine, or the
     * addon's or JioSaavn's own stream. It is the only thing measured.
     *
     * A streamed track used to be measured on a YouTube Opus copy of the same
     * recording instead: the YouTube id was looked up, its stream fetched
     * outside the source race, and the measurement stored under the track's
     * own id. That made Automix depend on YouTube to measure a track YouTube
     * had nothing to do with — with YouTube switched off there was no copy to
     * measure at all, so every addon-served track went into its transition
     * unanalysed — and it made the evidence describe a different rendition of
     * the song than the one under the listener.
     *
     * A track with no stream of its own yet is not measured and not written
     * off: the engine asks again as soon as it has one, which is the tick
     * after the source answers.
     */
    fun request(song: Song, stream: DesktopStream?, durationSeconds: Double) {
        val trackId = song.videoId
        if (trackId.isBlank()) return
        if (durationSeconds <= 0) return
        if (stopped()) return
        if (!DesktopAnalysisRuntime.available) {
            if (warnedNoAnalyser.compareAndSet(false, true)) {
                DesktopTrackLog.log("automix: this build has no analyser, so nothing will be measured")
            }
            return
        }
        if (results.containsKey(trackId) || restored(trackId) != null) return
        // Asked every tick; a track that just failed is not resolved and decoded again each time.
        failedAt[trackId]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return }
        // No stream of its own yet: nothing to measure, and nothing to record
        // as a failure either — the engine asks again once the source answers.
        val source = stream ?: return
        if (!running.add(trackId)) return
        failed.remove(trackId)
        DesktopTrackLog.log(
            "automix: analysing '${song.title}' (${"%.0f".format(durationSeconds)}s)" +
                if (source.isLocalFile) " from the file" else " from the copy being played",
        )

        worker.execute {
            run {
                if (stopped()) {
                    running.remove(trackId)
                    return@execute
                }
                // Android's rule: the lowest rung yields to playback rather than competing for a
                // core, and the thread count stays the speed knob for the other two.
                Thread.currentThread().priority =
                    if (performance().yieldsToPlayback) Thread.MIN_PRIORITY else Thread.NORM_PRIORITY
                val analysis = runCatching {
                    analyse(trackId, source, durationSeconds)
                }
                    .onFailure { DesktopTrackLog.log("analysis of '${song.title}' failed: ${it.message}") }
                    .getOrNull()
                if (analysis == null && stopped()) {
                    // Called off, not failed: it is free to be measured once the party ends.
                    running.remove(trackId)
                    return@execute
                }
                if (analysis == null) {
                    failed += trackId
                    failedAt[trackId] = System.currentTimeMillis()
                }
                if (analysis != null) {
                    failedAt.remove(trackId)
                    store.save(trackId, analysis)
                    results[trackId] = analysis
                    DesktopTrackLog.log(
                        "analysed '${song.title}': ${"%.1f".format(analysis.bpm)} bpm" +
                            ", ${analysis.downbeats.size} downbeats" +
                            ", key ${analysis.key.ifBlank { "unknown" }}",
                    )
                    onAnalysed(trackId)
                }
                running.remove(trackId)
            }
        }
    }

    private fun analyse(
        trackId: String,
        stream: DesktopStream,
        durationSeconds: Double,
    ): TrackAnalysis? {
        val features = decode(stream, TrackFeatures.sampleRate)
            ?.let { TrackFeatures.analyze(it, durationSeconds) }
            ?: return null

        if (stopped()) return null
        // The model runs at its own rate, so it gets its own decode rather than a resample of the
        // analyser's.
        val grid = decode(stream, MelSpectrogram.sampleRate)?.let { beats.track(it) }

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            // The grid wins where it has an opinion: a trained model reads meter, which
            // autocorrelation cannot.
            bpm = grid?.bpm ?: features.bpm,
            beatInterval = grid?.beatInterval ?: features.beatInterval,
            beatConfidence = grid?.beatConfidence ?: features.beatConfidence,
            downbeats = grid?.downbeats ?: features.downbeats,
            firstBeat = grid?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            contentEndTime = features.contentEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = features.vocalActivityMask,
            vocalProbability = features.vocalProbability,
        )
    }

    /** The whole track as mono float at [rate]. */
    private fun decode(stream: DesktopStream, rate: Double): FloatArray? {
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(
            url = stream.url,
            headers = stream.headers,
            requested = DesktopPcmFormat(rate.toInt(), channels = 1, bytesPerSample = 4, isFloat = true),
            windowed = stream.windowedReads,
            transport = stream.transport,
        )
        if (opened.isFailure) {
            decoder.close()
            return null
        }
        return try {
            val out = ArrayList<FloatArray>()
            var total = 0
            while (true) {
                if (stopped()) return null
                val block = decoder.readSamples() ?: break
                val count = decoder.sampleCount
                if (count <= 0) continue
                out += block.copyOf(count)
                total += count
                if (total > MAX_SAMPLES) break
            }
            if (total == 0) return null
            val samples = FloatArray(total)
            var at = 0
            for (block in out) {
                block.copyInto(samples, at)
                at += block.size
            }
            samples
        } finally {
            decoder.close()
        }
    }

    fun release() {
        worker.shutdownNow()
        results.clear()
        running.clear()
        failed.clear()
        failedAt.clear()
    }

    internal companion object {

        /** How long a track that failed to be measured waits before it is tried again. */
        const val RETRY_AFTER_MS = 60_000L

        /**
         * A ceiling of about twenty minutes at the analyser's rate, so a mis-tagged eight-hour
         * upload cannot eat the heap.
         */
        const val MAX_SAMPLES = 20 * 60 * 11_025

        /** Stack for the analysis thread. */
        const val ANALYSIS_STACK_BYTES = 64L * 1024 * 1024
    }
}

/** The queue facts the shared planner needs, from a [Song]. */
internal fun Song.transitionInfo(durationMs: Long) = com.music.bitchord.playback.smart.TransitionTrackInfo(
    id = videoId,
    durationMs = durationMs,
    title = title,
    artist = artist,
    album = albumName.orEmpty(),
    albumId = albumId.orEmpty(),
)

/** "3:27" as seconds, or zero when there is nothing to read. */
internal fun String?.durationSeconds(): Double {
    val parts = this?.split(':')?.mapNotNull { it.trim().toIntOrNull() } ?: return 0.0
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]).toDouble()
        3 -> (parts[0] * 3_600 + parts[1] * 60 + parts[2]).toDouble()
        else -> 0.0
    }
}
