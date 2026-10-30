package com.music.bitchord.playback.audio

import android.util.Log
import android.os.SystemClock
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
import com.music.bitchord.playback.usb.ExclusiveUsbOutput
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
 *   - Float-capable route ([enableFloatOutput] is true and the delegate opens float natively):
 *     encodes to Float32 PCM -> delegate [DefaultAudioSink] (configured for Float32).
 *   - PCM16-only route ([enableFloatOutput] is false or unsupported by route/hardware):
 *     encodes to PCM16 -> delegate [DefaultAudioSink] (configured for PCM16).
 *
 *   Downstream, custom processors are excluded from [DefaultAudioSink]'s internal processor chain,
 *   guaranteeing ZERO duplicate processing, while SilenceSkipping and Sonic processors remain functional.
 *
 *   With every DSP stage idle and matching input/output encodings, the decoder's
 *   buffer is handed to the delegate untouched: the decode and re-encode would
 *   have reproduced it byte for byte, so the cheapest correct path is no path.
 *
 * - In Fallback/Legacy Mode (for non-linear PCM sources — a passthrough or offload
 *   bitstream): decoder buffers pass straight through to [DefaultAudioSink]. Nothing
 *   here can process a compressed stream, and nothing here pretends to.
 *
 * ## Output encoding
 *
 * [DefaultAudioSink] has exactly two linear-PCM output encodings: float, and 16-bit.
 * Its `configure` puts `ToInt16PcmAudioProcessor` in the chain for every input
 * encoding whenever float output is off, and `ToFloatPcmAudioProcessor` when it is
 * on. There is no 24-bit or 32-bit AudioTrack path through it, so asking for one
 * gets a silent downconvert rather than what was asked for — which is why
 * [resolveTargetEncoding] only ever answers float or 16-bit, however high-res the
 * route claims to be.
 *
 * Float is checked with [AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY] rather than
 * `supportsFormat`, which is too weak to be a gate: with float output disabled,
 * `getFormatSupport` rewrites a float format to 16-bit and still answers
 * SUPPORTED_WITH_TRANSCODING, so `supportsFormat` says "yes" about a track it is
 * going to downconvert.
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
     * Whether this sink is the one the listener can currently hear.
     *
     * A crossfade keeps two players alive, each with its own sink, both writing
     * into the process-wide [AudioOutputStatus]. Without this the idle one wins
     * the race regularly — its `reset` publishes "not exact" over the audible
     * player's verdict, and its `configure` publishes the *next* track's format
     * as though it were the playing one. Telemetry is therefore the audible
     * sink's to write; the spare stays quiet.
     */
    private val isAudible: () -> Boolean = { true },
    /**
     * Whether the listener has asked for bit-perfect output — see
     * [com.music.bitchord.data.settings.AppSettings.bitPerfect]. Read per
     * decoder buffer, so a switch flipped mid-track stops altering the next
     * buffer instead of waiting for the next configure.
     */
    private val bitPerfect: () -> Boolean = { false },
    /**
     * The userspace usbdevfs session, when the listener has granted USB access
     * and the DAC is attached. Ported from LastWave: it writes isochronous
     * URBs straight to the endpoint, so AudioFlinger and the system mixer are
     * not in the path at all — the part [BitPerfectMixer] can only ask the
     * Android 14 mixer to do.
     */
    private val exclusiveUsb: ExclusiveUsbOutput? = null,
    /**
     * Whether exclusive USB output should be attempted for the next configure.
     * Same switch as [bitPerfect]; separate so the caller decides the route.
     */
    private val exclusiveWanted: () -> Boolean = { false },
    /** Source bit depth, for float decodes that cannot name their own. */
    private val bitDepthHint: () -> Int? = { null },
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

    /** What [bitPerfect] said when the last buffer was processed. */
    private var dspBypassLatched = false

    /**
     * Whether this configure is actually rendering through the userspace USB
     * session. Only the renderer thread flips it, except for [reset]/[flush]
     * which Media3 also calls on that thread.
     */
    @Volatile private var exclusiveActive = false

    /** Set by [playToEndOfStream] once the exclusive stream has all its PCM. */
    private var exclusiveEnded = false

    /** Whether the renderer currently wants audio — used when leaving exclusive. */
    private var playing = false

    /** One adoption attempt per configured track; a failed claim is not retried per buffer. */
    private var lastExclusiveAttemptMs = 0L

    /**
     * Set when the exclusive open failed for a reason retrying cannot fix
     * (wrong clock, no matching alt). Without it the 5 s retry loop keeps
     * pausing and flushing the working AudioTrack, which is heard as a
     * dropout — and would fix nothing.
     */
    private var exclusiveAdoptionSuppressed = false

    /** Sample rate of the configured format, for timestamping split sub-blocks. */
    private var configuredSampleRate: Int = 0

    private var audioBlock: AudioBlock = AudioBlock(
        channelCount = AudioBlock.DEFAULT_CHANNELS,
        capacityFrames = DEFAULT_CAPACITY_FRAMES,
    )

    private var outputByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    /**
     * A decoder buffer the pass-through below handed to the delegate which the
     * delegate did not finish taking.
     *
     * The renderer hands the same buffer back on the next call, so nothing is
     * copied here — that would give back the entire cost the pass-through
     * exists to avoid. It is kept only so [hasPendingData] and [isEnded] can
     * answer for the bytes the delegate still holds.
     */
    private var passthroughBuffer: ByteBuffer? = null
    private var passthroughPresentationTimeUs: Long = C.TIME_UNSET
    private var passthroughAccessUnitCount: Int = 0

    private var pendingPresentationTimeUs: Long = C.TIME_UNSET
    private var pendingAccessUnitCount: Int = 0

    /**
     * Frames already emitted from the decoder buffer currently being drained,
     * and the timestamp that identifies it.
     *
     * Kept across calls rather than per call because backpressure splits one
     * buffer over several: when the delegate refuses a sub-block, the renderer
     * hands the same partially-consumed buffer back with its *original*
     * presentation time, and a per-call counter would restart at zero and
     * re-issue a timestamp already used.
     */
    private var timestampedInputTimeUs: Long = C.TIME_UNSET
    private var framesEmittedForInput: Long = 0L

    /**
     * Whether audio has flowed since the last flush — which is what tells a
     * gapless track boundary apart from a skip. Both reach [setOutputStreamOffsetUs],
     * but only a boundary arrives mid-stream, and only there is the chain's
     * "next track" the one actually starting.
     */
    private var streaming = false

    /**
     * End of the last block the DSP chain processed, in the renderer's
     * timebase — the same one [getCurrentPositionUs] answers in, so the gap
     * between the two is how far ahead of the speaker the chain is running.
     */
    private var processedEndUs: Long = C.TIME_UNSET

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        // A reconfigure starts a new format: a session latched to the old one
        // would be fed the wrong clock, so let it go before anything else.
        if (exclusiveActive) leaveExclusiveUsb(resume = false)
        val format = audioSinkConfig.format
        // The endpoint is shared by the A/B crossfade sinks. Only the audible
        // one may keep or close it; the standby configuring early is what
        // used to retune the DAC under the track that was still playing.
        if (!exclusiveActive && isAudible() &&
            !(exclusiveWanted() && shouldActivatePrecision(format) && format.channelCount in 1..2)
        ) {
            runCatching { exclusiveUsb?.reset() }
        }
        exclusiveEnded = false
        lastExclusiveAttemptMs = 0L
        exclusiveAdoptionSuppressed = false
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
                configuredSampleRate = sampleRate

                // 3. Determine target output encoding
                val floatFormat = format.buildUpon()
                    .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                    .build()
                val pcm16Format = format.buildUpon()
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build()

                val targetEncoding = resolveTargetEncoding(
                    preferred = preferredOutputEncodingProvider?.invoke(format),
                    floatFormat = floatFormat,
                )

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
                    publishTelemetry(encoding, targetEncoding)
                    logConfig(
                        precisionActive = true,
                        inputEncoding = encoding.name,
                        sampleRate = sampleRate,
                        channelCount = channelCount,
                        targetOutputEncoding = targetEncoding.name,
                        enableFloatOutput = enableFloatOutput,
                        delegateEncoding = delegateFormat.pcmEncoding,
                    )
                    if (startExclusiveIfWanted(format)) exclusiveAdoptionSuppressed = true
                    return
                } catch (e: Exception) {
                    // If the Float32 delegate config failed, try PCM16 before aborting precision
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
                            publishTelemetry(encoding, PcmEncoding.PCM_16BIT)
                            logConfig(
                                precisionActive = true,
                                inputEncoding = encoding.name,
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                targetOutputEncoding = PcmEncoding.PCM_16BIT.name,
                                enableFloatOutput = enableFloatOutput,
                                delegateEncoding = pcm16Format.pcmEncoding,
                            )
                            if (startExclusiveIfWanted(format)) exclusiveAdoptionSuppressed = true
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

        // Fallback / legacy mode: forward configuration unchanged. A passthrough
        // or offload bitstream reaches here, in which case nothing of ours is
        // altering samples — but nothing of ours can vouch for them either, and
        // no DSP stage can run on a stream that was never decoded to PCM.
        isPrecisionActive = false
        inputPcmEncoding = null
        targetOutputEncoding = null
        configuredSampleRate = 0
        // Bit-perfect is a promise about decoded PCM; a bitstream that was
        // never decoded has nothing for it to promise.
        dspBypassLatched = false
        if (isAudible()) {
            AudioOutputStatus.publishOutputExactness(
                exact = false,
                detail = "${encodingLabel(format.pcmEncoding)} is not linear PCM",
            )
            AudioOutputStatus.publishDsp(
                decoderOutputEncoding = null,
                dspFormat = "Legacy PCM",
                dspAvailable = false,
            )
        }
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

    /**
     * Hands the configured track to the userspace USB session when the
     * listener asked for bit-perfect and a DAC has granted access.
     *
     * The Android delegate is configured first and then paused and flushed,
     * which is what LastWave does before claiming the endpoint: while
     * AudioFlinger still owns the DAC, the usbdevfs claim fails and the
     * session falls back onto the shared mixer path.
     *
     * @return true when the failure is definitive for this format, so the
     *   caller can stop retrying until the next configure.
     */
    private fun startExclusiveIfWanted(format: Format): Boolean {
        exclusiveActive = false
        // The crossfade standby must never claim the endpoint: its configure
        // runs while the outgoing track still owns the DAC.
        if (!isAudible()) return false
        val session = exclusiveUsb ?: return false
        if (!exclusiveWanted() || !bitPerfect()) return false
        if (format.channelCount !in 1..2) {
            publishExclusiveUnavailable(
                "This track has ${format.channelCount} channels; the Android mixer stays in the path",
            )
            return false
        }
        // Nothing to try without a DAC that grants access. Pausing the
        // delegate first would cost a dropout for an attempt that cannot
        // even reach the endpoint.
        if (!session.canOpen()) {
            publishExclusiveUnavailable(null)
            return false
        }
        // LastWave's order: release the mixer's claim before opening usbdevfs.
        runCatching { delegate.pause() }
        runCatching { delegate.flush() }
        val started = runCatching {
            session.configure(format, bitDepthHint = bitDepthHint())
        }.getOrDefault(false)
        // A DAC that latched a different clock would get source-rate bytes fed
        // to the wrong rate. This sink has no resampler, so the honest answer
        // is the mixer fallback rather than a pitch-shifted stream.
        if (started && session.currentRateHz() != format.sampleRate) {
            val latched = session.currentRateHz()
            runCatching { session.reset() }
            publishExclusiveUnavailable(
                "DAC clock ${latched} Hz for a ${format.sampleRate} Hz track; no resampling in bit-perfect",
            )
            if (playing) runCatching { delegate.play() }
            return true
        }
        if (!started) {
            publishExclusiveUnavailable(session.lastFailureReason)
            if (playing) runCatching { delegate.play() }
            return !session.failureIsRetryable()
        }
        exclusiveActive = true
        exclusiveEnded = false
        if (isAudible()) {
            val bits = session.activeWireBits()
            val rate = session.currentRateHz()
            val wire = if (bits > 0) "$bits-bit" else "PCM"
            AudioOutputStatus.publishOutputExactness(
                exact = true,
                detail = "USB exclusive: $wire / $rate Hz direct to the DAC",
            )
            AudioOutputStatus.publishDsp(
                decoderOutputEncoding = inputPcmEncoding?.let { encodingLabel(mapFromPcmEncoding(it)) },
                dspFormat = "Float32 (bypassed)",
                dspAvailable = true,
                dspBypassed = true,
            )
            AudioOutputStatus.publishExclusiveUsb(
                active = true,
                detail = "$wire / $rate Hz via usbdevfs, AudioFlinger not in the path",
            )
        }
        Log.i(
            TAG,
            "exclusive USB active rate=${session.currentRateHz()}Hz wireBits=${session.activeWireBits()} " +
                "enc=$inputPcmEncoding ch=${format.channelCount} AudioTrack=idle",
        )
        return false
    }

    /** Leaves the userspace session and re-reports the AudioTrack path. */
    private fun leaveExclusiveUsb(resume: Boolean) {
        exclusiveActive = false
        exclusiveEnded = false
        runCatching { exclusiveUsb?.reset() }
        val input = inputPcmEncoding
        val output = targetOutputEncoding
        if (input != null && output != null) publishTelemetry(input, output)
        if (resume && playing) runCatching { delegate.play() }
    }

    private fun publishExclusiveUnavailable(reason: String?) {
        if (!isAudible()) return
        AudioOutputStatus.publishExclusiveUsb(
            active = false,
            detail = reason?.takeIf { it.isNotBlank() }
                ?: "USB exclusive unavailable; the Android mixer stays in the path",
        )
    }

    private enum class ExclusiveOutcome { CONSUMED, BACKPRESSURE, FALL_THROUGH }

    /**
     * Takes over mid-track when the switch is flipped while a track plays,
     * the way LastWave adopts its exclusive session. Only the audible sink
     * may claim the endpoint; the idle half of a handoff must stay on
     * AudioTrack. One attempt per configured track: a refused claim should
     * not re-prompt or re-open on every buffer.
     */
    private fun maybeAdoptExclusive() {
        if (!exclusiveWanted() || !bitPerfect()) {
            // A fresh ask starts a fresh attempt after an earlier refusal.
            exclusiveAdoptionSuppressed = false
            return
        }
        if (exclusiveActive || !isPrecisionActive) return
        if (exclusiveAdoptionSuppressed) return
        val session = exclusiveUsb ?: return
        if (!isAudible()) return
        // A live session belongs to the other sink; do not feed two clocks
        // into one isochronous queue.
        if (session.isStreamAlive()) return
        val format = activeFormat ?: return
        if (format.channelCount !in 1..2) return
        // A claim refused for a missing grant must be able to succeed once
        // the user accepts the system dialog, so retry on a slow clock
        // instead of latching the failure for the whole track. Without a
        // grant there is nothing to try, so leave the delegate alone.
        val now = SystemClock.elapsedRealtime()
        if (now - lastExclusiveAttemptMs < EXCLUSIVE_RETRY_MS) return
        lastExclusiveAttemptMs = now
        if (!session.canOpen()) return
        // The pre-toggle processed tail belongs to the AudioTrack path; drop
        // it rather than replaying it on a clock that just changed owner.
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        passthroughBuffer = null
        timestampedInputTimeUs = C.TIME_UNSET
        framesEmittedForInput = 0L
        if (startExclusiveIfWanted(format)) exclusiveAdoptionSuppressed = true
    }

    /**
     * One decoder buffer to the isochronous endpoint, untouched.
     *
     * The buffer is already what the wire carries — [inputPcmEncoding] is the
     * format the session was opened for — so no conversion, gain or DSP runs
     * here. "Queue full" is backpressure, not failure; a dead stream after a
     * seek is re-armed once before the AudioTrack fallback takes the buffer.
     */
    private fun tryExclusiveBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
    ): ExclusiveOutcome {
        val session = exclusiveUsb ?: return ExclusiveOutcome.FALL_THROUGH
        // The switch, the DAC or the permission can go away mid-track. Leave
        // the endpoint and hand this buffer to the delegate instead of writing
        // to a session the service has already torn down.
        if (!exclusiveWanted()) {
            leaveExclusiveUsb(resume = true)
            return ExclusiveOutcome.FALL_THROUGH
        }
        if (session.isPaused()) return ExclusiveOutcome.BACKPRESSURE
        val encoding = inputPcmEncoding ?: return ExclusiveOutcome.FALL_THROUGH
        if (session.write(buffer, presentationTimeUs, isFloatBuffer = encoding == PcmEncoding.PCM_FLOAT)) {
            exclusiveEnded = false
            return ExclusiveOutcome.CONSUMED
        }
        // Queue full: the DAC is still running. Returning false makes the
        // renderer retry and keep loading the network instead of stalling.
        if (session.isStreamAlive()) return ExclusiveOutcome.BACKPRESSURE
        // A seek discards in-flight URBs; the first write afterwards can fail
        // once, and tearing the session down there left the DAC silent.
        if (session.restartIfStopped()) return ExclusiveOutcome.BACKPRESSURE
        Log.w(
            TAG,
            "exclusive USB stopped; falling back to AudioTrack (${session.lastFailureReason})",
        )
        leaveExclusiveUsb(resume = true)
        return ExclusiveOutcome.FALL_THROUGH
    }

    override fun handleBuffer(
        inputBuffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (exclusiveActive) {
            when (tryExclusiveBuffer(inputBuffer, presentationTimeUs)) {
                ExclusiveOutcome.CONSUMED -> return true
                ExclusiveOutcome.BACKPRESSURE -> return false
                ExclusiveOutcome.FALL_THROUGH -> Unit
            }
        } else {
            maybeAdoptExclusive()
            if (exclusiveActive) {
                when (tryExclusiveBuffer(inputBuffer, presentationTimeUs)) {
                    ExclusiveOutcome.CONSUMED -> return true
                    ExclusiveOutcome.BACKPRESSURE -> return false
                    ExclusiveOutcome.FALL_THROUGH -> Unit
                }
            }
        }
        if (!isPrecisionActive) {
            return delegate.handleBuffer(inputBuffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // A pass-through buffer the delegate did not finish with last time.
        // The renderer passes the same buffer again; feeding it here first
        // leaves the bypass below an empty remainder rather than a second copy.
        passthroughBuffer?.let { pending ->
            val accepted = delegate.handleBuffer(
                pending,
                passthroughPresentationTimeUs,
                passthroughAccessUnitCount,
            )
            if (!accepted || pending.hasRemaining()) return false
            passthroughBuffer = null
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
        streaming = true

        // Bit-perfect is a per-buffer promise rather than a configure-time one:
        // switching it on must stop the very next buffer being altered, and
        // switching it off must not resume a limiter from where it left off.
        val skipDsp = bitPerfect()
        if (skipDsp != dspBypassLatched) {
            if (!skipDsp) dspChain.reset()
            publishTelemetry(inEncoding, outEncoding)
        }

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

        // 2. Output buffer is drained; process available input in blocks.
        //    A decoder buffer larger than the block capacity is split, and each
        //    sub-block carries its own timestamp: handing the delegate the same
        //    presentation time for every piece makes its expected-position check
        //    drift by one block each time, and past 200ms of accumulated drift it
        //    raises UnexpectedDiscontinuityException and resyncs the clock.
        // Nothing in the chain would alter a sample, and the delegate is already
        // configured for exactly this encoding: forward the decoder's buffer
        // instead of decoding to float and encoding it back to identical bytes.
        // On the 16-bit speaker path this is the whole per-buffer cost of the
        // app's audio processing.
        if (inEncoding == outEncoding && (skipDsp || dspChain.isBypassed())) {
            timestampedInputTimeUs = C.TIME_UNSET
            framesEmittedForInput = 0L
            if (!inputBuffer.hasRemaining()) return true
            val accepted = delegate.handleBuffer(inputBuffer, presentationTimeUs, encodedAccessUnitCount)
            if (!accepted || inputBuffer.hasRemaining()) {
                // Keep the buffer (not a copy) so isEnded/hasPendingData answer
                // for the bytes still in the delegate's hands.
                passthroughBuffer = inputBuffer
                passthroughPresentationTimeUs = presentationTimeUs
                passthroughAccessUnitCount = encodedAccessUnitCount
                return false
            }
            return true
        }

        if (presentationTimeUs != timestampedInputTimeUs) {
            timestampedInputTimeUs = presentationTimeUs
            framesEmittedForInput = 0L
        }
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
            if (!skipDsp) dspChain.process(audioBlock)

            // Encode processed samples to target PCM output buffer (Float32 or PCM16)
            outputByteBuffer.clear()
            PcmBoundary.encode(
                sourceBlock = audioBlock,
                encoding = outEncoding,
                outputBuffer = outputByteBuffer,
            )
            outputByteBuffer.flip()

            val blockTimeUs = advanceTimestamp(presentationTimeUs, framesEmittedForInput)
            if (blockTimeUs != C.TIME_UNSET && configuredSampleRate > 0) {
                processedEndUs = blockTimeUs + Util.sampleCountToDurationUs(decodedFrames.toLong(), configuredSampleRate)
            }
            // The access-unit count describes the whole decoder buffer, so it is
            // reported once, on the first piece of it.
            val blockAccessUnits = if (framesEmittedForInput == 0L) encodedAccessUnitCount else 0
            pendingPresentationTimeUs = blockTimeUs
            pendingAccessUnitCount = blockAccessUnits
            framesEmittedForInput += decodedFrames

            val consumed = delegate.handleBuffer(
                outputByteBuffer,
                blockTimeUs,
                blockAccessUnits,
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

    /** [presentationTimeUs] moved on by [frames], or left alone when the rate is unknown. */
    private fun advanceTimestamp(presentationTimeUs: Long, frames: Long): Long {
        if (frames <= 0L || configuredSampleRate <= 0 || presentationTimeUs == C.TIME_UNSET) {
            return presentationTimeUs
        }
        return presentationTimeUs + Util.sampleCountToDurationUs(frames, configuredSampleRate)
    }

    /**
     * Called by the renderer as the output side moves onto the next stream —
     * after the outgoing track's last buffer, before the incoming one's first.
     * That is the one moment the DSP chain can switch per-track state on the
     * right sample, so a gapless boundary is passed on; a skip, which flushed
     * first, is not.
     */
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (streaming) dspChain.onStreamBoundary()
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    /**
     * Passed straight through, noting on the way how far the DSP chain is
     * running ahead of it — see
     * [com.music.bitchord.playback.TransitionFilterProcessor.leadUs]. The
     * renderer polls this on every clock tick, so the lead stays current for
     * free.
     */
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (exclusiveActive) {
            dspChain.transition.leadUs = 0L
            // The USB clock, not AudioTrack's: no track exists on this path.
            return exclusiveUsb?.getCurrentPositionUs() ?: AudioSink.CURRENT_POSITION_NOT_SET
        }
        val position = super.getCurrentPositionUs(sourceEnded)
        val processed = processedEndUs
        if (position != AudioSink.CURRENT_POSITION_NOT_SET && processed != C.TIME_UNSET) {
            dspChain.transition.leadUs = (processed - position).coerceAtLeast(0L)
        }
        return position
    }

    override fun flush() {
        processedEndUs = C.TIME_UNSET
        dspChain.transition.leadUs = 0L
        streaming = false
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        audioBlock.clear()
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingAccessUnitCount = 0
        timestampedInputTimeUs = C.TIME_UNSET
        framesEmittedForInput = 0L
        passthroughBuffer = null
        passthroughPresentationTimeUs = C.TIME_UNSET
        passthroughAccessUnitCount = 0
        exclusiveEnded = false
        if (exclusiveActive && isAudible()) {
            // Drop the URBs already queued for the pre-seek position. The
            // delegate is already idle and flushed from configure.
            runCatching { exclusiveUsb?.flush() }
        }
        if (isPrecisionActive) {
            dspChain.flush()
        }
        delegate.flush()
    }

    override fun reset() {
        // Keep the claimed endpoint between items when the switch is still
        // on: closing it would put the DAC back behind snd-usb-audio and the
        // next configure would pay the Feature Unit re-probe again.
        if (exclusiveActive && exclusiveWanted()) {
            runCatching { exclusiveUsb?.prepareForNextItem() }
        } else if (isAudible()) {
            runCatching { exclusiveUsb?.reset() }
        }
        exclusiveActive = false
        exclusiveEnded = false
        processedEndUs = C.TIME_UNSET
        dspChain.transition.leadUs = 0L
        streaming = false
        processCounter = 0L
        outputByteBuffer.clear()
        outputByteBuffer.flip()
        audioBlock.clear()
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingAccessUnitCount = 0
        timestampedInputTimeUs = C.TIME_UNSET
        framesEmittedForInput = 0L
        passthroughBuffer = null
        passthroughPresentationTimeUs = C.TIME_UNSET
        passthroughAccessUnitCount = 0
        if (isPrecisionActive) {
            dspChain.reset()
        }
        isPrecisionActive = false
        inputPcmEncoding = null
        targetOutputEncoding = null
        activeFormat = null
        configuredSampleRate = 0
        if (isAudible()) {
            AudioOutputStatus.publishOutputExactness(exact = false, detail = null)
            AudioOutputStatus.publishExclusiveUsb(active = false, detail = null)
            AudioOutputStatus.publishDsp(
                decoderOutputEncoding = null,
                dspFormat = "Float32",
                dspAvailable = true,
            )
        }
        delegate.reset()
    }

    override fun handleDiscontinuity() {
        if (exclusiveActive) {
            // A segment boundary, not a seek: the USB clock keeps running.
            runCatching { exclusiveUsb?.handleDiscontinuity() }
            return
        }
        delegate.handleDiscontinuity()
    }

    override fun playToEndOfStream() {
        if (exclusiveActive) {
            // Everything the decoder produced is already queued as URBs.
            exclusiveEnded = true
            return
        }
        passthroughBuffer?.let { pending ->
            delegate.handleBuffer(pending, passthroughPresentationTimeUs, passthroughAccessUnitCount)
            if (!pending.hasRemaining()) passthroughBuffer = null
        }
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            delegate.handleBuffer(outputByteBuffer, pendingPresentationTimeUs, pendingAccessUnitCount)
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (exclusiveActive) {
            return exclusiveEnded
        }
        if (passthroughBuffer?.hasRemaining() == true) {
            return false
        }
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            return false
        }
        return delegate.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (exclusiveActive) {
            return !exclusiveEnded && exclusiveUsb?.hasPendingData() == true
        }
        if (passthroughBuffer?.hasRemaining() == true) {
            return true
        }
        if (isPrecisionActive && outputByteBuffer.hasRemaining()) {
            return true
        }
        return delegate.hasPendingData()
    }

    override fun play() {
        playing = true
        if (exclusiveActive) {
            runCatching { exclusiveUsb?.setPaused(false) }
            return
        }
        super.play()
    }

    override fun pause() {
        playing = false
        // Media3 pauses for STATE_BUFFERING as well as for the listener. An
        // exclusive pause flag would then refuse the post-seek pre-fill and
        // leave the DAC silent while the bar moved; LastWave learned that
        // the hard way and so leaves the isochronous stream running here.
        if (exclusiveActive) return
        super.pause()
    }

    override fun setVolume(volume: Float) {
        // usbdevfs never uses AudioTrack volume. A Feature Unit that changes
        // GET_CUR keeps PCM untouched; otherwise the session scales it in
        // software. The platform track must stay at unity so the two gains
        // never stack.
        if (isAudible()) runCatching { exclusiveUsb?.setVolume(volume) }
        super.setVolume(if (exclusiveActive) 1f else volume)
    }

    override fun release() {
        // The standby is released on every handoff; closing the shared
        // endpoint from there would cut the track that is still playing.
        if (isAudible()) runCatching { exclusiveUsb?.release() }
        exclusiveActive = false
        exclusiveEnded = false
        super.release()
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    /**
     * How well this sink can carry [format], which Media3 reads for more than a
     * yes/no: `MediaCodecAudioRenderer.getMediaFormat` asks about a PCM-float
     * format and requests float decoder output *only* when the answer is
     * [AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY].
     *
     * So answering SUPPORTED_DIRECTLY for float on a route that cannot open a
     * float track is not a harmless overstatement — it makes every decoder emit
     * float, including on the 16-bit speaker path. A 16-bit source then arrives
     * here as Float32 and is reported as converted-on-the-way-out, when in fact
     * the round trip through the float block recovers it exactly. The float
     * answer therefore tracks what [resolveTargetEncoding] will really do.
     */
    override fun getFormatSupport(format: Format): Int {
        // The userspace path writes the decoder's own bytes to the endpoint,
        // at the source rate, with no AudioTrack involved — the one claim
        // AudioTrack support cannot make. It needs stereo (or mono) and the
        // session itself validates the encoding before opening.
        if (exclusiveWanted() && bitPerfect() && shouldActivatePrecision(format) &&
            format.channelCount in 1..2
        ) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        if (shouldActivatePrecision(format)) {
            val pcm16Format = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build()
            val floatFormat = format.buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            val pcm16Playable =
                delegate.getFormatSupport(pcm16Format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            val floatIsNative = floatIsNative(floatFormat)

            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                return when {
                    floatIsNative -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                    pcm16Playable -> AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
                    else -> AudioSink.SINK_FORMAT_UNSUPPORTED
                }
            }
            if (floatIsNative || pcm16Playable) {
                // PrecisionAudioSink natively consumes every supported linear PCM input
                // encoding (Float32, PCM16, PCM24, PCM32) straight into its canonical
                // Float32 AudioBlock, without requiring Media3 to transcode first.
                return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            }
        }
        return delegate.getFormatSupport(format)
    }

    /**
     * Whether the delegate will open a genuine float AudioTrack for [floatFormat].
     *
     * [enableFloatOutput] is checked first because `getFormatSupport` alone cannot
     * answer it: with float output off the delegate rewrites the format to 16-bit
     * and reports SUPPORTED_WITH_TRANSCODING, which is a different claim than the
     * one being made here.
     */
    private fun floatIsNative(floatFormat: Format): Boolean =
        enableFloatOutput &&
            delegate.getFormatSupport(floatFormat) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    /**
     * The encoding handed to the delegate, which is only ever float or 16-bit.
     *
     * [OutputNegotiator] can name PCM_24BIT_PACKED when a route advertises packed
     * 24-bit, and that used to be passed straight through. It cannot be honoured:
     * [DefaultAudioSink] has no 24-bit AudioTrack path, so it quietly converted the
     * result again — to 16-bit whenever float output was off — leaving a needless
     * extra quantization in the middle and a route summary claiming 24 bits that
     * were never written. A preference for more than 16 bits is therefore read as
     * what it is, a request for float, and honoured only where float is real.
     */
    private fun resolveTargetEncoding(preferred: PcmEncoding?, floatFormat: Format): PcmEncoding {
        val wantsAbove16Bit = when (preferred) {
            PcmEncoding.PCM_16BIT -> return PcmEncoding.PCM_16BIT
            PcmEncoding.PCM_FLOAT, PcmEncoding.PCM_24BIT_PACKED, PcmEncoding.PCM_32BIT -> true
            null -> enableFloatOutput
        }
        return if (wantsAbove16Bit && floatIsNative(floatFormat)) {
            PcmEncoding.PCM_FLOAT
        } else {
            PcmEncoding.PCM_16BIT
        }
    }

    private fun shouldActivatePrecision(format: Format): Boolean {
        val sampleMimeType = format.sampleMimeType
        if (sampleMimeType != null && sampleMimeType != MimeTypes.AUDIO_RAW) return false
        if (!Util.isEncodingLinearPcm(format.pcmEncoding)) return false
        if (mapToPcmEncoding(format.pcmEncoding) == null) return false
        // Any channel layout the decoder produces, not just mono and stereo. A
        // Dolby (E-AC-3 JOC) stream decodes to 5.1, and capping this at two
        // channels was what silently dropped the whole DSP chain — equaliser
        // included — for every immersive track. Each stage handles an arbitrary
        // channel count already; the ones that are inherently stereo, like
        // spatial widening, bow out on their own.
        if (format.channelCount !in 1..MAX_CHANNELS) return false
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
     * Reports whether the decoder's samples survive the trip to AudioTrack.
     *
     * This is the *encoding* half of bit-exactness — whether anything is being
     * lost between [PcmBoundary] and the track. Whether the listener also has a
     * DSP stage switched on is a separate question, and the readout combines the
     * two; see `AudioPipelineDialog.bitExactVerdict`.
     *
     * The combinations that are genuinely lossless:
     *
     * - 16-bit source to a 16-bit track. [PcmBoundary] scales by 32768, a power
     *   of two, so the float round trip recovers every original integer.
     * - 24-bit source to a float track. Scaled by 8388608, likewise exact, and
     *   float32 has the 24 bits of significand to hold the result.
     * - A float source to a float track, which is a copy.
     *
     * Everything else is inexact, and there are two of those worth naming. A
     * hi-res source on a route that will not open a float track gets 16-bit,
     * because Media3 offers nothing between. And **32-bit integer PCM cannot be
     * delivered exactly at all** — float32 carries 24 bits of significand, so
     * eight bits go regardless of route, and no setting in this app can change
     * that while [DefaultAudioSink] is doing the writing.
     */
    private fun publishOutputExactness(source: PcmEncoding, target: PcmEncoding) {
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
        AudioOutputStatus.publishOutputExactness(exact = exact, detail = detail)
    }

    private fun publishTelemetry(inEncoding: PcmEncoding, outEncoding: PcmEncoding) {
        dspBypassLatched = bitPerfect()
        if (!isAudible()) return
        publishOutputExactness(inEncoding, outEncoding)
        // Any route report from here means the AudioTrack path is the one
        // playing; the exclusive session publishes its own state after this.
        if (!exclusiveActive) {
            AudioOutputStatus.publishExclusiveUsb(active = false, detail = null)
        }
        AudioOutputStatus.publishDsp(
            decoderOutputEncoding = encodingLabel(mapFromPcmEncoding(inEncoding)),
            dspFormat = "Float32",
            dspAvailable = true,
            dspBypassed = dspBypassLatched,
        )
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

        /** How often an unclaimed exclusive session is retried mid-track. */
        private const val EXCLUSIVE_RETRY_MS = 5_000L

        /**
         * Widest channel layout the precision path will take on. Eight covers
         * 7.1, which is the most any decoder here produces; the cap exists so a
         * nonsense channel count cannot size a buffer without bound.
         */
        const val MAX_CHANNELS: Int = 8

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
