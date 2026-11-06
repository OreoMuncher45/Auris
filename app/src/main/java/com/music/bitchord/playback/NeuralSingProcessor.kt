package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.audio.PcmBoundary
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

    /** When the track ended, so the tail can be given a bounded wait. */
    private var endOfStreamAt: Long = 0L

    /** Whether the end of the stream has been signalled to this processor. */
    private var streamEnded: Boolean = false

    /** The buffer the tail is written into, kept so the audio thread does not allocate. */
    private val tailBuffer = FloatArray(MAX_TAIL_FRAMES * 2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Stereo, sixteen-bit only. The chain this processor lives in only
        // ever sees sixteen-bit audio, and a mono track has no vocal to
        // take out of it anyway.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.sampleRate > 0) {
            separator.sampleRate = inputAudioFormat.sampleRate
        }
        return inputAudioFormat
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
        val frames = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frames == 0) {
            drainTail()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())

        if (!enabled || !separator.ready) {
            val passthrough = replaceOutputBuffer(frames * BYTES_PER_FRAME)
            passthrough.order(ByteOrder.nativeOrder())
            passthrough.put(inputBuffer)
            passthrough.flip()
            return
        }

        val song = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            song[frame * 2] = inputBuffer.short * INV_SCALE
            song[frame * 2 + 1] = inputBuffer.short * INV_SCALE
        }

        val separated = FloatArray(frames * 2)
        val produced = separator.exchange(song, frames, separated, frames).produced
        if (produced <= 0) return

        val output = replaceOutputBuffer(produced * BYTES_PER_FRAME)
        output.order(ByteOrder.nativeOrder())
        for (frame in 0 until produced) {
            output.putShort(PcmBoundary.clamp16FromFloat(separated[frame * 2]))
            output.putShort(PcmBoundary.clamp16FromFloat(separated[frame * 2 + 1]))
        }
        output.flip()
    }

    /**
     * The end of the track: the last of the song is still waiting to be
     * separated, and it is played before the track is over.
     */
    override fun onQueueEndOfStream() {
        streamEnded = true
        endOfStreamAt = SystemClock.elapsedRealtime()
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
        if (!separator.hasPendingWork) return true
        return SystemClock.elapsedRealtime() - endOfStreamAt > TAIL_WAIT_MS
    }

    override fun onFlush() {
        separator.reset()
        streamEnded = false
    }

    override fun onReset() {
        separator.reset()
        streamEnded = false
    }

    /**
     * Gives back whatever the separator has ready, with no new song to
     * work on.
     *
     * This is how the last of a track gets out: the sink asks for output
     * after the input has ended, and the answer is whatever the network
     * has finished by then.
     */
    private fun drainTail() {
        if (!streamEnded) return
        if (!enabled || !separator.ready) {
            // Studio was turned off with a window still in the machine:
            // it is not going to be played, so it is let go.
            separator.reset()
            return
        }
        val produced = separator.finish(tailBuffer, MAX_TAIL_FRAMES)
        if (produced <= 0) return
        val output = replaceOutputBuffer(produced * BYTES_PER_FRAME)
        output.order(ByteOrder.nativeOrder())
        for (frame in 0 until produced) {
            output.putShort(PcmBoundary.clamp16FromFloat(tailBuffer[frame * 2]))
            output.putShort(PcmBoundary.clamp16FromFloat(tailBuffer[frame * 2 + 1]))
        }
        output.flip()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
        const val INV_SCALE = 1.0f / 32768.0f

        /** How long the end of a track waits for its last answer. */
        const val TAIL_WAIT_MS = 4_000L

        /** Frames the tail drain can write at once: two windows' worth. */
        const val MAX_TAIL_FRAMES = 2 * NeuralVocalSeparator.MODEL_CHUNK_FRAMES
    }
}
