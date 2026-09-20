package com.music.bitchord.playback.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Gated loudness measurement to ITU-R BS.1770-4 / EBU R 128, running on the
 * audio thread over whatever is actually being played.
 *
 * ## Why measure rather than read a tag
 *
 * Nothing upstream supplies a loudness figure. YouTube's `playerConfig`
 * carries one, but BitChord never parsed it and the sources that matter most
 * here — JioSaavn and user addons — have no equivalent field at all, so a
 * normalization that depended on metadata would be off for exactly the
 * catalogues an upgrade moves a track *to*. Measuring the decoded audio is the
 * only approach that treats every source the same.
 *
 * The analyzer in `native/analyzer/` already reports a `loudness_lufs`, and it
 * is deliberately not reused: it is plain RMS, documented there as "not a
 * gated, K-weighted loudness measurement", and it only runs at all when
 * Automix is on, behind a full background decode that
 * [com.music.bitchord.playback.smart.TrackAnalyzer] pays for. A default-on
 * feature cannot be built on a measurement most listeners never compute.
 * Two biquads and a running sum, by contrast, cost nothing worth measuring.
 *
 * ## The measurement
 *
 * - K-weighting: a high-shelf and a high-pass, cascaded, per channel. The
 *   coefficients are derived for the live sample rate rather than being the
 *   48 kHz constants from the spec's tables — [configure] is called with
 *   whatever the decoder produced, and 44.1 kHz is the common case.
 * - 400 ms blocks at 75% overlap, i.e. a fresh block every 100 ms, each one
 *   the mean square of the four most recent 100 ms sub-blocks.
 * - Two gates, as the spec requires: an absolute one at [ABSOLUTE_GATE_LUFS],
 *   then a relative one ten units below the mean of what survived the first.
 *   The gates are what make a quiet intro or a silent run-out stop dragging a
 *   track's measured loudness down.
 *
 * ## Bounded, and deliberately biased towards the opening
 *
 * Block history stops growing at [MAX_BLOCKS]. That is ten minutes of audio,
 * past which the integrated figure has long since stopped moving for anything
 * shaped like a song, and the cap is what keeps a two-hour mix from turning a
 * per-player meter into half a megabyte. A track longer than that is measured
 * on its first ten minutes.
 *
 * ## Threading
 *
 * Audio-thread-owned and allocation-free in steady state: every array is
 * sized in [configure] and reused. Nothing here is synchronized, because
 * nothing but the audio thread may call [process], [configure] or [reset].
 * [integratedLufs] and [peak] are plain reads of values the audio thread
 * wrote, and are only ever read for a figure that is allowed to be one block
 * stale.
 */
class LoudnessMeter {

    private var sampleRate: Int = 0
    private var channelCount: Int = 0

    /** Frames in one 100 ms sub-block. Four of these make a gating block. */
    private var subBlockFrames: Int = 0

    // K-weighting stage 1 (high shelf) and stage 2 (high pass), Direct Form I.
    private var shelfB0 = 0.0
    private var shelfB1 = 0.0
    private var shelfB2 = 0.0
    private var shelfA1 = 0.0
    private var shelfA2 = 0.0
    private var hpB0 = 0.0
    private var hpB1 = 0.0
    private var hpB2 = 0.0
    private var hpA1 = 0.0
    private var hpA2 = 0.0

    /** Two input and two output histories per channel, per stage. */
    private var shelfX1 = DoubleArray(0)
    private var shelfX2 = DoubleArray(0)
    private var shelfY1 = DoubleArray(0)
    private var shelfY2 = DoubleArray(0)
    private var hpX1 = DoubleArray(0)
    private var hpX2 = DoubleArray(0)
    private var hpY1 = DoubleArray(0)
    private var hpY2 = DoubleArray(0)

    /** Sum of squares of K-weighted samples in the sub-block being filled. */
    private var subSum = DoubleArray(0)
    private var subFilled = 0

    /**
     * Mean square of each of the last four completed sub-blocks, per channel,
     * as a flat `[slot * channelCount + channel]` ring. A gating block is the
     * mean of all four.
     */
    private var ring = DoubleArray(0)
    private var ringPos = 0
    private var ringFilled = 0

