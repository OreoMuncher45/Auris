package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.audio.PcmBoundary
import com.music.bitchord.playback.smart.MdxResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The neural karaoke stage: a Media3 processor that takes the lead vocal
 * out of the song on its way to the speaker.
 *
 * ## Where it sits
 *
 * It runs in ExoPlayer's own audio processor chain, after the app's float
 * DSP chain has had its say and before the speed control. That chain is
 * only consulted on the sixteen-bit branch of the sink — the float branch
 * gets the format converter and nothing else — so Studio plays at sixteen
 * bits while it is on. That is Media3's rule, not this app's.
 *
 * ## What it owes the sink
 *
 * A processor in that chain has one obligation: what comes out has to be
 * the same length as what went in. The sink counts the frames it has
 * submitted and the frames it has written, and the gap between the two is
 * what it believes the listener is hearing. A processor that hands back
 * more than it was given — or that hands back a window's worth every time
 * it is asked — makes the sink's arithmetic meaningless: the position runs
 * away from the music, the lyrics go with it, and the song stutters.
 *
 * So the frames are counted here, and they are counted properly. The
 * separator holds the song back while the network thinks about it, and what
 * comes out is the song from a window ago with the vocal taken out of it.
 * The sink does not need to know: it writes what it is given, and the
 * position it reports is the position of the music that is actually being
 * heard.
 *
 * ## The delay
 *
 * A processor that answers later than it is asked has to be asked again:
 * the audio comes in, and the answer for it is not ready yet, so the
 * processor keeps the song and gives nothing back until the network has
 * had its turn. The song is therefore heard a window behind the clock,
 * which is the price of asking a network what a voice is.
 *
 * Nothing is dropped and nothing is repeated. The listener hears the song
 * from a window ago, with the vocal taken out of it.
 */
