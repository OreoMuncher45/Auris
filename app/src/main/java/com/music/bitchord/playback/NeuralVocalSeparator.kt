package com.music.bitchord.playback

import com.music.bitchord.playback.smart.MdxStft
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The karaoke separator: a neural network that takes the lead vocal out of
 * a song while it plays.
 *
 * The three older Sing methods are filters. They look for the vocal in the
 * middle of the stereo image and take it out, which works until the bass
 * and the snare are sitting in the middle too -- and then they take those
 * as well. This one is taught: a network that has heard thousands of
 * separated songs knows what a voice is, and leaves the instruments where
 * they are.
 *
 * ## The delay
 *
 * The network is given a window of audio and answers with a window of
 * audio. It cannot answer about a moment it has not yet heard, so the
 * answer for the music at time *t* is only ready once the music a little
 * after *t* has arrived. Everything here is arranged around that: the
 * separator holds the song back by one window and plays the answer when
 * it is ready.
 *
 * Nothing is dropped and nothing is repeated -- except when the phone
 * cannot keep up, which is the one case where something has to give. See
 * [droppedFrames].
 *
 * ## The rate
 *
 * Everything inside the separator is at the model's own rate, 44.1 kHz: the
 * network was trained there and reads a spectrogram whose bins mean
 * something only at that rate. The conversion in and out is the caller's
 * business -- see [NeuralSingProcessor] -- because only the caller knows
 * whether the song is going to be played at the model's rate or at its own.
 *
 * ## The thread
 *
 * The network is far too slow to run on the audio thread -- a window of
 * audio takes the better part of a second on a slow machine -- so it runs
 * on its own thread while the audio thread keeps feeding it and taking
 * answers away. Two windows are in flight at most, which is what keeps the
 * answer close behind the music without letting the queue grow without
 * bound.
 *
 * One separator belongs to one player. Two players can be playing at once
 * during a crossfade, and their audio must not end up in the same buffers;
 * they share the model itself, which is safe to run from both.
 */