    /** Per gating block: its weighted power, and that power as a loudness. */
    private var blockPower = DoubleArray(0)
    private var blockLoudness = DoubleArray(0)
    private var blockCount = 0

    /** Highest absolute sample seen, before any gain. Never decreases. */
    var peak: Float = 0f
        private set

    /** Blocks measured so far, whether or not they survived gating. */
    val measuredBlocks: Int get() = blockCount

    /** Seconds of audio the measurement has seen. */
    val measuredSeconds: Double get() = blockCount * BLOCK_STEP_SECONDS

    /**
     * Integrated loudness in LUFS, or null while too little has been heard for
     * the figure to mean anything at all — fewer than [EARLY_BLOCKS], or
     * nothing above the absolute gate, which is what a track that opens on
     * silence looks like.
     *
     * From [EARLY_BLOCKS] to [MIN_BLOCKS] the figure is real but drawn from
     * under a second of audio, so it describes the opening rather than the
     * recording; [trusted] is how a caller tells the two apart. It is offered
     * that early on purpose: a correction applied in a track's first moments
     * is one nobody hears arrive, and waiting for a settled figure is what
     * turns a correction into an audible step three seconds in.
     */
    var integratedLufs: Double? = null
        private set

    /**
     * Whether [integratedLufs] has heard enough of the track to be taken at
     * face value rather than treated as an opening estimate.
     */
    val trusted: Boolean get() = blockCount >= MIN_BLOCKS

    /**
     * Sizes the meter for a stream and derives the K-weighting for its rate.
     *
     * Block history and [peak] survive this deliberately. A mid-track quality
     * upgrade reconfigures the sink under a track that is still playing, and
     * frequently at a different rate — 48 kHz Opus giving way to 44.1 kHz
     * FLAC — but loudness in LUFS is a property of the recording rather than
     * of the rendition carrying it, so everything measured before the swap is
     * still true after it. Discarding it would restart the measurement from
     * nothing halfway through a song and step the gain as it converged again.
     * Only what is genuinely rate-dependent — the coefficients, the filter
     * memory, the part-filled sub-block — is rebuilt.
     *
     * A change of channel count is the one case that cannot carry over, since
     * the per-channel accumulators change shape; that drops the history.
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }

        if (channelCount != this.channelCount) {
            this.channelCount = channelCount
            shelfX1 = DoubleArray(channelCount)
            shelfX2 = DoubleArray(channelCount)
            shelfY1 = DoubleArray(channelCount)
            shelfY2 = DoubleArray(channelCount)
            hpX1 = DoubleArray(channelCount)
            hpX2 = DoubleArray(channelCount)
            hpY1 = DoubleArray(channelCount)
            hpY2 = DoubleArray(channelCount)
            subSum = DoubleArray(channelCount)
            ring = DoubleArray(RING_SLOTS * channelCount)
            reset()
        }

        this.sampleRate = sampleRate
        subBlockFrames = (sampleRate / SUB_BLOCKS_PER_SECOND).coerceAtLeast(1)
        deriveCoefficients(sampleRate)

        if (blockPower.size != MAX_BLOCKS) {
            blockPower = DoubleArray(MAX_BLOCKS)
            blockLoudness = DoubleArray(MAX_BLOCKS)
        }

        clearTransient()
    }

    /**
     * Clears filter memory and the part-filled sub-block, keeping everything
     * already measured. For a seek: the listener jumped, but it is the same
     * recording and the blocks behind the playhead described it correctly.
     */
    fun flushTransient() {
        clearTransient()
    }

    /** Forgets the track entirely. For an actual change of track. */
    fun reset() {
        clearTransient()
        blockCount = 0
        peak = 0f
        integratedLufs = null
    }

    private fun clearTransient() {
        shelfX1.fill(0.0)
        shelfX2.fill(0.0)
        shelfY1.fill(0.0)
        shelfY2.fill(0.0)
        hpX1.fill(0.0)
        hpX2.fill(0.0)
        hpY1.fill(0.0)
        hpY2.fill(0.0)
        subSum.fill(0.0)
        ring.fill(0.0)
        subFilled = 0
        ringPos = 0
        ringFilled = 0
    }

