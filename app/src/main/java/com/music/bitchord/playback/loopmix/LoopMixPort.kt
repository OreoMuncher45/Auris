package com.music.bitchord.playback.loopmix

import androidx.media3.common.C
import java.util.concurrent.atomic.AtomicLong

/**
 * One player's side of the loop mix, inside its audio sink
 * ([com.music.bitchord.playback.audio.PrecisionAudioSink]).
 *
 * **Outgoing** ([armOutgoing]): at the decoded frame where the song reaches its
 * loop start, the sink crosses from the song's own audio to the [LoopMixRenderer]
 * over 25 ms — sample-exact, because both are counted in the same frames — and
 * from then on writes the renderer's mix and stops consuming the song's input.
 * The player keeps playing (its sink always has data), but its song is
 * finished: the mix plays on its AudioTrack until the incoming player takes
 * over and the controller retires this one.
 *
 * **Incoming** ([armIncoming]): the song is silent up to [InArm.handoverUs] and
 * fades in over 25 ms there, while the renderer's incoming deck fades out at
 * the same track position. Before that, frames may be dropped or silence
 * inserted ([requestShift]) to put this player exactly in step with the deck;
 * the sink keeps its own clock continuous through that and reports the true
 * track position back ([positionShiftUs]).
 *
 * Written by the controller's thread ([armOutgoing], [armIncoming],
 * [requestShift], [disarm]) and the playback thread (everything else).
 */
class LoopMixPort {

    class OutArm(
        val renderer: LoopMixRenderer,
        /** Track microseconds of the loop start: mix frame 0. */
        val takeoverUs: Long,
        val sampleRate: Int,
        /** The first mix frame the renderer needs to see (its decks' pre-roll). */
        val firstFrame: Long,
        /** Renders mix frames into an interleaved stereo block: [renderer]'s, or a test's stand-in. */
        val render: Render = Render(renderer::render),
    )