@UnstableApi
class NeuralSingProcessor(
    /** The separator this processor drives. */
    private val separator: NeuralVocalSeparator,
    /** Which rate the song is played at. Read when the sink configures. */
    private val quality: () -> StudioQuality,
) : BaseAudioProcessor() {

    /**
     * Whether the separation runs at all.
     *
     * Off means the audio goes through untouched — which is what happens
     * before a model has been downloaded, and what happens the moment
     * Studio is turned off.
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * How much of the vocal to take out, 0..1.
     *
     * 0 leaves the voice where it is; 1 takes it out entirely. Written
     * from the UI thread and read on the audio thread, hence volatile.
     */
    @Volatile
    var level: Float = 0f

    /** The song's rate into the separator. */
    private var toModel: MdxResampler? = null

    /** The song's rate out of the separator. */
    private var fromModel: MdxResampler? = null

    /** When the track ended, so the tail can be given a bounded wait. */
    private var endOfStreamAt: Long = 0L

    /** Whether the end of the stream has been signalled to this processor. */
    private var streamEnded: Boolean = false

    /**
     * Whether the converter's own last frames have been handed over.
     *
     * A rate converter cannot know what follows the last sample it has, so
     * it keeps those few frames back until the stream ends. They are given
     * over with the last of the song, and the processor is not finished
     * until they are.
     */
    private var tailFlushed: Boolean = false

    /**
     * Sets the processor up for the format the sink is handing it.
     *
     * Nothing in here may throw: this runs on the thread that plays the
     * music, and an exception here is an exception that ends the app.
     * A processor that cannot be set up stands down instead — the song
     * plays as it is, at its own rate, and the reason is written down
     * where the log can be read.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        try {
            configureFor(inputAudioFormat)
        } catch (error: Throwable) {
            TrackLog.i("STUDIO", "Studio could not configure itself: ${error.message}")
            AudioProcessor.AudioFormat.NOT_SET
        }

    private fun configureFor(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Stereo, sixteen-bit only. The chain this processor lives in only
        // ever sees sixteen-bit audio, and a mono track has no vocal to
        // take out of it anyway.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        val rate = inputAudioFormat.sampleRate
        if (rate <= 0) return AudioProcessor.AudioFormat.NOT_SET

        val target = quality().outputRate(rate)
        if (target <= 0) return AudioProcessor.AudioFormat.NOT_SET

        val to = if (rate == NeuralVocalSeparator.MODEL_RATE) {
            null
        } else {
            MdxResampler.create(rate.toDouble(), NeuralVocalSeparator.MODEL_RATE.toDouble())
        }
        val from = if (target == NeuralVocalSeparator.MODEL_RATE) {
            null
        } else {
            MdxResampler.create(NeuralVocalSeparator.MODEL_RATE.toDouble(), target.toDouble())
        }
        // A rate pair the converter cannot do exactly is a reason not to
        // play at that rate at all: a stream that drifts is worse than a
        // song at its own rate with no separation.
        if (to != null && !to.supported || from != null && !from.supported) {
            to?.close()
            from?.close()
            TrackLog.i(
                "STUDIO",
                "No converter for ${rate} Hz -> ${target} Hz; the song plays as it is",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }

        toModel?.close()
        fromModel?.close()
        toModel = to
        fromModel = from
        // The rates the sink was built with: what the track arrived at, and
        // what it is being played at. The settings screen shows them, and
        // they are the only honest answer — the decoder's rate, not the
        // rate the file claims on its sleeve.
        separator.inputRateHz = rate
        separator.outputRateHz = target
        StudioDiagnostics.setRates(rate, target)
        return AudioProcessor.AudioFormat(target, C.ENCODING_PCM_16BIT, 2)
    }

    /**
     * Takes a buffer of song and gives back the song with the vocal out of
     * it — or nothing at all, when the answer is not ready yet.
     *
     * The input is always consumed. A processor that held on to it would
     * leave the sink spinning on the same bytes forever; the delay lives
     * in the separator's own buffers instead.
     */
    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.nativeOrder())
        val frames = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frames == 0) {
            // The sink is asking for more of what it has already given us.
            emitTail()
            return
        }

        val song = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            song[frame * 2] = inputBuffer.short * INV_SCALE
            song[frame * 2 + 1] = inputBuffer.short * INV_SCALE
        }

        // Into the rate the model reads.
        val atModel = toModel?.let { it.process(song, frames) } ?: song
        val modelFrames = atModel.size / 2

        val mixed: FloatArray
        if (separator.ready) {
            separator.cut = level
            separator.push(atModel, modelFrames)
            mixed = separator.pull(MAX_PULL_FRAMES)
        } else {
            // No model: the song plays as it is, at the rate the sink was
            // told about. It goes through the converter both ways so that
            // the rate the sink was built with is the rate it gets.
            mixed = atModel
        }

        // Out to the rate the song is played at.
        val out = fromModel?.let { it.process(mixed, mixed.size / 2) } ?: mixed
        write(out)
    }

    /**
     * The end of the track: the last of the song is still waiting to be
     * separated, and it is played before the track is over.
     */
    override fun onQueueEndOfStream() {
        streamEnded = true
        tailFlushed = false
        endOfStreamAt = SystemClock.elapsedRealtime()
    }

    /**
     * Hands over whatever there is to hand over.
     *
     * This is where the end of a track comes out. The sink asks for output
     * until the processor says it has nothing left, and it asks through
     * this method rather than by queueing more song: after the end of the
     * stream there is no more song, only the answer to the song that was.
     */
    override fun getOutput(): ByteBuffer {
        if (streamEnded) emitTail()
        return super.getOutput()
    }

    /**
     * Whether the processor has nothing left to say.
     *
     * The song is not over while the network still owes an answer, but the
     * wait is bounded: a separator that never answers must not be able to
     * hold the sink open for ever.
     */
    override fun isEnded(): Boolean {
        if (!super.isEnded()) return false
        if (!enabled || !separator.ready) return true
        if (separator.hasPendingWork) {
            return SystemClock.elapsedRealtime() - endOfStreamAt > TAIL_WAIT_MS
        }
        // The converter still owes its last few frames, and they are handed
        // over with the answer to the last window.
        return tailFlushed
    }

    override fun onFlush() {
        separator.reset()
        toModel?.reset()
        fromModel?.reset()
        streamEnded = false
        tailFlushed = false
    }

    override fun onReset() {
        separator.reset()
        // The sink is being torn down: the converters go with it, and the
        // next configure makes new ones.
        toModel?.close()
        fromModel?.close()
        toModel = null
        fromModel = null
        streamEnded = false
        tailFlushed = false
    }

    /**
     * Gives back whatever the separator has ready, with no new song to
     * work on.
     *
     * This is how the last of a track gets out: the sink asks for output
     * after the input has ended, and the answer is whatever the network
     * has finished by then. The converter's own last frames come out with
     * it — a rate converter cannot know what comes after the last sample
     * it has, so it waits until the stream ends before it says so.
     */
    private fun emitTail() {
        if (!streamEnded) {
            // The sink wants more and the separator may have something ready
            // that was not asked for in time.
            if (separator.ready) {
                val out = separator.pull(MAX_PULL_FRAMES)
                write(fromModel?.let { it.process(out, out.size / 2) } ?: out)
            }
            return
        }
        if (!enabled || !separator.ready) {
            // Studio was turned off with a window still in the machine:
            // it is not going to be played, so it is let go.
            separator.reset()
            tailFlushed = true
            return
        }
        if (separator.hasPendingWork) {
            // The last window is still in the machine. It is played when it
            // arrives, and until then there is nothing to hand over.
            val tail = separator.finish(MAX_PULL_FRAMES)
            if (tail.isNotEmpty()) {
                write(fromModel?.let { it.process(tail, tail.size / 2) } ?: tail)
            }
            return
        }
        if (!tailFlushed) {
            tailFlushed = true
            val last = fromModel?.flush() ?: FloatArray(0)
            write(last)
        }
    }

    /** Writes separated song out in the format the sink asked for. */
    private fun write(pcm: FloatArray) {
        val frames = pcm.size / 2
        val output = replaceOutputBuffer(frames * BYTES_PER_FRAME)
        output.order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            output.putShort(PcmBoundary.clamp16FromFloat(pcm[frame * 2]))
            output.putShort(PcmBoundary.clamp16FromFloat(pcm[frame * 2 + 1]))
        }
        output.flip()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
        const val INV_SCALE = 1.0f / 32768.0f

        /** How long the end of a track waits for its last answer. */
        const val TAIL_WAIT_MS = 4_000L

        /** Frames taken out of the separator at once: a third of a second. */
        const val MAX_PULL_FRAMES = 16_384
    }
}