    /**
     * Measures [frameCount] interleaved frames from [samples], starting at
     * index 0, without altering them.
     */
    fun process(samples: FloatArray, frameCount: Int) {
        val channels = channelCount
        if (frameCount <= 0 || channels <= 0 || sampleRate <= 0) return
        if (blockCount >= MAX_BLOCKS) return

        var index = 0
        for (frame in 0 until frameCount) {
            for (channel in 0 until channels) {
                val sample = samples[index++]
                if (sample.isNaN()) continue

                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude

                // Stage 1: high shelf.
                val x = sample.toDouble()
                val shelfOut = shelfB0 * x + shelfB1 * shelfX1[channel] + shelfB2 * shelfX2[channel] -
                    shelfA1 * shelfY1[channel] - shelfA2 * shelfY2[channel]
                shelfX2[channel] = shelfX1[channel]
                shelfX1[channel] = x
                shelfY2[channel] = shelfY1[channel]
                shelfY1[channel] = shelfOut

                // Stage 2: high pass.
                val hpOut = hpB0 * shelfOut + hpB1 * hpX1[channel] + hpB2 * hpX2[channel] -
                    hpA1 * hpY1[channel] - hpA2 * hpY2[channel]
                hpX2[channel] = hpX1[channel]
                hpX1[channel] = shelfOut
                hpY2[channel] = hpY1[channel]
                hpY1[channel] = hpOut

                subSum[channel] += hpOut * hpOut
            }

            if (++subFilled >= subBlockFrames) {
                closeSubBlock()
                if (blockCount >= MAX_BLOCKS) return
            }
        }
    }

    /**
     * Files the completed 100 ms sub-block into the ring and, once four are in
     * hand, emits the 400 ms gating block they overlap to form.
     */
    private fun closeSubBlock() {
        val channels = channelCount
        val frames = subFilled.toDouble()
        val slotBase = ringPos * channels
        for (channel in 0 until channels) {
            ring[slotBase + channel] = subSum[channel] / frames
            subSum[channel] = 0.0
        }
        subFilled = 0
        ringPos = (ringPos + 1) % RING_SLOTS
        if (ringFilled < RING_SLOTS) {
            ringFilled++
            if (ringFilled < RING_SLOTS) return
        }

        // Weighted sum over channels of each one's mean square across the
        // whole 400 ms window. Every channel BitChord plays is weighted 1.0:
        // the spec's 1.41 applies to surround channels, and the sink only
        // runs its DSP chain for mono and stereo.
        var power = 0.0
        for (channel in 0 until channels) {
            var meanSquare = 0.0
            for (slot in 0 until RING_SLOTS) {
                meanSquare += ring[slot * channels + channel]
            }
            power += meanSquare / RING_SLOTS
        }

        blockPower[blockCount] = power
        blockLoudness[blockCount] = if (power > 0.0) {
            LOUDNESS_OFFSET + 10.0 * log10(power)
        } else {
            Double.NEGATIVE_INFINITY
        }
        blockCount++

        // Every block until the figure is trusted, and once a second after
        // that. The early blocks are where the number moves, and they are the
        // ones a listener is least able to hear it move in; re-integrating
        // over at most thirty blocks costs nothing worth saving.
        if (blockCount >= EARLY_BLOCKS &&
            (blockCount < MIN_BLOCKS || blockCount % REFRESH_EVERY_BLOCKS == 0)
        ) {
            integratedLufs = computeIntegrated()
        }
    }

    /**
     * The two-gate integration from BS.1770-4: absolute at
     * [ABSOLUTE_GATE_LUFS], then relative at [RELATIVE_GATE_LU] below the mean
     * of the blocks that cleared it.
     *
     * Walks the block history twice rather than keeping running sums, because
     * the relative threshold depends on the first pass's result and every
     * block has to be re-tested against it. The loudness of each block was
     * computed once when it was filed, so neither pass calls `log10`.
     */
    private fun computeIntegrated(): Double? {
        var absoluteSum = 0.0
        var absoluteCount = 0
        for (i in 0 until blockCount) {
            if (blockLoudness[i] > ABSOLUTE_GATE_LUFS) {
                absoluteSum += blockPower[i]
                absoluteCount++
            }
        }
        if (absoluteCount == 0) return null

        val relativeThreshold =
            LOUDNESS_OFFSET + 10.0 * log10(absoluteSum / absoluteCount) - RELATIVE_GATE_LU

        var gatedSum = 0.0
        var gatedCount = 0
        for (i in 0 until blockCount) {
            val loudness = blockLoudness[i]
            if (loudness > ABSOLUTE_GATE_LUFS && loudness > relativeThreshold) {
                gatedSum += blockPower[i]
                gatedCount++
            }
        }
        if (gatedCount == 0 || gatedSum <= 0.0) return null

        return LOUDNESS_OFFSET + 10.0 * log10(gatedSum / gatedCount)
    }

