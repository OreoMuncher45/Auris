package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.LoudnessMeter
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Brings every track to the same perceived level, by measuring what is playing
 * and applying one constant gain to it.
 *
 * ## One scalar, not a leveller
 *
 * The gain is a single number per track. It is not a compressor, it does not
 * ride the signal, and it never reacts to a chorus arriving — multiplying
 * every sample by the same constant leaves the recording's dynamics exactly as
 * they were mastered, which is the difference between normalizing a track and
 * squashing it. What the constant is worth is decided by
 * [LoudnessMeter]'s gated integrated loudness against [targetLufs].
 *
 * The one thing that does move it is [LoudnessMeter.peak], and only downwards:
 * see [recomputeTarget].
 *
 * ## Where the number comes from, and when
 *
 * Two paths, and they behave visibly differently:
 *
 * - **Known track** — [prime] was handed a cached gain, so it applies from the
 *   first frame and the meter never runs. A gain measured on a previous play
 *   describes the same recording, so re-deriving it would spend CPU to arrive
 *   back where it started.
 * - **New track** — the meter runs, no gain is applied until it has heard
 *   [LoudnessMeter.MIN_BLOCKS] worth of audio, and the gain then glides in over
 *   a few hundred milliseconds. The measurement keeps refining as the track
 *   plays and [snapshot] keeps offering the better figure, which is what fills
 *   the cache for next time.
 *
 * ## Surviving a quality upgrade
 *
 * A mid-track upgrade to JioSaavn or an addon replaces the media item under a
 * playing track, which reconfigures the sink and therefore this processor. It
 * must not sound like anything. [prime] is idempotent for a track already
 * being handled — re-priming with the same key leaves the meter, the measured
 * gain and the glide exactly where they were — and [configure] keeps the
 * meter's history across the rate change the new rendition usually brings. So
 * the swap's short break in audio is all the listener hears; the level on the
 * other side of it is the level that went in.
 *
 * ## Threading
 *
 * [process], [configure], [flush] and [reset] are the audio thread's.
 * [prime], [enabled] and [targetLufs] are written from the main thread and
 * picked up at the top of the next [process]; [snapshot] is read from the main
 * thread and is allowed to be a block stale. Nothing is synchronized and
 * nothing allocates in steady state.
 */
class LoudnessProcessor : FloatAudioProcessor {

    /** A track and what is already known about it, handed over from the main thread. */
    private class Prime(val key: String, val cachedGainDb: Float?)

    /** What the processor is doing right now, for the cache and for telemetry. */
    data class Snapshot(
        val trackKey: String?,
        /** The gain actually multiplying samples at this instant. */
        val appliedGainDb: Float,
        /** Measured integrated loudness, or null on a cache hit or while still listening. */
        val measuredLufs: Float?,
        /** Highest absolute sample seen so far, 0 when nothing has been measured. */
        val peak: Float,
        /** Seconds of audio the measurement has heard. */
        val measuredSeconds: Double,
        /** Whether the gain came from the store rather than from this play. */
        val fromCache: Boolean,
    )

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var targetLufs: Float = DEFAULT_TARGET_LUFS

    @Volatile
    private var pendingPrime: Prime? = null

    @Volatile
    var snapshot: Snapshot = Snapshot(null, 0f, null, 0f, 0.0, false)
        private set

    private val meter = LoudnessMeter()

    private var activeKey: String? = null
    private var metering = false
    private var fromCache = false

    /** Linear gain the samples are being multiplied by, and what it is heading for. */
    private var currentGain = 1f
    private var targetGain = 1f

    /** Per-sample one-pole coefficient, derived from the rate in [configure]. */
    private var glide = 0f

    private var sampleRate = 0
    private var channelCount = 0

    /** Blocks processed since the target was last recomputed. */
    private var sinceRecompute = 0

