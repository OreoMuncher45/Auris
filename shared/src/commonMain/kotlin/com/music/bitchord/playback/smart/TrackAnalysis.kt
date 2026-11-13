/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

/**
 * Stored offline analysis for one track, in that track's own timeline seconds.
 *
 * This is the contract between [TrackAnalyzer] and the transition policy.
 * Nothing here is PCM: the policy and planner read these fields and their
 * confidences, and never touch audio. Every field is optional, because the
 * ladder in [assessTransitionTier] is built to degrade on missing evidence
 * rather than to require it; an all-defaults instance is a legitimate input
 * that simply lands on the bottom rung.
 *
 * Phase 1 fills every field from DSP alone (see native/analyzer/), so
 * [beatConfidence] and [vocalActivityMask] are heuristics rather than a
 * trained model's output — the policy already treats them with the same
 * scrutiny it would a model that failed to load.
 */
data class TrackAnalysis(
    /**
     * Blank means "no status was reported", which counts as ready. Any other
     * value must be [STATUS_READY] for the planner to trust the rest of the
     * fields.
     */
    val status: String = "",
    /** Guards against a stale analysis being paired with the wrong track. */
    val trackId: String = "",
    val duration: Double = 0.0,

    val bpm: Double = 0.0,
    /**
     * Seconds per beat. Redundant with [bpm], but the analyzer measures it
     * directly and it survives tempo drift better, so it is preferred
     * wherever both are available.
     */
    val beatInterval: Double = 0.0,
    /**
     * How far the beat grid can be trusted, 0..1. A catalog tempo lookup
     * merges in at 0, so a metadata BPM alone can never authorize
     * beat-matching.
     */
    val beatConfidence: Double = 0.0,
    val downbeats: List<Double> = emptyList(),
    val phraseBoundaries: List<Double> = emptyList(),
    val firstBeat: Double = 0.0,

    val key: String = "",
    val keyConfidence: Double = 0.0,

    /** Where the file starts making sound, and where the first musical event lands. */
    val audibleStartTime: Double? = null,
    val pickupTime: Double? = null,
    val introEndTime: Double = 0.0,
    /** Where the content actually ends, excluding trailing silence. */
    val contentEndTime: Double = 0.0,
    val outroStartTime: Double = 0.0,

    val mixInTime: Double = 0.0,
    val mixOutTime: Double = 0.0,
    val mixInCandidates: List<MixCandidate> = emptyList(),
    val mixOutCandidates: List<MixCandidate> = emptyList(),

    val energyCurve: List<EnergySample> = emptyList(),
    /**
     * The energy curve of the track's **opening**, measured by a head-only
     * pass.
     *
     * Kept apart from [energyCurve] on purpose. The head pass measures the
     * envelope of the window it decoded, which is real evidence — but it is
     * evidence about the first half-minute and nothing else, and the policy
     * indexes the vocal mask against the curve and counts audible seconds
     * from it. A curve that stops at thirty seconds would have this track's
     * *outgoing* half scored against a window it does not cover, which is
     * how a head pass came to describe an ending it had never heard.
     *
     * So the policy keeps reading [energyCurve], which only the whole-track
     * pass fills, and this one is for the panel: a lane that has nothing in
     * it is worse than a lane with the opening drawn in it and the rest
     * honestly blank. The times are the track's own, so the panel draws this
     * curve exactly where it belongs.
     */
    val headEnergyCurve: List<EnergySample> = emptyList(),
    /**
     * The energy curve of the track's **ending**, measured by a tail-only pass.
     *
     * The other half of [headEnergyCurve], and it exists for the same reason
     * its sibling does: a streamed track's last seconds are not on disk until
     * the track has been played through, so the whole-track pass — the only
     * thing that used to fill [energyCurve] — lands after the transition it was
     * meant to describe, if it lands at all.
     *
     * The Automix panel draws the outgoing track over the *end* of its own
     * timeline, so an opening-only curve is drawn entirely off-screen and the
     * lane reads as empty however much of the opening was measured. This is the
     * half that lands in that view, and the times are the track's own, so it
     * lands exactly where the audio is.
     *
     * Kept out of [energyCurve] for the reason [headEnergyCurve] documents from
     * the other end: the policy counts audible seconds from that curve, and a
     * curve that covers only the last half-minute would describe a whole track
     * from its outro. The policy reads the whole-track fields the tail pass
     * fills in — [contentEndTime], [outroStartTime], [mixOutCandidates] — which
     * are the measurements a streamed track used to go into its transition
     * without.
     */
    val tailEnergyCurve: List<EnergySample> = emptyList(),
    /**
     * The **peaks** of the track's whole envelope, measured from the decoded
     * samples.
     *
     * The curve above is an RMS level per quarter-second: the right shape for
     * "how loud is this part of the song", and the wrong shape for a picture
     * of the sound. Drawn as it stands it reads as a bar chart — flat-topped
     * blocks on the curve's own grid, with the transients nowhere in it.
     *
     * This is the waveform's own data: the loudest instant in each short
     * window of the decoded audio, which is what the eye reads as the shape
     * of a sound. It is measured, not derived, so it has the transients.
     *
     * Not persisted: it is a great deal of numbers for a picture, and the
     * analysis that produced them is cheap to repeat.
     */
    val peakCurve: List<EnergySample> = emptyList(),
    /**
     * The peaks of the track's **opening**, measured by a head-only pass.
     *
     * The peak-curve counterpart of [headEnergyCurve], and kept apart from
     * [peakCurve] for the same reason: it describes the first window and
     * nothing else.
     */
    val headPeakCurve: List<EnergySample> = emptyList(),
    /**
     * The peaks of the track's **ending**, measured by a tail-only pass.
     *
     * The peak-curve counterpart of [tailEnergyCurve]. This is the half the
     * outgoing lane is drawn from, and it is the half that was missing: the
     * outgoing track is drawn over the *end* of its own timeline, so a curve
     * that covers only the opening lands entirely off-screen and the lane
     * reads as empty however much of the opening was measured.
     */
    val tailPeakCurve: List<EnergySample> = emptyList(),
    /** Low-band energy, present only when the analyzer ran a band split. Drives the bass swap. */
    val lowEnergyCurve: List<EnergySample> = emptyList(),
    /**
     * Per-sample vocal activity, indexed against [energyCurve] sample times.
     * Empty, or any length other than the energy curve's, means "no
     * evidence", which never blocks a transition.
     */
    val vocalActivityMask: List<Double> = emptyList(),
    /** Whole-track vocal likelihood, distinct from the per-sample [vocalActivityMask]. */
    val vocalProbability: Double = 0.0,
) {
    /**
     * Whether this analysis actually describes a track, as opposed to standing
     * in for one that has not been analysed or could not be.
     *
     * Both no-analysis states have to be excluded, and they look different: a
     * track nothing has looked at yet has a blank [status], while one whose
     * decode failed is recorded [STATUS_READY] with every field at its default
     * so it is not retried forever. A zero [bpm] is what separates the second
     * from a real result — and it is also the threshold the policy uses, since
     * a tempo outside 40–220 drops a pairing to a plain crossfade anyway.
     */
    val isUsable: Boolean get() = status == STATUS_READY && bpm > 0

    companion object {
        const val STATUS_READY = "ready"
    }
}

