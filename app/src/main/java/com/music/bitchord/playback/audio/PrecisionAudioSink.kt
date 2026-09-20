package com.music.bitchord.playback.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.music.bitchord.BuildConfig
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.usb.DirectAudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Precision audio sink intercepting decoder PCM buffers before Media3's internal
 * processing pipeline can bypass BitChord's custom DSP chain.
 *
 * Architecture:
 * - In Precision Mode (for all supported linear PCM sources):
 *   decoder PCM -> PcmBoundary.decode -> AudioBlock (Float32) -> DspChain (Spatial -> EQ -> Transition)
 *   -> PcmBoundary.encode -> delegate [DefaultAudioSink].
 *   Internal DSP precision and AudioTrack output precision are independent:
 *   - Float-capable route ([enableFloatOutput] is true and supported by delegate):
 *     encodes to Float32 PCM -> delegate [DefaultAudioSink] (configured for Float32).
 *   - PCM16-only route ([enableFloatOutput] is false or unsupported by route/hardware):
 *     encodes to PCM16 -> delegate [DefaultAudioSink] (configured for PCM16).
 *
 *   Downstream, custom processors are excluded from [DefaultAudioSink]'s internal processor chain,
 *   guaranteeing ZERO duplicate processing, while SilenceSkipping and Sonic processors remain functional.
 *
 * - In Fallback/Legacy Mode (for non-linear PCM or unsupported channel counts/sample rates):
 *   decoder buffers pass straight through to [DefaultAudioSink].
 *
 * - In Bit-Perfect Mode ([bitPerfect]): precision stays active, the [DspChain] is bypassed
 *   wholesale, and the output encoding is chosen to preserve the source rather than to suit a
 *   preference — see [bitPerfectTargetEncoding].
 *
 *   Keeping the precision path is deliberate, and the opposite of the obvious approach.
 *   Handing raw decoder buffers to [DefaultAudioSink] *looks* more faithful, but it delegates
 *   the conversion to Media3, which has only two linear-PCM output encodings — float and
 *   16-bit — and picks between them on a flag rather than on what the source needs. With float
 *   output off it inserts `ToInt16PcmAudioProcessor` unconditionally, so "pass it through
 *   untouched" is in practice "downconvert everything to 16-bit". Staying on the precision path
 *   means [PcmBoundary] does the conversion instead, at power-of-two scale factors that
 *   provably round-trip 16- and 24-bit integers unchanged.
 *
 *   What the mode cannot do is make 32-bit integer PCM exact: float32 carries 24 bits of
 *   significand, and nothing between here and AudioTrack carries more.
 *
 * Threading & Buffer Contract:
 * - Steady-state execution avoids heap allocations by reusing an audio-thread-owned [AudioBlock]
 *   and a native-order [outputByteBuffer].
 * - Partial delegate consumption is fully supported: when [delegate] returns false, the exact same
 *   [outputByteBuffer] instance is retained and drained on subsequent ticks before accepting new input.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PrecisionAudioSink(
    private val delegate: AudioSink,
    val dspChain: DspChain,
    private val enableFloatOutput: Boolean,
    val directAudioOutput: DirectAudioOutput? = null,
    private val preferredOutputEncodingProvider: ((Format) -> PcmEncoding?)? = null,
    /**
     * Hands decoder buffers to [delegate] untouched. Fixed for the lifetime of
     * the sink rather than a live flag: changing it means changing what the
     * delegate was configured for, so the service rebuilds both players when
     * the setting moves — see `PlaybackService.rebuildPlayersForOutput`.
     */
    private val bitPerfect: Boolean = false,
) : ForwardingAudioSink(delegate) {

    /** Whether the precision Float32 DSP path is currently active for the configured format. */
    var isPrecisionActive: Boolean = false
        private set

    /** The PCM encoding format of the incoming decoder buffers when precision mode is active. */
    var inputPcmEncoding: PcmEncoding? = null
        private set

    /** The target PCM encoding format written to [delegate] when precision mode is active. */
    var targetOutputEncoding: PcmEncoding? = null
        private set

    /** The format passed to the most recent [configure] invocation. */
    var activeFormat: Format? = null
        private set

    private var processCounter: Long = 0L

    private var audioBlock: AudioBlock = AudioBlock(
        channelCount = AudioBlock.DEFAULT_CHANNELS,
        capacityFrames = DEFAULT_CAPACITY_FRAMES,
    )

    private var outputByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    private var pendingPresentationTimeUs: Long = C.TIME_UNSET
    private var pendingAccessUnitCount: Int = 0

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        activeFormat = format

        if (shouldActivatePrecision(format)) {
            val encoding = mapToPcmEncoding(format.pcmEncoding)
            if (encoding != null) {
                val sampleRate = format.sampleRate
                val channelCount = format.channelCount

                // 1. Configure the Float32 DSP chain
                dspChain.configure(sampleRate, channelCount)

                // 2. Ensure internal buffers are sized for max frames and channel count
                ensureBuffers(channelCount)

                // 3. Determine target output encoding based on preferred encoding, float output setting & delegate capability
                val floatFormat = format.buildUpon()
                    .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                    .build()
                val pcm24Format = format.buildUpon()
                    .setPcmEncoding(C.ENCODING_PCM_24BIT)
                    .build()
                val pcm16Format = format.buildUpon()
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build()

                val preferredEncoding = preferredOutputEncodingProvider?.invoke(format)
                val isHighResSource = encoding.bitDepth > 16

                val targetEncoding = if (bitPerfect) {
                    bitPerfectTargetEncoding(encoding, floatFormat)
                } else {
                    when {
                        preferredEncoding == PcmEncoding.PCM_FLOAT && delegate.supportsFormat(floatFormat) -> PcmEncoding.PCM_FLOAT
                        preferredEncoding == PcmEncoding.PCM_24BIT_PACKED && delegate.supportsFormat(pcm24Format) -> PcmEncoding.PCM_24BIT_PACKED
                        preferredEncoding == PcmEncoding.PCM_16BIT -> PcmEncoding.PCM_16BIT
                        preferredEncoding == null && enableFloatOutput && delegate.supportsFormat(floatFormat) -> PcmEncoding.PCM_FLOAT
                        else -> PcmEncoding.PCM_16BIT
                    }
                }

                // 4. Configure delegate DefaultAudioSink
                val delegateFormat = format.buildUpon()
                    .setPcmEncoding(targetEncoding.toMedia3PcmEncoding())
                    .build()

                val delegateConfig = AudioSink.AudioSinkConfig.Builder(delegateFormat)
                    .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
                    .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                    .setTimeline(audioSinkConfig.timeline)
                    .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                    .build()

                try {
                    delegate.configure(delegateConfig)
                    inputPcmEncoding = encoding
                    targetOutputEncoding = targetEncoding
                    isPrecisionActive = true
                    publishTelemetry(encoding, targetEncoding, isPrecisionActive = true)
                    logConfig(
                        precisionActive = true,
                        inputEncoding = encoding.name,
                        sampleRate = sampleRate,
                        channelCount = channelCount,
                        targetOutputEncoding = targetEncoding.name,
                        enableFloatOutput = enableFloatOutput,
                        delegateEncoding = delegateFormat.pcmEncoding,
                    )
                    return
                } catch (e: Exception) {
                    // If Float32 or PCM24 delegate config failed, try falling back to PCM16 before aborting precision
                    if (targetEncoding != PcmEncoding.PCM_16BIT) {
                        try {
                            val fallbackPcm16Config = AudioSink.AudioSinkConfig.Builder(pcm16Format)
                                .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
                                .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                                .setTimeline(audioSinkConfig.timeline)
                                .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                                .build()
                            delegate.configure(fallbackPcm16Config)
                            inputPcmEncoding = encoding
                            targetOutputEncoding = PcmEncoding.PCM_16BIT
                            isPrecisionActive = true
                            publishTelemetry(encoding, PcmEncoding.PCM_16BIT, isPrecisionActive = true)
                            logConfig(
                                precisionActive = true,
                                inputEncoding = encoding.name,
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                targetOutputEncoding = PcmEncoding.PCM_16BIT.name,
                                enableFloatOutput = enableFloatOutput,
                                delegateEncoding = pcm16Format.pcmEncoding,
                            )
                            return
                        } catch (e2: Exception) {
                            // Delegate configuration failed completely; fall back safely
                        }
                    }
                    isPrecisionActive = false
                    inputPcmEncoding = null
                    targetOutputEncoding = null
                }
            }
        }

        // Fallback / legacy mode: forward configuration unchanged. Reached in
        // bit-perfect mode too, for a non-linear-PCM stream that precision
        // cannot handle either way — in which case nothing here is altering
        // samples, but nothing here can vouch for what Media3 does with them.
        isPrecisionActive = false
        inputPcmEncoding = null
        targetOutputEncoding = null
        if (bitPerfect) {
            AudioOutputStatus.publishBitPerfect(
                active = false,
                detail = "${encodingLabel(format.pcmEncoding)} is not linear PCM",
            )
        }
        AudioOutputStatus.publishDsp(decoderOutputEncoding = null, dspFormat = "Legacy PCM")
        delegate.configure(audioSinkConfig)
        logConfig(
            precisionActive = false,
            inputEncoding = format.pcmEncoding.toString(),
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            targetOutputEncoding = "NONE",
            enableFloatOutput = enableFloatOutput,
            delegateEncoding = format.pcmEncoding,
        )
    }

    override fun handleBuffer(
        inputBuffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!isPrecisionActive) {
            return delegate.handleBuffer(inputBuffer, presentationTimeUs, encodedAccessUnitCount)
        }

        val inEncoding = inputPcmEncoding ?: return delegate.handleBuffer(
            inputBuffer,
            presentationTimeUs,
            encodedAccessUnitCount,
        )
        val outEncoding = targetOutputEncoding ?: return delegate.handleBuffer(
            inputBuffer,
            presentationTimeUs,
            encodedAccessUnitCount,
        )

        val channelCount = audioBlock.channelCount
        val bytesPerFrame = inEncoding.bytesPerFrame(channelCount)
        if (bytesPerFrame <= 0) return true

        // 1. Drain pending output from previous cycle if delegate had backpressure
        if (outputByteBuffer.hasRemaining()) {
            val consumed = delegate.handleBuffer(
                outputByteBuffer,
                pendingPresentationTimeUs,
                pendingAccessUnitCount,
            )
            if (!consumed || outputByteBuffer.hasRemaining()) {
                // Delegate still busy; backpressure to renderer
                return false
            }
        }

        // 2. Output buffer is drained; process available input in blocks
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val availableFrames = inputBuffer.remaining() / bytesPerFrame
            if (availableFrames <= 0) break

            val framesToRead = minOf(availableFrames, audioBlock.capacityFrames)
            val decodedFrames = PcmBoundary.decode(
                inputBuffer = inputBuffer,
                encoding = inEncoding,
                destinationBlock = audioBlock,
                maxFrames = framesToRead,
            )
            if (decodedFrames <= 0) break

            if (BuildConfig.DEBUG) {
                processCounter++
                if (processCounter == 1L || processCounter % 500L == 0L) {
                    try {
                        val count = processCounter
                        val active = isPrecisionActive
                        val inEnc = inEncoding.name
                        val outEnc = outEncoding.name
                        val frames = decodedFrames
                        Log.d(
                            TAG,
                            "handleBuffer() #$count precisionActive=$active inEnc=$inEnc outEnc=$outEnc frames=$frames",
                        )
                    } catch (_: Throwable) {
                    }
                }
            }

            // Process normalized Float32 samples through custom DSP chain
            dspChain.process(audioBlock)

            // Encode processed samples to target PCM output buffer (Float32 or PCM16)
            outputByteBuffer.clear()
            PcmBoundary.encode(
                sourceBlock = audioBlock,
                encoding = outEncoding,
                outputBuffer = outputByteBuffer,
            )
            outputByteBuffer.flip()

            pendingPresentationTimeUs = presentationTimeUs
            pendingAccessUnitCount = encodedAccessUnitCount

            val consumed = delegate.handleBuffer(
                outputByteBuffer,
                presentationTimeUs,
                encodedAccessUnitCount,
            )

            if (!consumed || outputByteBuffer.hasRemaining()) {
                // Delegate could not accept all output frames in this cycle
                return false
            }
        }

        // Drop any incomplete trailing frame bytes so we don't stall
        if (inputBuffer.remaining() in 1 until bytesPerFrame) {
            inputBuffer.position(inputBuffer.limit())
        }

        return !inputBuffer.hasRemaining() && !outputByteBuffer.hasRemaining()
    }

    override fun flush() {
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        audioBlock.clear()
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingAccessUnitCount = 0
        if (isPrecisionActive) {
            dspChain.flush()
        }
        delegate.flush()
    }

    override fun reset() {
        processCounter = 0L
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        audioBlock.clear()
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingAccessUnitCount = 0
        if (isPrecisionActive) {
            dspChain.reset()
        }
        isPrecisionActive = false
        inputPcmEncoding = null
        targetOutputEncoding = null
        activeFormat = null
        AudioOutputStatus.publishBitPerfect(active = false, detail = null)
        AudioOutputStatus.publishDsp(decoderOutputEncoding = null, dspFormat = "Float32")
        delegate.reset()
    }

    override fun handleDiscontinuity() {
        delegate.handleDiscontinuity()
    }

    override fun playToEndOfStream() {
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            delegate.handleBuffer(outputByteBuffer, pendingPresentationTimeUs, pendingAccessUnitCount)
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            return false
        }
        return delegate.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            return true
        }
        return delegate.hasPendingData()
    }

    override fun supportsFormat(format: Format): Boolean {
        if (shouldActivatePrecision(format)) {
            val pcm16Format = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build()
            val floatFormat = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            val delegateCanPlay = (enableFloatOutput && delegate.supportsFormat(floatFormat)) ||
                delegate.supportsFormat(pcm16Format)
            if (delegateCanPlay) {
                return true
            }
        }
        return delegate.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldActivatePrecision(format)) {
            val pcm16Format = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build()
            val floatFormat = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            val delegateCanPlay = (enableFloatOutput && delegate.getFormatSupport(floatFormat) != AudioSink.SINK_FORMAT_UNSUPPORTED) ||
                (delegate.getFormatSupport(pcm16Format) != AudioSink.SINK_FORMAT_UNSUPPORTED)

            if (delegateCanPlay) {
                // PrecisionAudioSink natively consumes all supported linear PCM formats (Float32, PCM16, PCM24, PCM32)
                // directly into its canonical Float32 AudioBlock without requiring Media3 to transcode.
                return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            }
        }
        return delegate.getFormatSupport(format)
    }

    private fun shouldActivatePrecision(format: Format): Boolean {
        val sampleMimeType = format.sampleMimeType
        if (sampleMimeType != null && sampleMimeType != MimeTypes.AUDIO_RAW) return false
        if (!Util.isEncodingLinearPcm(format.pcmEncoding)) return false
        if (mapToPcmEncoding(format.pcmEncoding) == null) return false
        if (format.channelCount !in 1..2) return false
        if (format.sampleRate <= 0) return false
        return true
    }

    private fun ensureBuffers(channelCount: Int) {
        if (audioBlock.channelCount != channelCount) {
            audioBlock = AudioBlock(channelCount = channelCount, capacityFrames = DEFAULT_CAPACITY_FRAMES)
        } else {
            audioBlock.reset(0)
        }

        val requiredCapacity = audioBlock.capacityFrames * channelCount * PcmEncoding.PCM_FLOAT.bytesPerSample
        if (outputByteBuffer.capacity() < requiredCapacity) {
            outputByteBuffer = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder())
        }
        outputByteBuffer.clear()
        outputByteBuffer.flip()
    }

    /**
     * The output encoding bit-perfect mode asks the delegate for.
     *
     * Constrained by what [DefaultAudioSink] can actually open a track in,
     * which is only ever PCM float or PCM 16-bit for linear PCM — its
     * `configure` inserts `ToInt16PcmAudioProcessor` for every input encoding
     * whenever float output is off, and `ToFloatPcmAudioProcessor` when it is
     * on. There is no 24-bit or 32-bit AudioTrack path through it, so asking
     * for one gets a silent downconvert rather than what was asked for.
     *
     * That leaves two honest choices:
     * - A 16-bit source asks for 16-bit, which is exact on any route.
     * - Anything above 16 bits asks for float, because float32's 24-bit
     *   significand is the only thing here that holds a 24-bit sample whole.
     *
     * The float request is checked with [AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY]
     * rather than `supportsFormat`, which is too weak to be a gate: with float
     * output disabled, `getFormatSupport` rewrites a float format to 16-bit
     * and still answers SUPPORTED_WITH_TRANSCODING, so `supportsFormat` says
     * "yes" about a track it is going to downconvert.
     */
    private fun bitPerfectTargetEncoding(source: PcmEncoding, floatFormat: Format): PcmEncoding {
        if (source == PcmEncoding.PCM_16BIT) return PcmEncoding.PCM_16BIT
        val floatIsNative =
            delegate.getFormatSupport(floatFormat) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        return if (floatIsNative) PcmEncoding.PCM_FLOAT else PcmEncoding.PCM_16BIT
    }

    /**
     * Reports whether bit-perfect mode is actually getting what it asked for.
     *
     * Bypassing the DSP chain is only half the promise; the other half is
     * whether the samples survive the trip to AudioTrack, and that depends on
     * the route. The combinations that are genuinely lossless:
     *
     * - 16-bit source to a 16-bit track. `PcmBoundary` scales by 32768, a
     *   power of two, so the float round trip recovers every original integer.
     * - 24-bit source to a float track. Scaled by 8388608, likewise exact, and
     *   float32 has the 24 bits of significand to hold the result.
     * - A float source to a float track, which is a copy.
     *
     * Everything else is reported as inexact, and there are two of those worth
     * naming. A hi-res source on a route that will not open a float track gets
     * 16-bit, because Media3 offers nothing between. And **32-bit integer PCM
     * cannot be delivered exactly at all** — float32 carries 24 bits of
     * significand, so eight bits go regardless of route, and no setting in
     * this app can change that while `DefaultAudioSink` is doing the writing.
     */
    private fun publishBitPerfectVerdict(source: PcmEncoding, target: PcmEncoding) {
        val exact = when (source) {
            PcmEncoding.PCM_16BIT -> target == PcmEncoding.PCM_16BIT
            PcmEncoding.PCM_24BIT_PACKED -> target == PcmEncoding.PCM_FLOAT
            PcmEncoding.PCM_FLOAT -> target == PcmEncoding.PCM_FLOAT
            // 24 bits of significand cannot hold 32.
            PcmEncoding.PCM_32BIT -> false
        }
        val sourceLabel = encodingLabel(mapFromPcmEncoding(source))
        val detail = when {
            exact -> "$sourceLabel → ${encodingLabel(mapFromPcmEncoding(target))}"
            source == PcmEncoding.PCM_32BIT ->
                "32-bit PCM exceeds what AudioTrack can carry; 8 bits lost"
            else -> "$sourceLabel → 16-bit; this route cannot open a float track"
        }
        AudioOutputStatus.publishBitPerfect(active = exact, detail = detail)
        AudioOutputStatus.publishDsp(
            decoderOutputEncoding = sourceLabel,
            dspFormat = if (exact) "Bit-perfect" else "Bit-perfect (converted)",
        )
    }

    private fun publishTelemetry(
        inEncoding: PcmEncoding,
        outEncoding: PcmEncoding,
        isPrecisionActive: Boolean,
    ) {
        if (isPrecisionActive) {
            if (bitPerfect) {
                publishBitPerfectVerdict(inEncoding, outEncoding)
                return
            }
            val label = when (inEncoding) {
                PcmEncoding.PCM_FLOAT -> "Float32"
                PcmEncoding.PCM_24BIT_PACKED -> "24-bit PCM"
                PcmEncoding.PCM_32BIT -> "32-bit PCM"
                PcmEncoding.PCM_16BIT -> "16-bit PCM"
            }
            AudioOutputStatus.publishBitPerfect(active = false, detail = null)
            AudioOutputStatus.publishDsp(decoderOutputEncoding = label, dspFormat = "Float32")
        } else {
            AudioOutputStatus.publishDsp(decoderOutputEncoding = null, dspFormat = "Legacy PCM")
        }
    }

    private fun logConfig(
        precisionActive: Boolean,
        inputEncoding: String,
        sampleRate: Int,
        channelCount: Int,
        targetOutputEncoding: String,
        enableFloatOutput: Boolean,
        delegateEncoding: Int,
    ) {
        if (BuildConfig.DEBUG) {
            try {
                Log.d(
                    TAG,
                    "configure() precisionActive=$precisionActive inputEncoding=$inputEncoding sr=$sampleRate ch=$channelCount targetOutputEncoding=$targetOutputEncoding enableFloatOutput=$enableFloatOutput delegateEncoding=$delegateEncoding",
                )
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "PrecisionAudioSink"
        const val DEFAULT_CAPACITY_FRAMES: Int = 4096

        /** Human-readable name for a Media3 PCM encoding constant. */
        fun encodingLabel(pcmEncoding: Int): String = when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> "16-bit PCM"
            C.ENCODING_PCM_24BIT -> "24-bit PCM"
            C.ENCODING_PCM_32BIT -> "32-bit PCM"
            C.ENCODING_PCM_FLOAT -> "Float32"
            else -> "Non-PCM"
        }

        fun mapToPcmEncoding(pcmEncoding: Int): PcmEncoding? = when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16BIT
            C.ENCODING_PCM_24BIT -> PcmEncoding.PCM_24BIT_PACKED
            C.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32BIT
            C.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
            else -> null
        }

        fun mapFromPcmEncoding(pcmEncoding: PcmEncoding): Int = when (pcmEncoding) {
            PcmEncoding.PCM_16BIT -> C.ENCODING_PCM_16BIT
            PcmEncoding.PCM_24BIT_PACKED -> C.ENCODING_PCM_24BIT
            PcmEncoding.PCM_32BIT -> C.ENCODING_PCM_32BIT
            PcmEncoding.PCM_FLOAT -> C.ENCODING_PCM_FLOAT
        }

        fun PcmEncoding.toMedia3PcmEncoding(): Int = mapFromPcmEncoding(this)
    }
}
