package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.PcmBoundary
import java.nio.ByteOrder

/**
 * Apple Music Sing equivalent: turns the lead vocal down (or off) so the
 * listener can sing over the track.
 *
 * Three engines, one slider — see [SingMethod] for what each one is for and
 * which kind of mix it suits. This is a thin shell over whichever pure kernel
 * is selected, so all the math stays testable without an audio stack.
 *
 * A disabled Sing doesn't run its engine at all and leaves the block
 * byte-identical — see [DspChain]'s exactness contract and [process] for how
 * the engine is warmed up again when the slider is raised.
 */
@UnstableApi
class VocalCutProcessor internal constructor(
    /** Builds the engine for a method; tests pass a recording fake. */
    private val engineFactory: (SingMethod) -> SingEngine,
) : BaseAudioProcessor(), FloatAudioProcessor {

    constructor() : this(::engineFor)

    /**
     * How much of the vocal band to remove, 0..1. Written from the UI thread via
     * [AppSettings.singLevel][com.music.bitchord.data.settings.AppSettings.singLevel];
     * read on the audio thread, hence volatile.
     */
    @Volatile
    var level: Float = 0f

    /**
     * Which remover is running. Swapped from the UI thread via [setMethod],
     * read on the audio thread, hence volatile.
     *
     * The three engines are kept alive one at a time rather than all three at
     * once: each carries filter memory sized for its own band, and holding
     * three of them per player to switch between costs memory nobody asked for.
     */
    @Volatile
    private var engine: SingEngine = engineFactory(AppSettings.singMethod.value)

    private var configuredSampleRate: Int = VocalCutDsp.DEFAULT_SAMPLE_RATE

    /** Audio-thread state: Sing was off on the last block, so the engine is stale. */
    private var idle = true

    /** Frames of silent run-in left before the cut is applied. Audio thread only. */
    private var warmupFramesLeft = 0

    /**
     * Switch engines, configured for the rate this processor is already
     * running at and reset so no filter memory from the last method leaks into
     * the first frame of the new one.
     *
     * Called from the player thread rather than from inside [process], so the
     * swap happens between callbacks and never mid-buffer.
     */
    fun setMethod(method: SingMethod) {
        if (method == currentMethod()) return
        engine = engineFactory(method).apply {
            configure(configuredSampleRate)
            reset()
        }
    }

    private fun currentMethod(): SingMethod = when (engine) {
        is VocalCutOriginal -> SingMethod.ORIGINAL
        is VocalCutBandLimited -> SingMethod.BAND_LIMITED
        else -> SingMethod.VOICE_GATE
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        configuredSampleRate = sampleRate
        engine.configure(sampleRate)
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     *
     * Off means off: while [level] is idle the engine doesn't run at all, and
     * the block passes through byte-identical. Tracking the engine's filters
     * and voice gate through every song just in case the slider moved cost
     * ~14% of a core during all playback, for a feature that is off nearly
     * all the time.
     *
     * The price is paid on the way back in instead. Raised from off, the
     * engine starts fresh and runs [WARMUP_MS] of audio at zero cut — tracking
     * but writing nothing — so its crossover filters settle and the voice
     * detector's history fills before the first cut sample. Cutting through
     * cold filters would click. The voice gate then starts from its fail-open
     * state, so in an instrumental passage it may cut briefly (until its
     * ~400 ms release) before it learns there is no singer.
     */
    override fun process(block: AudioBlock) {
        if (block.channelCount != 2 || block.frameCount == 0) return
        val cut = level
        if (cut <= IDLE_LEVEL) {
            idle = true
            return
        }
        val engine = engine
        if (idle) {
            idle = false
            engine.reset()
            warmupFramesLeft = configuredSampleRate * WARMUP_MS / 1000
        }
        if (warmupFramesLeft > 0) {
            // Whole blocks only: the engine takes no offset. Overshoot is less
            // than one block (decoder-sized, ~20 ms for Opus; 4096 frames at most).
            engine.processSpan(block.samples, block.frameCount * 2, 0f)
            warmupFramesLeft -= block.frameCount
            return
        }
        engine.processSpan(block.samples, block.frameCount * 2, cut)
    }

    /**
     * Stereo 16-bit only, for the same reason as
     * [SpatialAudioProcessor.onConfigure]: bowing out with NOT_SET keeps
     * mono and multichannel tracks playable instead of failing the sink.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.sampleRate > 0) {
            configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
        return inputAudioFormat
    }

    override fun onFlush() = engine.reset()

    override fun onReset() = engine.reset()

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * BYTES_PER_FRAME)

        val cut = level
        if (cut <= IDLE_LEVEL) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val invScale = 1.0f / 32768.0f
        repeat(frameCount) {
            val left = inputBuffer.short.toFloat() * invScale
            val right = inputBuffer.short.toFloat() * invScale
            val out = engine.processFrame(left, right, cut)
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(out[0]))
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(out[1]))
        }
        outputBuffer.flip()
    }

    internal companion object {
        private const val BYTES_PER_FRAME = 4 // stereo, 16-bit

        /** Silent run-in after Sing is raised from off; see [process]. */
        const val WARMUP_MS = 85

        /** At or below this, Sing is off — the same threshold every engine uses. */
        private const val IDLE_LEVEL = 0.001f
    }
}