    /**
     * Declares which track is playing and what is already known about its
     * level.
     *
     * Re-priming with the key already in hand is a no-op by design — that is
     * what a quality upgrade looks like from here, and it must not disturb a
     * measurement or a gain in flight. A different key is a real change of
     * track and clears everything.
     */
    fun prime(key: String, cachedGainDb: Float?) {
        pendingPrime = Prime(key, cachedGainDb)
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        if (sampleRate > 0) {
            meter.configure(sampleRate, channelCount)
            glide = (1.0 - exp(-1.0 / (GLIDE_TAU_SECONDS * sampleRate))).toFloat()
        }
    }

    override fun process(block: AudioBlock) {
        applyPendingPrime()

        val frameCount = block.frameCount
        if (frameCount == 0 || channelCount < 1 || sampleRate <= 0) return

        val sampleCount = frameCount * channelCount

        if (metering && enabled) {
            meter.process(block.samples, frameCount)
            if (++sinceRecompute >= RECOMPUTE_EVERY_BLOCKS) {
                sinceRecompute = 0
                recomputeTarget()
            }
        }

        // Off is a glide to unity rather than a bypass, so switching the
        // feature off mid-track releases the gain the way every other control
        // here releases one instead of stepping the level.
        val aim = if (enabled) targetGain else 1f

        // The common steady state: nothing to move towards and nothing being
        // applied. Costs one comparison and touches no samples, which is what
        // keeps a disabled or unity-gain track bit-transparent through here.
        if (aim == 1f && currentGain == 1f) return

        var gain = currentGain
        val rate = glide
        for (i in 0 until sampleCount) {
            gain += (aim - gain) * rate
            block.samples[i] *= gain
        }
        // Snap once the glide is within a hair of its aim, so `currentGain`
        // reaches exactly 1f and the fast path above can engage again.
        currentGain = if (kotlin.math.abs(aim - gain) < GLIDE_SETTLED) aim else gain
    }

    private fun applyPendingPrime() {
        val prime = pendingPrime ?: return
        pendingPrime = null
        if (prime.key == activeKey) return

        activeKey = prime.key
        meter.reset()
        sinceRecompute = 0

        val cached = prime.cachedGainDb
        if (cached != null) {
            fromCache = true
            metering = false
            targetGain = 10f.pow(cached / 20f)
        } else {
            fromCache = false
            metering = true
            targetGain = 1f
        }
        // Snapped rather than glided, because a new track has nothing to glide
        // *from* — the level is either known before a frame plays or not known
        // at all, and a ramp would just make a known-good track open at the
        // wrong volume.
        //
        // Snapped to what [process] will actually aim at, not to [targetGain]:
        // with the feature switched off the aim is unity, and snapping to a
        // stored gain here would apply it to the opening of every track before
        // gliding back off it. That is audible, and it is audible precisely
        // when the listener has said they do not want this.
        currentGain = if (enabled) targetGain else 1f
        publishSnapshot()
    }

    /**
     * Turns the measurement into the gain to aim for.
     *
     * Two limits sit on top of the loudness figure. [MIN_GAIN_DB] and
     * [MAX_GAIN_DB] bound how far any one track may be moved, so a badly
     * measured or genuinely pathological recording cannot arrive at a level
     * nobody asked for. The peak limit is the one that matters in practice:
     * boosting a quiet track raises its peaks with it, and
     * [com.music.bitchord.playback.audio.PcmBoundary] clamps rather than wraps
     * at the encode, so an unchecked boost would turn into flat-topped samples
     * instead of a loud track. Capping the gain at [PEAK_CEILING] over the
     * highest sample seen keeps the loudest moment a dB inside full scale.
     *
     * The cap only ever tightens: the peak the meter reports never decreases,
     * so a chorus arriving with higher peaks than the verse that was measured
     * first pulls the gain down rather than clipping. Downwards is the safe
     * direction for that to move in, and at a few hundred milliseconds of
     * glide it is not a thing anyone hears.
     */
    private fun recomputeTarget() {
        val lufs = meter.integratedLufs ?: return
        val gainDb = (targetLufs - lufs).toFloat().coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        var gain = 10f.pow(gainDb / 20f)

        val peak = meter.peak
        if (peak > 0f) {
            gain = min(gain, PEAK_CEILING / peak)
        }
        targetGain = gain
        publishSnapshot()
    }

