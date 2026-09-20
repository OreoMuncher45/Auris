package com.music.bitchord.playback.audio

import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.playback.EqualizerProcessor
import com.music.bitchord.playback.LoudnessProcessor
import com.music.bitchord.playback.SpatialAudioProcessor
import com.music.bitchord.playback.TransitionFilterProcessor

/**
 * Composite DSP chain executing BitChord's custom audio processors in their canonical sequence:
 *
 * AudioBlock(Float32) -> LoudnessProcessor -> SpatialAudioProcessor -> EqualizerProcessor
 *   -> TransitionFilterProcessor -> AudioBlock(Float32)
 *
 * Operates purely on in-place Float32 audio blocks without intermediate fixed-point quantization,
 * preserving full dynamic range and headroom.
 *
 * ## Why loudness goes first
 *
 * It is the only stage that corrects the *recording* rather than expressing a
 * preference about it, and the only one whose correct value depends on hearing
 * the track as it was mastered. Running it after the equaliser would have it
 * measure the listener's tone curve as though that were part of the
 * recording — a heavy bass boost would read as a loud track and be
 * attenuated, so the equaliser would end up quietly fighting itself. Putting
 * it in front means every stage after it sees tracks arriving at a consistent
 * level, which is also what makes the equaliser's make-up attenuation behave
 * the same way from one song to the next.
 *
 * ## Bit-perfect
 *
 * [bitPerfect] makes [process] return before any stage runs. Every processor
 * here already self-bypasses when its own setting is off, so with all four off
 * the samples come out unchanged anyway — but "unchanged because four separate
 * switches all happen to be off" is not a guarantee, it is a coincidence that
 * has held so far. A listener who left the equaliser on and then turned
 * bit-perfect on is asking for two contradictory things, and the mode has to
 * win without depending on anything else having tidied up first.
 *
 * The service does also switch the processors off (see
 * `PlaybackService.applyBitPerfectDependentSettings`), so the two agree; this
 * flag is the one that makes the promise enforceable in one place.
 */
class DspChain(
    val loudness: LoudnessProcessor = LoudnessProcessor(),
    val spatial: SpatialAudioProcessor = SpatialAudioProcessor(),
    val equalizer: EqualizerProcessor = EqualizerProcessor(),
    val transition: TransitionFilterProcessor = TransitionFilterProcessor(),
    private val bitPerfect: Boolean = false,
) : FloatAudioProcessor {

    private var currentSampleRate: Int = 0
    private var processCounter: Long = 0L

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.currentSampleRate = sampleRate
        loudness.configure(sampleRate, channelCount)
        spatial.configure(sampleRate, channelCount)
        equalizer.configure(sampleRate, channelCount)
        transition.configure(sampleRate, channelCount)
    }

    override fun process(block: AudioBlock) {
        // Before the frame check and before the debug logging: in bit-perfect
        // mode nothing in this class may touch, measure or even look at the
        // audio.
        if (bitPerfect) return
        if (block.frameCount == 0) return

        if (BuildConfig.DEBUG) {
            processCounter++
            if (processCounter == 1L || processCounter % 500L == 0L) {
                try {
                    val count = processCounter
                    val frames = block.frameCount
                    val sr = currentSampleRate
                    val spatialOn = spatial.enabled
                    val eqOn = equalizer.isEnabled
                    val transitionOn = transition.isFiltering
                    val gainDb = loudness.snapshot.appliedGainDb
                    Log.d(
                        TAG,
                        "process() #$count frames=$frames sr=$sr loudness=${gainDb}dB " +
                            "spatial=$spatialOn eq=$eqOn transition=$transitionOn",
                    )
                } catch (_: Throwable) {
                }
            }
        }

        loudness.process(block)
        spatial.process(block)
        equalizer.process(block)
        transition.process(block)
    }

    @Suppress("DEPRECATION")
    override fun flush() {
        loudness.flush()
        spatial.flush()
        equalizer.flush()
        transition.flush()
    }

    override fun reset() {
        processCounter = 0L
        loudness.reset()
        spatial.reset()
        equalizer.reset()
        transition.reset()
    }

    companion object {
        private const val TAG = "DspChain"
    }
}