/** One point on an energy curve. [energy] is in whatever scale the analyzer chose. */
data class EnergySample(val time: Double, val energy: Double)

/**
 * The envelope the Automix panel draws for a track.
 *
 * The whole track's curve where one has been measured, and otherwise the two
 * *windows* that have — the opening from a head pass and the ending from a tail
 * pass — in the track's own seconds.
 *
 * ## Why the windows are drawn together
 *
 * A streamed track is measured in pieces, and the piece that matters for the
 * lower-quality case is not always the one that arrived first. The panel draws
 * the outgoing track across the *end* of its own timeline, so a curve covering
 * only the opening lands entirely off-screen and the lane reads as empty however
 * much of the opening was measured — which is what "the analysis found the key
 * and the BPM but the waveform is blank" was.
 *
 * The two windows are disjoint in time, which is what makes stitching them
 * honest: the music at either end of the track is drawn where it is, and the
 * middle — the part nobody has measured — is left blank rather than
 * interpolated.
 */
val TrackAnalysis.panelEnergyCurve: List<EnergySample>
    get() = when {
        energyCurve.isNotEmpty() -> energyCurve
        headEnergyCurve.isEmpty() && tailEnergyCurve.isEmpty() -> emptyList()
        else -> (headEnergyCurve + tailEnergyCurve).sortedBy { it.time }
    }

/**
 * The envelope the Automix panel draws for a track, as peaks.
 *
 * The whole track's peaks where one has been measured, and otherwise the two
 * *windows* that have — the opening from a head pass and the ending from a
 * tail pass — in the track's own seconds.
 *
 * The same shape as [panelEnergyCurve] and for the same reasons: a streamed
 * track is measured in pieces, and the piece that lands in the panel's view
 * is not always the one that arrived first.
 *
 * Empty when nothing has been measured at the peak resolution, in which case
 * the caller falls back to [panelEnergyCurve] — a coarse drawing beats none.
 */
val TrackAnalysis.panelPeakCurve: List<EnergySample>
    get() = when {
        peakCurve.isNotEmpty() -> peakCurve
        headPeakCurve.isEmpty() && tailPeakCurve.isEmpty() -> emptyList()
        else -> (headPeakCurve + tailPeakCurve).sortedBy { it.time }
    }

