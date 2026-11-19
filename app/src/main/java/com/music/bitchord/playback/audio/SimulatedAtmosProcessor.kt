package com.music.bitchord.playback.audio

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * "Simulated Dolby Atmos" — an immersive-spatial effect for ordinary stereo.
 *
 * This is a *sound* effect, not a stream. The real object-based Atmos mix is
 * chosen by the audio-quality rung, and YouTube only ever hands us stereo, so
 * there is no Atmos source to decode here. What this does is the same thing a
 * virtualiser does when it has no object mix to work from: it widens the
 * front image and folds a little of the opposite channel back in, delayed and
 * dulled, so the sound reads as coming from around the head instead of from
 * two points in front of it.
 *
 * Two stages:
 *  1. Mid/side widening. The side signal is high-passed at 130 Hz so the bass
 *     stays centred and mono (no phasey low end), then re-encoded with an
 *     exaggerated side component.
 *  2. Crossfeed. A short, low-passed, attenuated copy of the opposite channel
 *     is mixed back in. This is what turns "wider" into "spacious": the two
 *     channels stop being independent speakers and start reading as one field.
 *
 * Reimplemented from the publicly described technique (M/S decode → side HP →
 * side × width → M/S encode, plus a delayed crossfeed tap); not copied from
 * any GPL source.
 *
 * Applies to the front L/R pair; on multichannel sources the remaining
 * channels pass through untouched. Inherently stereo, so it bows out below
 * two channels rather than failing the whole sink.
 */
class SimulatedAtmosProcessor : FloatAudioProcessor {

    @Volatile
    var enabled: Boolean = false

    /** Side width multiplier. 1.0 = untouched. */
    private val widthGain = 1.9f
    /** Makeup attenuation so the wider image and the crossfeed do not clip. */
    private val outputGain = 0.86f
    /** Side high-pass corner; bass below this stays mono/centred. */
    private val sideHpHz = 130f
    /** How much of the delayed, low-passed opposite channel is mixed back in. */
    private val crossfeedGain = 0.18f
    /** One-pole low-pass on the crossfed signal — dulls it, like a far ear would. */
    private val lowpassCoeff = 0.3f

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var hpCoeff = 0f
    private var lp = 0f

    private var delayLeft = FloatArray(0)
    private var delayRight = FloatArray(0)
    private var delayIndex = 0
    private var crossLpLeft = 0f
    private var crossLpRight = 0f

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        // One-pole high-pass coefficient for the side channel.
        hpCoeff = if (sampleRate > 0) {
            (1.0 - exp(-2.0 * Math.PI * sideHpHz / sampleRate.toDouble())).toFloat()
        } else {
            0f
        }
        lp = 0f
        val delayFrames = if (sampleRate > 0) {
            (sampleRate * DELAY_MS / 1000f).roundToInt().coerceAtLeast(1)
        } else {
            0
        }
        if (delayLeft.size != delayFrames) {
            delayLeft = FloatArray(delayFrames)
            delayRight = FloatArray(delayFrames)
        }
        flush()
    }

    override fun process(block: AudioBlock) {
        if (!enabled || block.frameCount == 0 || block.channelCount < 2) return
        if (sampleRate <= 0 || hpCoeff <= 0f) return
        val channels = block.channelCount
        val frames = block.frameCount
        val samples = block.samples
        var lp = this.lp
        val extra = widthGain - 1f

        val delaySize = delayLeft.size
        val crossfeed = crossfeedGain
        val lpCoeff = lowpassCoeff

        if (delaySize == 0) {
            // No delay line (a sample rate the configure could not make one
            // for): fall back to the widening alone rather than passing the
            // block through unprocessed, so the switch still does something.
            for (frame in 0 until frames) {
                val base = frame * channels
                val left = samples[base]
                val right = samples[base + 1]
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f
                lp += hpCoeff * (side - lp)
                val sideOut = side + (side - lp) * extra
                samples[base] = (mid + sideOut) * outputGain
                samples[base + 1] = (mid - sideOut) * outputGain
            }
            this.lp = lp
            return
        }

        var dIdx = delayIndex
        var xLpL = crossLpLeft
        var xLpR = crossLpRight
        for (frame in 0 until frames) {
            val base = frame * channels
            val left = samples[base]
            val right = samples[base + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            // Low-pass the side (the bass); the high-passed remainder widens.
            lp += hpCoeff * (side - lp)
            val sideOut = side + (side - lp) * extra
            var outLeft = mid + sideOut
            var outRight = mid - sideOut

            // Crossfeed: the opposite channel, delayed and dulled.
            val delayedRight = delayRight[dIdx]
            val delayedLeft = delayLeft[dIdx]
            xLpL += lpCoeff * (delayedRight - xLpL)
            xLpR += lpCoeff * (delayedLeft - xLpR)
            outLeft += xLpL * crossfeed
            outRight += xLpR * crossfeed

            delayLeft[dIdx] = left
            delayRight[dIdx] = right
            dIdx = if (dIdx + 1 >= delaySize) 0 else dIdx + 1

            samples[base] = outLeft * outputGain
            samples[base + 1] = outRight * outputGain
        }
        this.lp = lp
        delayIndex = dIdx
        crossLpLeft = xLpL
        crossLpRight = xLpR
    }

    override fun flush() {
        lp = 0f
        delayLeft.fill(0f)
        delayRight.fill(0f)
        delayIndex = 0
        crossLpLeft = 0f
        crossLpRight = 0f
    }

    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        hpCoeff = 0f
    }

    private companion object {
        const val DELAY_MS = 11
    }
}
