/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard),
 * scoped to Phase 1: the DSP-only pass (native/analyzer/audio_analysis.cpp),
 * with no beat-tracking or vocal-separation model in the loop yet.
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

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.AudioCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

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
class TrackAnalyzer(private val cache: AudioCache) {

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis").apply {
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

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight.
     *
     * Requires the track to be fully cached: a partially fetched file may not
     * even have a parsable container, and analysing the head of a track whose
     * tail hasn't arrived would produce a grid for audio the listener will
     * never reach through this transition. Callers re-request as caching
     * progresses; this is cheap to call repeatedly.
     */
    fun request(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (results.containsKey(trackId) || trackId in running) return
        if (!cache.isFullyCached(uri)) return
        if (!running.add(trackId)) return

        executor.execute {
            try {
                results[trackId] = analyze(trackId, uri, durationSeconds)
            } catch (error: Throwable) {
                // Throwable, not Exception: decode leans on MediaCodec, and an
                // OOM or a codec-level Error uncaught on a pool thread that is
                // nobody's parent takes the whole app down for work whose
                // entire failure mode is meant to be "this track goes
                // unanalysed".
                Log.w(TAG, "Analysis of $trackId failed", error)
                // Recorded as ready-but-empty so a track that cannot be
                // analysed is not retried on every tick for the rest of the
                // session.
                results[trackId] = TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = trackId,
                    duration = durationSeconds,
                )
            } finally {
                running.remove(trackId)
            }
        }
    }

    private fun analyze(trackId: String, uri: Uri, durationSeconds: Double): TrackAnalysis {
        fun openSource() = cache.mediaDataSource(uri)

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: cached media has no duration")
            return empty(trackId, 0.0)
        }

        // Phase 1 is DSP-only, and the analyzer needs the whole track — the
        // energy curve, phrase structure and mix-out anchor all read the tail,
        // not just a window of it — at its own low sample rate, so this is a
        // much smaller decode than a full-rate stereo pass would be.
        val structRate = TrackFeatures.sampleRate
        val decoded = openSource()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) }
            ?: return empty(trackId, effectiveDuration)
        val (pcm, _) = decoded
        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate)
                ?: return empty(trackId, effectiveDuration)
        } else {
            pcm.samples
        }

        val features = TrackFeatures.analyze(samples, effectiveDuration)
            ?: return empty(trackId, effectiveDuration)

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${features.bpm} conf=${features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
            bpm = features.bpm,
            beatInterval = features.beatInterval,
            beatConfidence = features.beatConfidence,
            downbeats = features.downbeats,
            firstBeat = features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
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

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    fun release() {
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"
    }
}