/**
 * The curve the panel draws, forced to reach both ends of the track.
 *
 * ## Why this exists
 *
 * With a tempo and a key on screen, a lane that draws nothing is not an honest
 * "not measured yet" — it reads as a bug, and it was reported as one repeatedly:
 * the analysis found both numbers, so the lane was expected to have a shape.
 * The measurement really can cover only a window: a head pass owns the opening,
 * a tail pass the ending, and a restored analysis has the whole energy curve
 * but no peaks. Drawn over the *end* of a track — which is where the outgoing
 * lane lives — an opening-only curve is entirely off-screen.
 *
 * So the measured curve is kept wherever it exists and the unmeasured stretches
 * are filled from a deterministic envelope built out of the analysis's own beat
 * grid: a decaying hit every [TrackAnalysis.beatInterval] seconds, swelling over
 * a sixteen-beat bar. That is not a claim about the audio; it is a beat-aligned
 * stand-in, and the policy never reads it. The panel's numbers, snapping and
 * transition decisions all still come from the measured fields.
 *
 * Nothing is invented without a tempo: with `bpm == 0` there is no analysis to
 * stand in for, and the caller gets [panelPeakCurve] back unchanged.
 *
 * @param measured the measured curve to preserve. Defaults to
 *   [panelPeakCurve]; callers with an energy fallback pass that instead, so a
 *   restored whole-track curve is not replaced by a placeholder.
 * @param step spacing of the filled grid, in seconds.
 */
fun TrackAnalysis.enforcedWaveformCurve(
    measured: List<EnergySample> = panelPeakCurve,
    step: Double = 0.10,
): List<EnergySample> {
    if (bpm <= 0.0) return measured
    val span = when {
        duration > 0.0 -> duration
        contentEndTime > 0.0 -> contentEndTime
        else -> return measured
    }
    if (span <= 0.0) return measured
    // A curve that already covers most of the track is the track's own shape;
    // filling it would only coarsen what was measured.
    val measuredSeconds = run {
        var total = 0.0
        for (index in 1 until measured.size) {
            val delta = measured[index].time - measured[index - 1].time
            if (delta > 0.0 && delta <= 1.0) total += delta
        }
        total
    }
    if (measuredSeconds >= span * 0.6) return measured

    val safeStep = step.coerceAtLeast(0.02)
    // Cells are keyed by the *nearest* slot rather than the truncated one, and
    // the filled grid is indexed rather than accumulated: `time += 0.1` drifts,
    // and a measured peak that lands in the neighbouring slot is a peak the
    // panel would replace with a stand-in.
    fun cellOf(time: Double): Int = kotlin.math.round(time / safeStep).toInt()
    val cells = HashMap<Int, Double>()
    measured.forEach { sample ->
        if (sample.time < 0.0 || sample.time > span || !sample.energy.isFinite()) return@forEach
        val value = sample.energy.coerceAtLeast(0.0)
        val cell = cellOf(sample.time)
        cells[cell] = maxOf(cells[cell] ?: 0.0, value)
    }
    val beat = beatInterval.takeIf { it > 0.01 } ?: (60.0 / bpm)
    val first = firstBeat
    val slots = kotlin.math.ceil(span / safeStep).toInt().coerceAtLeast(1)
    val out = ArrayList<EnergySample>(slots + 1)
    for (index in 0..slots) {
        val time = index * safeStep
        val value = cells[index] ?: placeholderEnergy(time, beat, first)
        out += EnergySample(time, value)
    }
    return out
}

/**
 * A beat-aligned stand-in for a stretch nobody has measured, in 0..1.
 *
 * A short decaying hit on every beat, a slow sixteen-beat swell across the
 * bar, and a still slower breathing shape over it — enough that the drawing
 * reads as music under a cursor rather than as a flat block, and entirely
 * derived from numbers the analysis already stated.
 */
private fun placeholderEnergy(time: Double, beat: Double, firstBeat: Double): Double {
    val beats = (time - firstBeat) / beat
    val phase = beats - kotlin.math.floor(beats)
    val hit = kotlin.math.exp(-phase * 5.5)
    val bar = 0.74 + 0.26 * kotlin.math.sin(2.0 * kotlin.math.PI * beats / 16.0)
    val breath = 0.88 + 0.12 * kotlin.math.sin(2.0 * kotlin.math.PI * beats / 64.0 + 1.3)
    return (0.20 + 0.62 * hit) * bar * breath
}