class NeuralVocalSeparator(
    /** The model this separator runs on. Shared between players. */
    private val model: VocalModel,
) {

    /**
     * How much of the vocal to take out, 0..1.
     *
     * Read on the audio thread when the answer is mixed, written from the
     * UI thread; volatile for the same reason the other processors use it.
     */
    @Volatile
    var cut: Float = 0f

    /** Whether the model is open and the separator can do anything. */
    val ready: Boolean
        get() = model.ready

    /**
     * How much audio the separator had to throw away because the network
     * could not keep up with the song.
     *
     * Zero is the only good answer. Anything else means the phone is slower
     * than the model at this window size, and the song has a hole in it.
     */
    @Volatile
    var droppedFrames: Long = 0L
        private set

    /** How long the last window took, in milliseconds. */
    @Volatile
    var lastWindowMs: Long = 0L
        private set

    /** How many windows have been separated since the track started. */
    @Volatile
    var windowsSeparated: Long = 0L
        private set

    /**
     * How many windows the model could not answer for.
     *
     * A failed window is played dry, so the song keeps going and the voice
     * comes back for that stretch. Zero is the only good answer, and a
     * number that keeps climbing while "windows separated" climbs too is
     * the model failing rather than the phone being slow.
     */
    @Volatile
    var windowsFailed: Long = 0L
        private set

    /** Why the last window failed, if one did. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * How much audio has gone in, and how much has come out, in model-rate
     * frames.
     *
     * The two should be the same number: the separator holds the song back
     * while the network thinks, but it never loses any of it. A difference
     * between them is the sign of a hole in the song.
     */
    @Volatile
    var framesIn: Long = 0L
        private set

    @Volatile
    var framesOut: Long = 0L
        private set

    /**
     * The rate the track arrived at, and the rate it leaves at.
     *
     * Written by the processor when the sink configures it, read by the
     * settings screen to say what the song is being played at.
     */
    @Volatile
    var inputRateHz: Int = 0

    @Volatile
    var outputRateHz: Int = 0

    private val lock = Any()

    /** Model-rate audio waiting to be made into a window. */
    private var pending = FloatArray(0)
    private var pendingFrames = 0

    /** Where the pending audio starts in the stream, in model-rate frames. */
    private var pendingStart = 0L

    /** The song's length so far, in model-rate frames. */
    private var streamFrames = 0L

    /** Where the next frame to be played comes from, in stream frames. */
    private var outputFrame = 0L

    /** Separated audio waiting to be played, model-rate interleaved stereo. */
    private var readyPcm = FloatArray(0)
    private var readyFrames = 0

    /** The windows handed to the worker, oldest first. */
    private val inFlight = ArrayDeque<Window>()

    /** The windows the worker has not picked up yet. */
    private val workQueue = LinkedBlockingQueue<Window>()

    private val worker = Thread({ drainLoop() }, "SingSeparator")

    @Volatile
    private var running = true

    /**
     * Whether the separator is answering with separated audio yet.
     *
     * False until the first window has been through the network: until then
     * the song plays as it is, because there is nothing better to play and
     * silence would be worse.
     */
    @Volatile
    var separatedYet: Boolean = false
        private set

    private class Window(
        /** The song itself, interleaved stereo at the model's rate. */
        val dry: FloatArray,
        /** Where the window starts in the stream, in model-rate frames. */
        val startFrame: Long,
        /** The vocal the network found, in the same shape as [dry]. */
        @Volatile var stem: FloatArray? = null,
        /**
         * Frames of this window that are song rather than padding.
         *
         * The last window of a track is short: the model wants a whole
         * window, so the rest is filled with silence, and the silence is
         * not played.
         */
        val realFrames: Int = dry.size / 2,
    )

    init {
        worker.isDaemon = true
        worker.start()
    }

    /**
     * Puts song in.
     *
     * [input] holds interleaved stereo at [MODEL_RATE] and [frames] says how
     * many frames are in it. The audio is buffered until a whole window has
     * arrived, and the window goes to the network.
     */
    fun push(input: FloatArray, frames: Int) {
        if (!ready || frames <= 0) return
        synchronized(lock) {
            framesIn += frames
            appendPending(input, frames)
            submitWindows()
            collect()
        }
    }

    /**
     * Takes the separated song out.
     *
     * Returns however much is ready, up to [maxFrames] frames of interleaved
     * stereo at the model's rate. An empty array means the network has not
     * answered yet.
     */
    fun pull(maxFrames: Int): FloatArray = take(maxFrames)

    /**
     * The end of the track.
     *
     * The last of the song is still waiting to be made into a window; this
     * hands it over, padded out with silence, so the last second of a song
     * is separated rather than dropped.
     */
    fun finish(maxFrames: Int): FloatArray {
        if (!ready || maxFrames <= 0) return FloatArray(0)
        synchronized(lock) {
            submitWindows(final = true)
            collect()
            return take(maxFrames)
        }
    }

    /**
     * Whether anything is still waiting to come out.
     *
     * The end of a track is not the end of the separation: the last window
     * is still in the worker, and the song is not over until it has been
     * played.
     */
    val hasPendingWork: Boolean
        get() = synchronized(lock) {
            inFlight.isNotEmpty() || pendingFrames > 0 || readyFrames > 0
        }

    /** Empties every buffer. Used on a seek, and when the method changes. */
    fun reset() {
        synchronized(lock) {
            pendingFrames = 0
            pendingStart = 0
            streamFrames = 0
            outputFrame = 0
            readyFrames = 0
            separatedYet = false
            droppedFrames = 0
            windowsSeparated = 0
            windowsFailed = 0
            lastFailure = null
            framesIn = 0
            framesOut = 0
            inFlight.clear()
            workQueue.clear()
        }
    }

    /** Stops the worker. The model itself belongs to [NeuralModel]. */
    fun release() {
        running = false
        worker.interrupt()
        reset()
    }

    // ---- the buffers ----------------------------------------------------

    private fun appendPending(input: FloatArray, frames: Int) {
        val needed = (pendingFrames + frames) * 2
        if (needed > pending.size) {
            val grown = FloatArray(maxOf(pending.size * 2, needed))
            System.arraycopy(pending, 0, grown, 0, pendingFrames * 2)
            pending = grown
        }
        System.arraycopy(input, 0, pending, pendingFrames * 2, frames * 2)
        pendingFrames += frames
        streamFrames += frames

        // A machine that cannot separate as fast as the song arrives must not
        // grow a queue until the process dies. What is dropped when the bound
        // is reached is the oldest audio still waiting for its turn -- a hole
        // in the song rather than a stall, and a stall would be worse.
        val cap = (MAX_IN_FLIGHT + 2) * WINDOW_FRAMES
        if (pendingFrames > cap) {
            val drop = pendingFrames - cap
            System.arraycopy(pending, drop * 2, pending, 0, (pendingFrames - drop) * 2)
            pendingFrames -= drop
            pendingStart += drop
            droppedFrames += drop
        }
    }

    /**
     * Hands whole windows to the worker, as many as there is room for.
     *
     * [final] is the end of the track: whatever is left in the buffer is
     * padded out to a whole window and handed over, so the last second of a
     * song is separated rather than dropped.
     */
    private fun submitWindows(final: Boolean = false) {
        while (pendingFrames >= WINDOW_FRAMES && inFlight.size < MAX_IN_FLIGHT) {
            val dry = FloatArray(WINDOW_FRAMES * 2)
            System.arraycopy(pending, 0, dry, 0, dry.size)
            System.arraycopy(pending, WINDOW_FRAMES * 2, pending, 0, (pendingFrames - WINDOW_FRAMES) * 2)
            pendingFrames -= WINDOW_FRAMES
            pendingStart += WINDOW_FRAMES
            val window = Window(dry = dry, startFrame = pendingStart - WINDOW_FRAMES)
            inFlight.addLast(window)
            workQueue.offer(window)
        }
        if (!final || pendingFrames <= 0 || inFlight.size >= MAX_IN_FLIGHT) return
        // The last window: the song that is left, and silence for the rest.
        val dry = FloatArray(WINDOW_FRAMES * 2)
        System.arraycopy(pending, 0, dry, 0, pendingFrames * 2)
        val window = Window(dry = dry, startFrame = pendingStart, realFrames = pendingFrames)
        pendingFrames = 0
        pendingStart += 0
        inFlight.addLast(window)
        workQueue.offer(window)
    }

    /**
     * Mixes the answers that have arrived into the ready buffer.
     *
     * The vocal is taken out by subtraction: the mix minus the vocal is the
     * instrumental, and the slider says how much of that subtraction to
     * apply, so a half-open slider is half a vocal and a closed one is the
     * song as it was.
     */
    private fun collect() {
        val amount = cut.coerceIn(0f, 1f)
        while (inFlight.isNotEmpty()) {
            val window = inFlight.first()
            val stem = window.stem ?: break
            val windowEnd = window.startFrame + window.realFrames
            // The song that has already been played dry is not played again:
            // the separated answer picks up exactly where the dry one left
            // off, so the two meet without a gap and without a repeat.
            val start = maxOf(window.startFrame, outputFrame)
            if (start >= windowEnd) {
                inFlight.removeFirst()
                continue
            }
            val count = (windowEnd - start).toInt()
            appendReady(window, stem, amount, count, (start - window.startFrame).toInt())
            outputFrame = windowEnd
            inFlight.removeFirst()
        }
    }

    private fun appendReady(
        window: Window,
        stem: FloatArray,
        amount: Float,
        count: Int,
        from: Int,
    ) {
        val needed = (readyFrames + count) * 2
        if (needed > readyPcm.size) {
            val grown = FloatArray(maxOf(readyPcm.size * 2, needed))
            System.arraycopy(readyPcm, 0, grown, 0, readyFrames * 2)
            readyPcm = grown
        }
        val base = readyFrames * 2
        val source = from * 2
        for (index in 0 until count) {
            val at = source + index * 2
            val target = base + index * 2
            readyPcm[target] = window.dry[at] - amount * stem[at]
            readyPcm[target + 1] = window.dry[at + 1] - amount * stem[at + 1]
        }
        readyFrames += count
        separatedYet = true
    }

    /**
     * Gives out the song as it is, while the network is still thinking.
     *
     * Only the first stretch of a track is played this way: the point at
     * which the network answers, the separated song takes over, and the two
     * meet without a gap because the separated song starts exactly where the
     * dry one stopped.
     */
    private fun giveDry(maxFrames: Int) {
        if (separatedYet) return
        var remaining = minOf(maxFrames.toLong(), streamFrames - outputFrame)
        if (remaining <= 0) return
        // The song that has been waiting for its window comes first, and the
        // song that has not been waiting long enough yet comes after it: the
        // stream is played in order either way.
        for (window in inFlight) {
            if (remaining <= 0) break
            val windowEnd = window.startFrame + window.realFrames
            if (outputFrame >= windowEnd) continue
            val count = minOf(remaining, windowEnd - outputFrame).toInt()
            appendRaw(window.dry, ((outputFrame - window.startFrame) * 2).toInt(), count)
            outputFrame += count
            remaining -= count
        }
        if (remaining > 0 && outputFrame >= pendingStart) {
            val count = minOf(remaining, streamFrames - outputFrame).toInt()
            if (count > 0) {
                appendRaw(pending, ((outputFrame - pendingStart) * 2).toInt(), count)
                outputFrame += count
            }
        }
    }

    /** Appends song as it is to the ready buffer. */
    private fun appendRaw(source: FloatArray, from: Int, count: Int) {
        val needed = (readyFrames + count) * 2
        if (needed > readyPcm.size) {
            val grown = FloatArray(maxOf(readyPcm.size * 2, needed))
            System.arraycopy(readyPcm, 0, grown, 0, readyFrames * 2)
            readyPcm = grown
        }
        System.arraycopy(source, from, readyPcm, readyFrames * 2, count * 2)
        readyFrames += count
    }

    /** Hands over whatever is ready, up to [maxFrames]. */
    private fun take(maxFrames: Int): FloatArray {
        if (maxFrames <= 0) return FloatArray(0)
        synchronized(lock) {
            if (readyFrames == 0) {
                // The network has not answered yet and the sink wants audio
                // now. The song plays as it is until it does: the
                // alternative is a second of silence at the start of every
                // track, which is worse than a second of unseparated song.
                giveDry(maxFrames)
            }
            if (readyFrames == 0) return FloatArray(0)
            val count = minOf(readyFrames, maxFrames)
            val out = FloatArray(count * 2)
            System.arraycopy(readyPcm, 0, out, 0, count * 2)
            val left = (readyFrames - count) * 2
            System.arraycopy(readyPcm, count * 2, readyPcm, 0, left)
            readyFrames -= count
            framesOut += count
            return out
        }
    }

    // ---- the worker ----------------------------------------------------

    private fun drainLoop() {
        while (running) {
            val window = try {
                // A wait rather than a poll: with nothing to do the thread
                // sleeps, and a paused phone stays cool.
                workQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                continue
            }
            val started = System.nanoTime()
            val stem = model.separate(window.dry, WINDOW_FRAMES)
            lastWindowMs = (System.nanoTime() - started) / 1_000_000
            windowsSeparated++
            // A window the network could not answer for is played dry rather
            // than not played at all: the song keeps going and the vocal
            // comes back for that stretch. The failure is counted and its
            // reason kept, because a separator that fails quietly is a
            // separator that looks like it is working.
            if (stem == null) {
                windowsFailed++
                lastFailure = model.lastError ?: "the model could not answer"
            }
            window.stem = stem ?: FloatArray(window.dry.size)
        }
    }

    companion object {
        /** The rate the network was trained on. */
        const val MODEL_RATE = 44_100

        /**
         * Frames of audio in one window.
         *
         * 32 frames of 1024 samples is 0.74 s. The network is fully
         * convolutional in time, so a shorter window separates almost as well
         * as a long one -- measured against a reference mixture, 0.9969 at 32
         * frames against 0.9977 at 64 -- and costs half as much, which is
         * what decides whether the phone can keep up with the song at all.
         */
        const val WINDOW_FRAMES = MdxStft.FRAMES * MdxStft.HOP

        /** Windows the worker may be holding at once. */
        private const val MAX_IN_FLIGHT = 2
    }
}