    /** One block of the mix; a `fun interface`, so the playback thread's calls box nothing. */
    fun interface Render {
        fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int): Boolean
    }

    class InArm(
        /** Track microseconds at which this player becomes audible. */
        val handoverUs: Long,
    ) {
        /**
         * Frames to drop (positive) or of silence to insert (negative), applied
         * before the handover. This arm's own: a debit the sink makes for it can
         * never land on the counter of an arm that replaced it.
         */
        val pendingShift = AtomicLong()
    }

    /**
     * The arm and the generation it was published under, as one value: the
     * sink reads them in a single read, so it can never pair a new arm with
     * the loop state of the one before it.
     */
    class Armed(val generation: Int, val out: OutArm?, val inn: InArm?)

    @Volatile var armed: Armed = Armed(0, null, null)
        private set

    val outArm: OutArm? get() = armed.out
    val inArm: InArm? get() = armed.inn

    /** Bumped for every arm and disarm, so the sink drops state that belonged to an older one. */
    val generation: Int get() = armed.generation

    /** Arming, disarming and the sink's takeover commit are one decision each, in one order. */
    private val lock = Any()

    fun armOutgoing(arm: OutArm) = synchronized(lock) {
        armed = Armed(armed.generation + 1, arm, null)
    }

    fun armIncoming(arm: InArm) = synchronized(lock) {
        armed = Armed(armed.generation + 1, null, arm)
    }

    /**
     * Ends whatever is armed. Returns whether the outgoing sink had committed
     * its takeover first ([commitTakeover]): after this, none can, so the
     * answer is final, not a reading a sink mid-buffer can overtake.
     */
    fun disarm(): Boolean = synchronized(lock) {
        // The status of the arm being removed, not a flag an earlier arm left.
        val removed = armed.generation
        armed = Armed(removed + 1, null, null)
        tookOverAt == removed
    }

    /**
     * The sink switching to the renderer for the arm it read as [generation]:
     * refused, false, when that arm was disarmed or replaced since.
     */
    fun commitTakeover(generation: Int): Boolean = synchronized(lock) {
        if (armed.generation != generation) return@synchronized false
        tookOverAt = generation
        true
    }

    /**
     * The sink calling off the arm it read as [generation] (a flush, a format
     * it can't splice): refused when that arm was replaced since, so a sink
     * still finishing a buffer for the old arm never marks the new one over.
     */
    fun interrupt(generation: Int): Boolean = synchronized(lock) {
        if (armed.generation != generation) return@synchronized false
        interruptedAt = generation
        true
    }

    /**
     * A sink adopting [generation]: its status starts clean, unless that arm was
     * replaced meanwhile. Each arm's status is its own already (a new
     * generation reads as neither taken over nor interrupted); this clears a
     * mark left on the same generation.
     */
    fun resetStatus(generation: Int) = synchronized(lock) {
        if (armed.generation != generation) return@synchronized
        if (tookOverAt == generation) tookOverAt = NO_GENERATION
        if (interruptedAt == generation) interruptedAt = NO_GENERATION
    }

    // ---- What the sink publishes ---------------------------------------------------------

    /** The sink's format, as last configured: a loop mix needs linear PCM, stereo. */
    @Volatile var sampleRate: Int = 0
    @Volatile var channelCount: Int = 0
    @Volatile var precision: Boolean = false

    val supportsLoopMix: Boolean get() = precision && channelCount == 2 && sampleRate > 0

    /** The renderer timebase's offset: track µs = presentation µs - this. */
    @Volatile var streamOffsetUs: Long = 0L

    /** The generation whose takeover the sink committed, and the one it interrupted. */
    @Volatile private var tookOverAt = NO_GENERATION
    @Volatile private var interruptedAt = NO_GENERATION

    /**
     * True from the first block the outgoing sink mixed the renderer into (the
     * start of the audible crossing, just before the loop start), for the arm
     * armed now.
     */
    val tookOver: Boolean get() = tookOverAt == armed.generation

    /** Set by a flush or a reset: whatever was armed is gone (a seek, a stop). Only for the arm still armed ([interrupt]). */
    val interrupted: Boolean get() = interruptedAt == armed.generation

    /**
     * The last position reading, in track µs, and when it was taken
     * ([System.nanoTime]); the player's own clock, at sub-millisecond
     * resolution, read on every renderer tick.
     */
    @Volatile var positionUs: Long = C.TIME_UNSET
        private set
    @Volatile var positionNanos: Long = 0L
        private set

    /** The arm [generation] the reading was taken under. */
    @Volatile private var positionGeneration = -1

    /**
     * How long after the reading the delegate's clock and the track's still
     * advance together, µs: up to the next shift's boundary, 0 inside inserted
     * silence (the track holds still while the delegate plays it).
     */
    @Volatile private var positionSteadyUs = Long.MAX_VALUE

    /**
     * Odd while [publishPosition] is between its writes. The pair is read
     * as one: a new position with the old time would extrapolate it ahead by
     * the gap between readings, a whole correction's worth of error.
     */
    @Volatile private var positionSeq = 0

    /**
     * Records a reading; the sink's playback thread is the only writer.
     * [takenUnder] is the [generation] read *before* the clock was: an arm that
     * lands between the clock read and this call must not relabel the older
     * reading as its own.
     */
    fun publishPosition(us: Long, nanos: Long, takenUnder: Int, steadyUs: Long = Long.MAX_VALUE) {
        positionSeq++
        positionUs = us
        positionNanos = nanos
        positionGeneration = takenUnder
        positionSteadyUs = steadyUs
        positionSeq++
    }

    /**
     * Where the presented audio is now, in track seconds, extrapolated from the
     * last reading; null without one taken under the current arm, or when no
     * consistent reading could be had (the writer descheduled mid-way).
     */
    fun presentedSeconds(nowNanos: Long, playing: Boolean): Double? {
        // A handful of tries: the writer is a few field stores long.
        for (attempt in 0 until 64) {
            val seq = positionSeq
            if (seq and 1 != 0) continue
            val position = positionUs
            val at = positionNanos
            val taken = positionGeneration
            val steady = positionSteadyUs
            if (positionSeq != seq) continue
            // A reading from an earlier arm (this player's last mix, before a
            // pause or a gap) says nothing about this one.
            if (position == C.TIME_UNSET || taken != generation) return null
            val elapsed = if (playing) (nowNanos - at) / 1e9 else 0.0
            // Projected across a shift's boundary, or through inserted silence,
            // the track would not have moved as the clock did: no sample.
            if (elapsed > 0 && elapsed * 1e6 > steady) return null
            return position / 1e6 + elapsed
        }
        return null
    }

    // ---- The incoming side's alignment ------------------------------------------------------


    /** The track position the sink has processed up to, µs: a shift lands there, not where it's heard. */
    @Volatile var processedUs: Long = C.TIME_UNSET

    fun requestShift(frames: Long) {
        armed.inn?.pendingShift?.addAndGet(frames)
    }

    // ---- The splice, checked ---------------------------------------------------------------

    /**
     * The song's own audio and the renderer's, mono, over the [SPLICE_CAPTURE]
     * frames before mix frame 0 — the same stretch of the same song from two
     * decoders. [spliceLag] says how far apart they really are, which is the
     * one thing that can make the takeover audible.
     */
    val spliceSong = FloatArray(SPLICE_CAPTURE)
    val spliceDeck = FloatArray(SPLICE_CAPTURE)
    @Volatile var spliceCaptured = false

    fun resetSplice() {
        spliceCaptured = false
        spliceSong.fill(0f)
        spliceDeck.fill(0f)
    }

    /**
     * The lag (frames, positive: the deck late) at which the deck's pre-roll
     * best matches the song it takes over from, and the correlation there.
     * Null before the capture, or for silence.
     */
    fun spliceLag(maxLag: Int = 960): Pair<Int, Double>? {
        if (!spliceCaptured) return null
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestNorm = 0.0
        for (lag in -maxLag..maxLag) {
            var dot = 0.0
            var song = 0.0
            var deck = 0.0
            val from = maxOf(0, -lag)
            val to = minOf(SPLICE_CAPTURE, SPLICE_CAPTURE - lag)
            for (i in from until to) {
                val a = spliceSong[i].toDouble()
                val b = spliceDeck[i + lag].toDouble()
                dot += a * b
                song += a * a
                deck += b * b
            }
            val norm = kotlin.math.sqrt(song * deck)
            if (norm > 0 && dot / norm > bestScore) {
                bestScore = dot / norm
                best = lag
                bestNorm = norm
            }
        }
        return if (bestNorm > 0) best to bestScore else null
    }

    companion object {
        const val SPLICE_CAPTURE = 8192

        /** No arm: generations start at 0 and only go up. */
        private const val NO_GENERATION = -1
    }
}