/**
 * A partial pass's answer merged into another's.
 *
 * The head pass measures a track's entry and the tail pass its exit, and both
 * are published while the track is still playing — so the panel is drawn from
 * whichever has landed, then from both. Each field is taken from the half that
 * measured it, with `this` winning where both did: the head pass is where the
 * policy's entry numbers come from, and a tail window's own tempo estimate is
 * measured over thirty seconds of an outro, which is the least representative
 * music in the track.
 *
 * The two beat grids are kept *together* rather than chosen between. They are
 * disjoint in time, and the panel draws them along the edges of its lanes so a
 * handoff can be snapped to the grid of the music it actually lands on.
 *
 * Order matters, and deliberately: `this` is the analysis whose measurements win
 * where both halves have one. Callers merge the head pass into the tail pass —
 * see [TrackAnalyzer] — so a note's tempo is never taken from thirty seconds of
 * an outro over the opening's own estimate.
 */
fun TrackAnalysis.mergedWith(arriving: TrackAnalysis): TrackAnalysis = copy(
    status = TrackAnalysis.STATUS_READY,
    trackId = trackId,
    duration = duration.takeIf { it > 0.0 } ?: arriving.duration,
    bpm = bpm.takeIf { it > 0.0 } ?: arriving.bpm,
    beatInterval = beatInterval.takeIf { it > 0.0 } ?: arriving.beatInterval,
    beatConfidence = maxOf(beatConfidence, arriving.beatConfidence),
    firstBeat = firstBeat.takeIf { it > 0.0 } ?: arriving.firstBeat,
    downbeats = (downbeats + arriving.downbeats).distinct().sorted(),
    phraseBoundaries = phraseBoundaries.ifEmpty { arriving.phraseBoundaries },
    key = key.ifBlank { arriving.key },
    keyConfidence = maxOf(keyConfidence, arriving.keyConfidence),
    audibleStartTime = audibleStartTime ?: arriving.audibleStartTime,
    pickupTime = pickupTime ?: arriving.pickupTime,
    introEndTime = introEndTime.takeIf { it > 0.0 } ?: arriving.introEndTime,
    contentEndTime = contentEndTime.takeIf { it > 0.0 } ?: arriving.contentEndTime,
    outroStartTime = outroStartTime.takeIf { it > 0.0 } ?: arriving.outroStartTime,
    mixInTime = mixInTime.takeIf { it > 0.0 } ?: arriving.mixInTime,
    mixOutTime = mixOutTime.takeIf { it > 0.0 } ?: arriving.mixOutTime,
    mixInCandidates = mixInCandidates.ifEmpty { arriving.mixInCandidates },
    mixOutCandidates = mixOutCandidates.ifEmpty { arriving.mixOutCandidates },
    headEnergyCurve = headEnergyCurve.ifEmpty { arriving.headEnergyCurve },
    tailEnergyCurve = tailEnergyCurve.ifEmpty { arriving.tailEnergyCurve },
    peakCurve = peakCurve.ifEmpty { arriving.peakCurve },
    headPeakCurve = headPeakCurve.ifEmpty { arriving.headPeakCurve },
    tailPeakCurve = tailPeakCurve.ifEmpty { arriving.tailPeakCurve },
)

/**
 * A candidate point for a transition to enter or leave on. [score] is the
 * analyzer's own confidence; [type] is what it recognized, and carries its
 * own weight during ranking.
 */
data class MixCandidate(val time: Double, val score: Double, val type: String)

/** A ranked [MixCandidate], carrying the score the policy actually ordered it by. */
data class RankedMixCandidate(
    val time: Double,
    val score: Double,
    val type: String,
    val rankScore: Double,
    /**
     * Seconds of audible music this candidate would skip by ending the
     * transition before the content does. Mix-out only; always 0 for mix-in.
     */
    val discardedMusicSeconds: Double = 0.0,
    /** False when there was no energy curve and [discardedMusicSeconds] is the raw gap instead. */
    val measured: Boolean = true,
)

/** Where a transition should end on the outgoing track, and what it costs to end there. */
data class MixOutAnchor(
    val time: Double,
    val type: String,
    val discardedMusicSeconds: Double,
)

/** The verdict on how ambitious a transition the stored evidence supports. */
data class TransitionPolicyVerdict(
    val tier: TransitionTier,
    /** Ordered most-disqualifying first, so `reasons.first()` is the routing verdict. */
    val reasons: List<String>,
    val beatConfidence: Double,
)

/**
 * The degradation ladder. Ambition falls in explicit steps as certainty does,
 * rather than letting one engine quietly do beat math on junk data.
 */
enum class TransitionTier {
    /** Both grids trusted and the tempi sit within the transparent stretch window. */
    BEATMATCHED,

    /** Beat-quantized anchors and EQ handoffs are allowed; time-stretching is not. */
    DJ_ASSISTED,

    /** The evidence supports nothing beyond an equal-power fade at the analyzed anchor. */
    PLAIN_CROSSFADE,
}
