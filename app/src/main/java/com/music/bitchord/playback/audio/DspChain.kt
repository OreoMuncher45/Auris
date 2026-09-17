package com.music.bitchord.playback.audio

import com.music.bitchord.playback.EqualizerProcessor
import com.music.bitchord.playback.SpatialAudioProcessor
import com.music.bitchord.playback.TransitionFilterProcessor

/**
 * Composite DSP chain executing BitChord's custom audio processors in their canonical sequence:
 *
 * AudioBlock(Float32) -> SpatialAudioProcessor -> EqualizerProcessor -> TransitionFilterProcessor -> AudioBlock(Float32)
 *
 * Operates purely on in-place Float32 audio blocks without intermediate fixed-point quantization,
 * preserving full dynamic range and headroom.
 */
class DspChain(
    val spatial: SpatialAudioProcessor = SpatialAudioProcessor(),
    val equalizer: EqualizerProcessor = EqualizerProcessor(),
    val transition: TransitionFilterProcessor = TransitionFilterProcessor(),
) : FloatAudioProcessor {

    override fun configure(sampleRate: Int, channelCount: Int) {
        spatial.configure(sampleRate, channelCount)
        equalizer.configure(sampleRate, channelCount)
        transition.configure(sampleRate, channelCount)
    }

    override fun process(block: AudioBlock) {
        if (block.frameCount == 0) return
        spatial.process(block)
        equalizer.process(block)
        transition.process(block)
    }

    @Suppress("DEPRECATION")
    override fun flush() {
        spatial.flush()
        equalizer.flush()
        transition.flush()
    }

    override fun reset() {
        spatial.reset()
        equalizer.reset()
        transition.reset()
    }
}
