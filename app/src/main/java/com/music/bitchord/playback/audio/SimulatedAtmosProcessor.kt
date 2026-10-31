package com.music.bitchord.playback.audio

import kotlin.math.exp

/**
 * "Simulated Dolby Atmos" — a widening effect for ordinary stereo.
 *
 * This is a *sound* effect, not a stream: the real object-based Atmos mix is
 * chosen by the audio-quality rung, and YouTube only ever hands us stereo, so
 * there is no Atmos source to decode here. What this does is the classic
 * mid/side widening that reads as "spacious": the side signal is high-passed
 * at 130 Hz so bass stays centered and mono (no phasey low end), then
 * re-encoded with an exaggerated side component.
 *
 * Reimplemented from the publicly described technique (M/S decode → side HP →
 * side × width → M/S encode); not copied from any GPL source.
 *
 * Applies to the front L/R pair; on multichannel sources the remaining
 * channels pass through untouched.
 */
class SimulatedAtmosProcessor : FloatAudioProcessor {

    @Volatile
    var enabled: Boolean = false

    /** Side width multiplier. 1.0 = untouched. */
    private val widthGain = 1.7f
    /** Makeup attenuation so the wider image does not clip. */
    private val outputGain = 0.9f
    /** Side high-pass corner; bass below this stays mono/centered. */
    private val sideHpHz = 130f

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var hpCoeff = 0f
    private var lp = 0f

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
    }

    override fun process(block: AudioBlock) {
        if (!enabled || block.frameCount == 0 || block.channelCount < 2) return
        if (sampleRate <= 0 || hpCoeff <= 0f) return
        val channels = block.channelCount
        val frames = block.frameCount
        val samples = block.samples
        var lp = this.lp
        val extra = widthGain - 1f
        for (frame in 0 until frames) {
            val base = frame * channels
            val left = samples[base]
            val right = samples[base + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            // Low-pass the side (the bass); the high-passed remainder widens.
            lp += hpCoeff * (side - lp)
            val sideHp = side - lp
            val sideOut = side + sideHp * extra
            samples[base] = (mid + sideOut) * outputGain
            samples[base + 1] = (mid - sideOut) * outputGain
        }
        this.lp = lp
    }

    override fun flush() {
        lp = 0f
    }

    override fun reset() {
        lp = 0f
        sampleRate = 0
        channelCount = 0
        hpCoeff = 0f
    }
}