    /**
     * K-weighting for an arbitrary rate, from the analogue prototype rather
     * than the spec's 48 kHz coefficient table, so 44.1 kHz — which is most of
     * what a lossless source serves — is filtered correctly rather than
     * approximately. Same derivation libebur128 uses.
     */
    private fun deriveCoefficients(rate: Int) {
        val shelfK = tan(Math.PI * SHELF_F0_HZ / rate)
        val vh = 10.0.pow(SHELF_GAIN_DB / 20.0)
        val vb = vh.pow(SHELF_VB_EXPONENT)
        val shelfDen = 1.0 + shelfK / SHELF_Q + shelfK * shelfK
        shelfB0 = (vh + vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfDen
        shelfB1 = 2.0 * (shelfK * shelfK - vh) / shelfDen
        shelfB2 = (vh - vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfDen
        shelfA1 = 2.0 * (shelfK * shelfK - 1.0) / shelfDen
        shelfA2 = (1.0 - shelfK / SHELF_Q + shelfK * shelfK) / shelfDen

        val hpK = tan(Math.PI * HIGHPASS_F0_HZ / rate)
        val hpDen = 1.0 + hpK / HIGHPASS_Q + hpK * hpK
        hpB0 = 1.0
        hpB1 = -2.0
        hpB2 = 1.0
        hpA1 = 2.0 * (hpK * hpK - 1.0) / hpDen
        hpA2 = (1.0 - hpK / HIGHPASS_Q + hpK * hpK) / hpDen
    }

    companion object {
        /** The -0.691 dB offset BS.1770 applies to every loudness figure. */
        const val LOUDNESS_OFFSET = -0.691

        /** Blocks quieter than this never count, however quiet the track is. */
        const val ABSOLUTE_GATE_LUFS = -70.0

        /** The relative gate sits this far below the absolutely-gated mean. */
        const val RELATIVE_GATE_LU = 10.0

        /** Four overlapping 100 ms sub-blocks make one 400 ms gating block. */
        const val RING_SLOTS = 4
        const val SUB_BLOCKS_PER_SECOND = 10
        const val BLOCK_STEP_SECONDS = 1.0 / SUB_BLOCKS_PER_SECOND

        /**
         * The first figure, at seven hundred milliseconds of audio — a 400 ms
         * gating block and three more 100 ms steps.
         *
         * Too little to be the final word on a recording, and that is not what
         * it is for: it is what lets the correction be applied while the track
         * is still opening, where a level that arrives already correct is the
         * only level the listener ever knows. Everything after it is a
         * refinement of a track already playing at roughly the right volume,
         * which is a change small enough and slow enough to be inaudible.
         */
        const val EARLY_BLOCKS = 4

        /**
         * Roughly three seconds, past which the figure is no longer an
         * estimate of the opening but a measurement of the recording. Long
         * enough that the answer is not being drawn from one bar of an intro.
         */
        const val MIN_BLOCKS = 30

        /** Re-integrate once a second rather than on every 100 ms block. */
        const val REFRESH_EVERY_BLOCKS = 10

        /** Ten minutes. See the class comment on why history is capped. */
        const val MAX_BLOCKS = 6000

        private const val SHELF_F0_HZ = 1681.974450955533
        private const val SHELF_GAIN_DB = 3.999843853973347
        private const val SHELF_Q = 0.7071752369554196
        private const val SHELF_VB_EXPONENT = 0.4996667741545416
        private const val HIGHPASS_F0_HZ = 38.13547087602444
        private const val HIGHPASS_Q = 0.5003270373238773
    }
}
