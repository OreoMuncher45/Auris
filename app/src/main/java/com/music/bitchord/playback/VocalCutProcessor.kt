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
 * byte-identical — see [DspChain]'s exactness contract, and [process] for how
 * the engine is warmed up again before it next cuts.
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
     *
     * Going idle marks the engine stale here rather than in [process]: with
     * every stage idle the sink bypasses the chain, so [process] may never see
     * the zero, and the next enable would cut with filter memory frozen from
     * whenever Sing was last on.
     */
    @Volatile
    var level: Float = 0f
        set(value) {
            field = value
            if (value <= SING_IDLE_LEVEL) engineStale = true
        }

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

    /** Which method is running, kept beside the engine it chose. */
    @Volatile
    private var method: SingMethod = AppSettings.singMethod.value

    private var configuredSampleRate: Int = VocalCutDsp.DEFAULT_SAMPLE_RATE

    /**
     * The engine's state doesn't belong to the audio about to arrive: Sing
     * was off, the method changed, or the stream was flushed (seek, new
     * track). Set from any thread; cleared on the audio thread by [process],
     * which resets and warms the engine before cutting.
     */
    @Volatile
    private var engineStale = true

    /** Frames of silent run-in left before the cut is applied. Audio thread only. */
    private var warmupFramesLeft = 0

    /**
     * The level this processor is actually applying.
     *
     * Studio takes the vocal out further down the chain, so the filter here
     * stands down entirely while it runs: the two would otherwise both cut
     * the same voice, and the chain's own "nothing is happening" check would
     * never be true.
     */
    val effectiveLevel: Float
        get() = if (method == SingMethod.NEURAL) 0f else level

    /**
     * Switch engines, configured for the rate this processor is already
     * running at. The new engine is marked stale, so [process] resets and
     * warms it before its first cut sample.
     *
     * Called from the settings collector rather than from inside [process],
     * so the swap happens between callbacks and never mid-buffer.
     */
    fun setMethod(method: SingMethod) {
        if (method == this.method) return
        this.method = method
        if (method != SingMethod.NEURAL) {
            // Studio keeps the current filter: it never runs while Studio is on.
            engine = engineFactory(method).apply { configure(configuredSampleRate) }
        }
        engineStale = true
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        configuredSampleRate = sampleRate
        engine.configure(sampleRate)
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Returns before touching a sample when idle or non-stereo, so a
     * disabled Sing — or a mono track — passes through byte-identical.
     *
     * Off means off: while Sing is idle (or Studio is cutting instead) the
     * engine doesn't run at all. Tracking its filters and voice gate through
     * every song in case the slider moved cost ~14% of a core during all
     * playback (perf ticket 03).
     *
     * The price is paid on the way back in instead. Whenever the engine is
     * stale — Sing raised from off, a new method, a seek or a new track — it
     * starts fresh and runs [WARMUP_MS] of audio at zero cut, tracking but
     * writing nothing, so its crossover filters settle and the voice
     * detector's history fills before the first cut sample. Cutting through
     * cold filters would click. Accepted cost: the cut starts ~85 ms later,
     * and the voice gate starts fail-open, so in an instrumental passage it
     * may cut briefly (until its ~400 ms release) before it learns there is
     * no singer. An engine with no state ([SingEngine.needsWarmup] false)
     * cuts at once.
     */
    override fun process(block: AudioBlock) {
        if (block.channelCount != 2 || block.frameCount == 0) return
        val cut = effectiveLevel
        if (cut <= SING_IDLE_LEVEL) {
            engineStale = true
            return
        }
        val engine = engine
        if (engineStale) {
            engineStale = false
            engine.reset()
            warmupFramesLeft = if (engine.needsWarmup) configuredSampleRate * WARMUP_MS / 1000 else 0
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

    /** A seek or a new stream: the engine's history no longer fits the audio. */
    override fun onFlush() {
        engine.reset()
        engineStale = true
    }

    override fun onReset() {
        engine.reset()
        engineStale = true
    }

    /**
     * The 16-bit path. Only reached when this processor sits in a Media3
     * AudioProcessor chain directly; the app's players use [process] via
     * [DspChain], which is where the idle skip and the warm-up live.
     */
    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * BYTES_PER_FRAME)

        val cut = effectiveLevel
        if (cut <= SING_IDLE_LEVEL) {
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

        /** Silent run-in before a stale engine cuts; see [process]. */
        const val WARMUP_MS = 85
    }
}
