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
    )

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
        armed = Armed(armed.generation + 1, null, null)
        tookOver
    }

    /**
     * The sink switching to the renderer for the arm it read as [generation]:
     * refused, false, when that arm was disarmed or replaced since.
     */
    fun commitTakeover(generation: Int): Boolean = synchronized(lock) {
        if (armed.generation != generation) return@synchronized false
        tookOver = true
        true
    }

    // ---- What the sink publishes ---------------------------------------------------------

    /** The sink's format, as last configured: a loop mix needs linear PCM, stereo. */
    @Volatile var sampleRate: Int = 0
    @Volatile var channelCount: Int = 0
    @Volatile var precision: Boolean = false

    val supportsLoopMix: Boolean get() = precision && channelCount == 2 && sampleRate > 0

    /** The renderer timebase's offset: track µs = presentation µs - this. */
    @Volatile var streamOffsetUs: Long = 0L

    /** True from the block where the outgoing sink switched to the renderer. */
    @Volatile var tookOver: Boolean = false

    /** Set by a flush or a reset: whatever was armed is gone (a seek, a stop). */
    @Volatile var interrupted: Boolean = false

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
    fun publishPosition(us: Long, nanos: Long, takenUnder: Int) {
        positionSeq++
        positionUs = us
        positionNanos = nanos
        positionGeneration = takenUnder
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
            if (positionSeq != seq) continue
            // A reading from an earlier arm (this player's last mix, before a
            // pause or a gap) says nothing about this one.
            if (position == C.TIME_UNSET || taken != generation) return null
            val elapsed = if (playing) (nowNanos - at) / 1e9 else 0.0
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
    }
}
