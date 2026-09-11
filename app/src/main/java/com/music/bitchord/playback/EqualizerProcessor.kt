package com.music.bitchord.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The app's own equaliser: a cascade of ten filter sections and a balance trim,
 * running inside ExoPlayer's audio processor chain.
 *
 * ## Why not the platform equaliser
 *
 * [android.media.audiofx.Equalizer] picks its own band count and centre
 * frequencies — five, wherever the device's effect library put them — so the
 * seven centres this app draws sliders under cannot be asked for, the tone pad
 * has no continuously movable filter to drive, and there is no platform effect
 * for left/right balance at all. [SpatialAudioProcessor] already documents the
 * other half of the argument: an OEM effect chain is free to swallow a
 * session-attached effect whole, and one of them did.
 *
 * ## The filter
 *
 * The same topology-preserving state-variable filter
 * [TransitionFilterProcessor] uses, per section, with Cytomic's output mixes
 * turning each one into a bell or a shelf. Chosen for the reason given there —
 * the trapezoidal form stays well behaved at every cutoff — which matters more
 * here than it does for a DJ filter: a 60 Hz section is a thousandth of the way
 * to Nyquist, which is exactly where a naive biquad's coefficients lose their
 * precision.
 *
 * ## Gliding
 *
 * Every number here is a target, not a value. Dragging the tone puck or a band
 * slider re-aims them continuously, and stepping filter coefficients per buffer
 * is zipper noise, so gains, Qs, the make-up attenuation and the balance all
 * chase their targets across [GLIDE_FRAMES]-sample sub-blocks. Switching tab or
 * preset is a gain change like any other — see [EqLayout] for why the set of
 * sections never changes shape — so it glides too rather than clicking.
 *
 * ## What this cannot do
 *
 * Nothing in [androidx.media3.exoplayer.audio.DefaultAudioSink]'s processor
 * chain runs when the sink is in float mode: it builds its pipeline from
 * `ToFloatPcmAudioProcessor` alone on that branch and appends
 * `audioProcessorChain.getAudioProcessors()` only on the 16-bit one. So an
 * output set to 32-bit float, on a route that actually granted it, plays with
 * no equaliser, no spatial audio and no silence skipping. That is Media3's
 * design and not something this class can route around; the settings screen
 * says so when it is happening.
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {

    /** What the processor is aiming at. Swapped whole, never mutated in place. */
    private class Tuning(val curve: EqCurve, val balance: Float) {
        companion object {
            val OFF = Tuning(EqCurve.FLAT, 0f)
        }
    }

    @Volatile
    private var target: Tuning = Tuning.OFF

    private var channelCount = 0
    private var sampleRate = 0

    private val currentGainDb = FloatArray(EqLayout.SLOTS)
    private val currentQ = FloatArray(EqLayout.SLOTS) { 0.707f }
    private var currentPreampDb = 0f
    private var currentBalance = 0f

    private val coeffA1 = FloatArray(EqLayout.SLOTS)
    private val coeffA2 = FloatArray(EqLayout.SLOTS)
    private val coeffA3 = FloatArray(EqLayout.SLOTS)
    private val mixInput = FloatArray(EqLayout.SLOTS)
    private val mixBand = FloatArray(EqLayout.SLOTS)
    private val mixLow = FloatArray(EqLayout.SLOTS)

    /** Which sections are worth running this sub-block, and how many. */
    private val activeSlots = IntArray(EqLayout.SLOTS)
    private val running = BooleanArray(EqLayout.SLOTS)

    /** Two integrator states per section, per channel. */
    private var state = FloatArray(0)

    /** Output trim per channel: make-up attenuation, and balance where stereo. */
    private var channelGain = FloatArray(0)

    /**
     * Aims the equaliser. Called from whoever owns the settings, not from the
     * audio thread; nothing here is read until the next sub-block boundary.
     *
     * `enabled = false` is a flat curve and a centred balance rather than a
     * bypass flag, so switching the equaliser off glides down to nothing like
     * every other change instead of cutting the current curve out from under a
     * playing track.
     */
    fun setTuning(enabled: Boolean, curve: EqCurve, balance: Float) {
        target = if (enabled) Tuning(curve, balance.coerceIn(-1f, 1f)) else Tuning.OFF
    }

    /**
     * 16-bit PCM, any channel count.
     *
     * Bowing out with [AudioProcessor.AudioFormat.NOT_SET] rather than throwing
     * for the reason [SpatialAudioProcessor] spells out: `DefaultAudioSink`
     * configures every processor in its chain whether or not the effect is
     * switched on, and a throw from any of them kills the renderer before a
     * sample is written.
     *
     * Unlike that one this accepts mono and multichannel. Widening a mono voice
     * note is meaningless, but equalising a mono file is not — the local library
     * is full of them — so only the balance trim stands down below two channels.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            Log.w(
                TAG,
                "Equaliser inactive: encoding=${inputAudioFormat.encoding} " +
                    "channels=${inputAudioFormat.channelCount} is not 16-bit PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        state = FloatArray(channelCount * EqLayout.SLOTS * 2)
        channelGain = FloatArray(channelCount) { 1f }
        running.fill(false)
        snapToTarget()
        return inputAudioFormat
    }

    override fun onFlush() {
        state.fill(0f)
        running.fill(false)
        // Snapped, not glided: a flush means a seek or a fresh source, so there
        // is no continuous signal for a glide to be continuous with.
        snapToTarget()
    }

    /**
     * State only.
     *
     * Deliberately *not* clearing [target]: `DefaultAudioSink` resets its
     * processors on a format change, and a reset that forgot the curve would
     * switch the user's equaliser off somewhere in the middle of a queue with
     * nothing on screen changing to say why. [TransitionFilterProcessor] does
     * clear its targets here, and is right to — its settings belong to one
     * transition, while these belong to the listener.
     */
    override fun onReset() {
        state = FloatArray(0)
        channelGain = FloatArray(0)
        running.fill(false)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val tuning = target
        // Flat and already settled there: hand the buffer straight through. The
        // "already settled" half matters — an equaliser that has just been
        // switched off is still gliding down, and cutting that glide short is
        // the click it exists to avoid.
        if (isFlat(tuning) && isSettled(tuning)) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        var remaining = frameCount
        while (remaining > 0) {
            val block = min(remaining, GLIDE_FRAMES)
            glideTowards(tuning)
            val active = prepareSections()
            prepareChannelGains()

            repeat(block) {
                for (channel in 0 until channelCount) {
                    var sample = inputBuffer.short.toFloat()
                    for (index in 0 until active) {
                        sample = section(activeSlots[index], channel, sample)
                    }
                    outputBuffer.putShort(clampToShort(sample * channelGain[channel]))
                }
            }
            flushDenormals(active)
            remaining -= block
        }
        outputBuffer.flip()
    }

    // ---- Gliding -----------------------------------------------------------

    private fun snapToTarget() {
        val tuning = target
        tuning.curve.gainsDb.copyInto(currentGainDb)
        tuning.curve.qs.copyInto(currentQ)
        currentPreampDb = tuning.curve.preampDb
        currentBalance = tuning.balance
    }

    private fun glideTowards(tuning: Tuning) {
        for (slot in 0 until EqLayout.SLOTS) {
            // Decibels, not linear gain: it is the perceptual unit, and it puts
            // the resting point at zero so a band crossing from cut to boost
            // passes through flat rather than through a division.
            currentGainDb[slot] = linearGlide(currentGainDb[slot], tuning.curve.gainsDb[slot])
            // Q the other way round — a bandwidth halves and doubles, it does
            // not step, so Broad to Focused reads as one even movement.
            currentQ[slot] = geometricGlide(currentQ[slot], tuning.curve.qs[slot])
        }
        currentPreampDb = linearGlide(currentPreampDb, tuning.curve.preampDb)
        currentBalance = linearGlide(currentBalance, tuning.balance)
    }

    private fun linearGlide(current: Float, target: Float): Float =
        current + (target - current) * GLIDE_RATE

    private fun geometricGlide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_Q))
        val to = ln(target.coerceAtLeast(MIN_Q))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    private fun isFlat(tuning: Tuning): Boolean =
        abs(tuning.balance) < SETTLED_BALANCE &&
            abs(tuning.curve.preampDb) < SETTLED_DB &&
            tuning.curve.gainsDb.all { abs(it) < SETTLED_DB }

    private fun isSettled(tuning: Tuning): Boolean {
        if (abs(currentBalance - tuning.balance) >= SETTLED_BALANCE) return false
        if (abs(currentPreampDb - tuning.curve.preampDb) >= SETTLED_DB) return false
        for (slot in 0 until EqLayout.SLOTS) {
            if (abs(currentGainDb[slot] - tuning.curve.gainsDb[slot]) >= SETTLED_DB) return false
        }
        return true
    }

    // ---- Coefficients ------------------------------------------------------

    /**
     * Works out which sections are doing anything and updates their
     * coefficients. Returns how many were filled into [activeSlots].
     *
     * A section at 0 dB is arithmetically a wire — its band and low mixes both
     * fall to zero — so skipping it costs nothing and saves the whole cascade
     * while the other tab's half of [EqLayout] sits idle. Its state is cleared
     * on the way out rather than left stale, so nothing it was holding is
     * waiting to be let go the next time it comes back.
     */
    private fun prepareSections(): Int {
        var active = 0
        for (slot in 0 until EqLayout.SLOTS) {
            if (abs(currentGainDb[slot]) >= SETTLED_DB) {
                updateCoefficients(slot)
                activeSlots[active++] = slot
                running[slot] = true
            } else if (running[slot]) {
                clearState(slot)
                running[slot] = false
            }
        }
        return active
    }

    private fun updateCoefficients(slot: Int) {
        val spec = EqLayout.slots[slot]
        val a = 10f.pow(currentGainDb[slot] / 40f)
        val q = currentQ[slot].coerceAtLeast(MIN_Q)
        val base = tan(Math.PI * usableFrequency(spec.frequencyHz) / sampleRate).toFloat()
        val g: Float
        val k: Float
        when (spec.kind) {
            FilterKind.BELL -> {
                g = base
                k = 1f / (q * a)
                mixInput[slot] = 1f
                mixBand[slot] = k * (a * a - 1f)
                mixLow[slot] = 0f
            }
            FilterKind.LOW_SHELF -> {
                g = base / sqrt(a)
                k = 1f / q
                mixInput[slot] = 1f
                mixBand[slot] = k * (a - 1f)
                mixLow[slot] = a * a - 1f
            }
            FilterKind.HIGH_SHELF -> {
                g = base * sqrt(a)
                k = 1f / q
                mixInput[slot] = a * a
                mixBand[slot] = k * (1f - a) * a
                mixLow[slot] = 1f - a * a
            }
        }
        val d = 1f / (1f + g * (g + k))
        coeffA1[slot] = d
        coeffA2[slot] = g * d
        coeffA3[slot] = g * (g * d)
    }

    /** Highest centre the bilinear transform can still place without warping to infinity. */
    private fun usableFrequency(hz: Float): Float =
        hz.coerceIn(MIN_HZ, sampleRate * MAX_FREQUENCY_FRACTION)

    private fun prepareChannelGains() {
        val preamp = 10f.pow(currentPreampDb / 20f)
        if (channelCount == 2) {
            // Attenuate the far side rather than lift the near one: there is no
            // headroom above full scale to lift into, and a balance that made
            // things louder would be a volume control with a side effect.
            channelGain[0] = preamp * min(1f, 1f - currentBalance)
            channelGain[1] = preamp * min(1f, 1f + currentBalance)
        } else {
            channelGain.fill(preamp)
        }
    }

    // ---- Filter ------------------------------------------------------------

    private fun section(slot: Int, channel: Int, input: Float): Float {
        val i = (channel * EqLayout.SLOTS + slot) * 2
        val ic1 = state[i]
        val ic2 = state[i + 1]
        val v3 = input - ic2
        val v1 = coeffA1[slot] * ic1 + coeffA2[slot] * v3
        val v2 = ic2 + coeffA2[slot] * ic1 + coeffA3[slot] * v3
        state[i] = 2f * v1 - ic1
        state[i + 1] = 2f * v2 - ic2
        return mixInput[slot] * input + mixBand[slot] * v1 + mixLow[slot] * v2
    }

    private fun clearState(slot: Int) {
        for (channel in 0 until channelCount) {
            val i = (channel * EqLayout.SLOTS + slot) * 2
            state[i] = 0f
            state[i + 1] = 0f
        }
    }

    /**
     * Zeroes integrator states that have decayed to nothing.
     *
     * A filter left ringing out under silence walks its state down towards
     * denormal floats, and denormal arithmetic is one to two orders of
     * magnitude slower than normal arithmetic on hardware that traps it. On an
     * audio thread with a fixed buffer deadline that is not a slow fade, it is a
     * dropout — and a quiet passage is exactly when it would happen.
     */
    private fun flushDenormals(active: Int) {
        for (index in 0 until active) {
            val slot = activeSlots[index]
            for (channel in 0 until channelCount) {
                val i = (channel * EqLayout.SLOTS + slot) * 2
                if (abs(state[i]) < DENORMAL_FLOOR) state[i] = 0f
                if (abs(state[i + 1]) < DENORMAL_FLOOR) state[i + 1] = 0f
            }
        }
    }

    private fun clampToShort(value: Float): Short =
        value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    companion object {
        private const val TAG = "BitChordEqualizer"

        private const val BYTES_PER_SAMPLE = 2

        /** Frames between coefficient updates. ~1.5 ms at 44.1 kHz. */
        private const val GLIDE_FRAMES = 64

        /** Per-sub-block glide fraction. ~18 ms time constant — a fast drag still tracks. */
        private const val GLIDE_RATE = 0.08f

        /** Below this a band is doing nothing anyone can hear, so it counts as flat. */
        private const val SETTLED_DB = 0.01f

        /** Same idea for the balance trim, where the scale is -1 to 1. */
        private const val SETTLED_BALANCE = 0.0005f

        private const val MIN_Q = 0.05f
        private const val MIN_HZ = 10f

        /** Keeps `tan` away from its pole at Nyquist. */
        private const val MAX_FREQUENCY_FRACTION = 0.45f

        /** A 16-bit sample is never smaller than 1; this is far below inaudible. */
        private const val DENORMAL_FLOOR = 1e-12f
    }
}