    /**
     * Republishes what the main thread reads.
     *
     * Called only when the answer actually changes — a new track, or a fresh
     * measurement — and never from the per-block path, because a [Snapshot]
     * is an allocation and the steady-state audio callback does not make
     * those. The consequence is that [Snapshot.appliedGainDb] names the gain
     * being *aimed at* rather than the instantaneous value part-way through a
     * glide, which is the right figure for both of its readers: the cache
     * wants the track's gain, and the pipeline readout wants what the track
     * settles at, not a number moving too fast to read.
     */
    private fun publishSnapshot() {
        snapshot = Snapshot(
            trackKey = activeKey,
            appliedGainDb = toDb(targetGain),
            measuredLufs = if (metering) meter.integratedLufs?.toFloat() else null,
            peak = meter.peak,
            measuredSeconds = if (metering) meter.measuredSeconds else 0.0,
            fromCache = fromCache,
        )
    }

    /**
     * A seek. Same recording, so the measurement behind the playhead still
     * describes it — only the filter memory and the part-filled block are
     * wrong, and those are all [LoudnessMeter.flushTransient] clears.
     */
    override fun flush() {
        meter.flushTransient()
    }

    /**
     * Deliberately *not* a teardown, despite the name the interface gives it.
     *
     * Which track is playing is the service's knowledge, not the sink's, and
     * [prime] is the only thing that may change it here. Media3 resets a sink
     * for reasons that have nothing to do with the queue — a renderer being
     * disabled and re-enabled, a format change, and in particular the
     * re-prepare that a mid-track quality upgrade performs — and forgetting
     * the track's gain on any of those would step the level in the middle of a
     * song. That is exactly the failure this feature has to not have: an
     * upgrade to JioSaavn or an addon is meant to be inaudible apart from the
     * break in the audio, and it cannot be if the correction restarts from
     * nothing on the other side of it.
     *
     * So this clears what is genuinely per-stream — filter memory, the
     * part-filled block — and keeps what is per-track. A processor left
     * holding a stale gain is harmless: nothing plays through it until the
     * service primes it, and priming a different track clears everything.
     */
    override fun reset() {
        meter.flushTransient()
    }

    /** A real teardown, for the service releasing the player that owns this. */
    fun forget() {
        meter.reset()
        activeKey = null
        metering = false
        fromCache = false
        currentGain = 1f
        targetGain = 1f
        sinceRecompute = 0
        publishSnapshot()
    }

    companion object {
        /**
         * Where tracks are brought to, in LUFS.
         *
         * -14 is what the streaming services normalize to — Spotify, YouTube,
         * Tidal and Amazon all sit at or within a decibel of it — which makes
         * it the level most listeners are already used to, and the level a
         * modern master (typically -8 to -10 LUFS) only ever has to come
         * *down* to. That direction matters: attenuation cannot clip, so the
         * default case needs no headroom argument at all.
         */
        const val DEFAULT_TARGET_LUFS = -14f

        /** Bounds on how far one track may be moved, whatever it measures. */
        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 12f

        /** -1 dBFS. What the loudest sample is allowed to reach after gain. */
        val PEAK_CEILING = 10f.pow(-1f / 20f)

        /** Glide time constant. Long enough to be inaudible, short enough to settle. */
        const val GLIDE_TAU_SECONDS = 0.25

        /** Close enough to the aim to snap, so the unity fast path can re-engage. */
        const val GLIDE_SETTLED = 1e-5f

        /** Recompute the target every few blocks rather than on every one. */
        const val RECOMPUTE_EVERY_BLOCKS = 8

        fun toDb(gain: Float): Float =
            if (gain <= 0f) MIN_GAIN_DB else (20.0 * log10(gain.toDouble())).toFloat()
    }
}
