package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.LoudnessMeter
import kotlin.math.log10
import kotlin.math.max
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
 * - **New track** — the meter runs, and the gain is applied as soon as the
 *   meter has a figure at all: [LoudnessMeter.EARLY_BLOCKS], seven hundred
 *   milliseconds in, while the track is still opening. The measurement keeps
 *   refining as the track plays and [snapshot] keeps offering the better
 *   figure, which is what fills the cache for next time.
 *
 * ## Why the level never steps
 *
 * A correction is only inaudible if the listener never hears it *arrive*, and
 * there are exactly two moments where that is true: before a frame has played,
 * and inside the track's first second, where nothing has yet been established
 * for the new level to contradict. So the gain moves at two speeds, and which
 * one it is using is the whole of the ramp logic in [process]:
 *
 * - **[OPENING_SECONDS] of a track, and a toggle of [enabled]** — [OPENING_SLEW_DB_PER_SECOND],
 *   fast enough to be done inside the opening. This is where the first
 *   measured gain lands, and it is deliberately the only fast move the feature
 *   ever makes.
 * - **Everything after** — [REFINE_SLEW_DB_PER_SECOND], a creep. The
 *   measurement goes on improving for the rest of the track, the peak cap goes
 *   on tightening, and a listener two minutes into a song would hear either of
 *   those as the volume moving if they arrived at any speed worth noticing.
 *   Half a decibel per second is under the rate at which a level change
 *   registers as one.
 *
 * The estimate the opening is corrected by is drawn from under a second of
 * audio, so it can be wrong — a hushed intro reads as a quiet recording. That
 * is what [PROVISIONAL_MAX_GAIN_DB] is for: until the meter is
 * [LoudnessMeter.trusted] the boost is held to a modest one, so the distance
 * the creep has to walk back when the band comes in is small.
 *
 * ## Surviving a quality upgrade
 *
 * A mid-track upgrade to JioSaavn or an addon replaces the media item under a
 * playing track, which reconfigures the sink and therefore this processor. It
 * must not sound like anything. [prime] is idempotent for a track already
 * being handled — re-priming with the same key leaves the meter, the measured
 * gain and the ramp exactly where they were — and [configure] keeps the
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

    /**
     * The highest gain the measured peak allows without clipping. A gain above
     * this is unsafe *now*, not eventually, so [process] leaves the creep for
     * as long as it takes to get back under it. See [recomputeTarget].
     */
    private var peakSafeGain = Float.MAX_VALUE

    /**
     * Per-sample multipliers for the two ramp speeds, derived from the rate
     * and the channel count in [configure]. A gain multiplied by one of these
     * once per sample moves at a fixed number of decibels per second, which is
     * the shape a level change has to have to go unnoticed — a one-pole glide
     * crosses most of its distance in its first instants, which is the part a
     * listener hears.
     */
    private var openingSlew = 1f
    private var refineSlew = 1f

    private var sampleRate = 0
    private var channelCount = 0

    /** Blocks processed since the target was last recomputed. */
    private var sinceRecompute = 0

    /** Frames of this track that have played, for the opening window. */
    private var trackFrames = 0L

    /** Frames in [OPENING_SECONDS] at the live rate. */
    private var openingFrames = 0L

    /** Whether a measured figure has been turned into a target yet. */
    private var hasMeasuredTarget = false

    /**
     * The value of [enabled] the ramp is currently working towards, so a
     * toggle can be told apart from a refinement and given the fast ramp.
     */
    private var rampingSetting = false
    private var appliedEnabled = true

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
        if (sampleRate > 0 && channelCount > 0) {
            meter.configure(sampleRate, channelCount)
            // Per sample, not per frame: the ramp advances once for every
            // value it multiplies, so a stereo stream would otherwise move at
            // twice the decibels per second a mono one does.
            val perSecond = sampleRate.toDouble() * channelCount
            openingSlew = 10.0.pow(OPENING_SLEW_DB_PER_SECOND / (20.0 * perSecond)).toFloat()
            refineSlew = 10.0.pow(REFINE_SLEW_DB_PER_SECOND / (20.0 * perSecond)).toFloat()
            openingFrames = (OPENING_SECONDS * sampleRate).toLong()
        }
    }

    override fun process(block: AudioBlock) {
        applyPendingPrime()

        val frameCount = block.frameCount
        if (frameCount == 0 || channelCount < 1 || sampleRate <= 0) return

        val sampleCount = frameCount * channelCount

        if (metering && enabled) {
            meter.process(block.samples, frameCount)
            // Every block until there is a target, so the first figure the
            // meter offers is acted on within a block or two of arriving
            // rather than up to [RECOMPUTE_EVERY_BLOCKS] later — the whole
            // point of the early figure is that it lands inside the opening.
            if (++sinceRecompute >= RECOMPUTE_EVERY_BLOCKS || !hasMeasuredTarget) {
                sinceRecompute = 0
                recomputeTarget()
            }
        }

        // Off is a ramp to unity rather than a bypass, so switching the
        // feature off mid-track releases the gain the way every other control
        // here releases one instead of stepping the level.
        val aim = if (enabled) targetGain else 1f
        if (enabled != appliedEnabled) {
            appliedEnabled = enabled
            rampingSetting = true
        }

        val opening = trackFrames < openingFrames
        trackFrames += frameCount

        // The common steady state: nothing to move towards and nothing being
        // applied. Costs one comparison and touches no samples, which is what
        // keeps a disabled or unity-gain track bit-transparent through here.
        if (aim == 1f && currentGain == 1f) {
            rampingSetting = false
            return
        }

        // A gain the peak cap has overtaken is clipping until it comes down,
        // so it comes down at the opening rate rather than creeping.
        val overPeak = enabled && currentGain > peakSafeGain
        val step = if (opening || rampingSetting || overPeak) openingSlew else refineSlew
        val rising = currentGain < aim
        val perSample = if (rising) step else 1f / step

        var gain = currentGain
        if (rising) {
            for (i in 0 until sampleCount) {
                gain = min(gain * perSample, aim)
                block.samples[i] *= gain
            }
        } else {
            for (i in 0 until sampleCount) {
                gain = max(gain * perSample, aim)
                block.samples[i] *= gain
            }
        }
        currentGain = gain
        if (gain == aim) rampingSetting = false
    }

    private fun applyPendingPrime() {
        val prime = pendingPrime ?: return
        pendingPrime = null
        if (prime.key == activeKey) return

        activeKey = prime.key
        meter.reset()
        sinceRecompute = 0
        trackFrames = 0
        hasMeasuredTarget = false
        rampingSetting = false
        appliedEnabled = enabled
        peakSafeGain = Float.MAX_VALUE

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
        // Snapped rather than ramped, because a new track has nothing to ramp
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
     * direction for that to move in.
     *
     * It is also the one move [process] does not creep towards. Everything
     * else the measurement changes can afford to take a few seconds, because
     * the cost of being a decibel out for those seconds is nothing; being a
     * decibel over the ceiling for them is a run of clipped transients. So
     * [peakSafeGain] is published here as well, and a gain currently above it
     * comes down at the opening rate until it is under it again — which is
     * only ever the excess, since [targetGain] already sits at or below the
     * cap.
     */
    private fun recomputeTarget() {
        val lufs = meter.integratedLufs ?: return
        hasMeasuredTarget = true
        // A boost asked for by an untrusted figure is held back, because the
        // figure it came from heard the opening and not the song: a quiet
        // intro reads as a quiet recording, and a +12 dB answer to one would
        // have to be walked back over the following minute. An attenuation is
        // not held back — it cannot clip, and a track that measures loud in
        // its first second is essentially never a quiet one.
        val ceiling = if (meter.trusted) MAX_GAIN_DB else PROVISIONAL_MAX_GAIN_DB
        val gainDb = (targetLufs - lufs).toFloat().coerceIn(MIN_GAIN_DB, ceiling)
        var gain = 10f.pow(gainDb / 20f)

        val peak = meter.peak
        if (peak > 0f) {
            peakSafeGain = PEAK_CEILING / peak
            gain = min(gain, peakSafeGain)
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
     * ramp, which is the right figure for both of its readers: the cache
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
        trackFrames = 0
        hasMeasuredTarget = false
        rampingSetting = false
        peakSafeGain = Float.MAX_VALUE
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

        /**
         * How long a track counts as still opening, and so how long a gain may
         * move fast. Comfortably past [LoudnessMeter.EARLY_BLOCKS], so the
         * first measured figure is applied at the fast rate and has settled
         * well inside it.
         */
        const val OPENING_SECONDS = 1.5

        /** The opening ramp: six decibels in a quarter of a second. */
        const val OPENING_SLEW_DB_PER_SECOND = 24.0

        /**
         * Everything after the opening. Slow enough that a refinement, or a
         * peak cap tightening under a chorus, reads as nothing at all rather
         * than as the volume moving.
         */
        const val REFINE_SLEW_DB_PER_SECOND = 0.5

        /** The most an untrusted opening estimate may boost by. */
        const val PROVISIONAL_MAX_GAIN_DB = 6f

        /** Recompute the target every few blocks rather than on every one. */
        const val RECOMPUTE_EVERY_BLOCKS = 8

        fun toDb(gain: Float): Float =
            if (gain <= 0f) MIN_GAIN_DB else (20.0 * log10(gain.toDouble())).toFloat()
    }
}
