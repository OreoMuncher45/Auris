package com.music.bitchord.playback

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.playback.smart.MdxStft
import com.music.bitchord.playback.smart.MelSpectrogram
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * Nothing is dropped and nothing is repeated. The listener hears the song
 * from a window ago, with the vocal taken out of it.
 *
 * ## The rate
 *
 * The network was trained on 44.1 kHz. A track at another rate is converted
 * in and the answer converted back, so the stem always lines up with the
 * music it was taken from. The conversion is the native windowed-sinc
 * resampler the rest of the analyser uses.
 *
 * ## The thread
 *
 * The network is far too slow to run on the audio thread -- a window of
 * audio takes a second of work on a slow machine -- so it runs on its own
 * thread while the audio thread keeps feeding it and taking answers away.
 * Two windows are in flight at most, which is what keeps the answer close
 * behind the music without letting the queue grow without bound.
 */
class NeuralVocalSeparator(
    /** Where the model is, or null when none has been downloaded. */
    private val modelPath: () -> String?,
) {

    /** What one exchange between the audio thread and the separator did. */
    data class Exchange(
        /** Frames taken from the caller. */
        val consumed: Int,
        /** Frames written back. */
        val produced: Int,
    )

    /**
     * How much of the vocal to take out, 0..1.
     *
     * Read on the audio thread when the answer is mixed, written from the
     * UI thread; volatile for the same reason the other processors use it.
     */
    @Volatile
    var cut: Float = 0f

    /** The rate the audio arrives at. Set by the sink when it configures. */
    @Volatile
    var sampleRate: Int = MODEL_RATE

    /** Whether a model is loaded and the separator can do anything. */
    @Volatile
    var ready: Boolean = false
        private set

    /** Set when the model could not be opened, for the log and the UI. */
    @Volatile
    var failure: String? = null
        private set

    /** Frames of song held back from the listener. */
    val latencyFrames: Int
        get() = sourceChunkFrames()

    private val lock = Any()
    private var session: OrtSession? = null
    private var sessionPath: String? = null

    /** Source-rate frames waiting to be turned into a window of model audio. */
    private var pending = FloatArray(0)
    private var pendingFrames = 0

    /** Windows handed to the worker, oldest first. */
    private val inFlight = ArrayDeque<Window>()

    /** The windows the worker has not picked up yet. */
    private val workQueue = LinkedBlockingQueue<Window>()

    private val worker = Thread({ drainLoop() }, "SingSeparator")

    @Volatile
    private var running = true

    private class Window(
        /** The song itself, at the source rate, interleaved stereo. */
        val dry: FloatArray,
        /** The same song at the model's rate, interleaved stereo. */
        val modelRate: FloatArray,
        /** The vocal the network found, at the source rate, interleaved. */
        @Volatile var stem: FloatArray? = null,
        /** Frames of this window already written out. */
        var written: Int = 0,
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
     * Opens the model, if there is one.
     *
     * Cheap to call repeatedly: it only does work when the path has changed.
     * Not called from the audio thread -- opening a thirty-megabyte model
     * takes long enough to be heard as a hitch.
     */
    fun ensureSession() {
        val path = modelPath() ?: run {
            releaseSession()
            ready = false
            return
        }
        synchronized(lock) {
            if (session != null && sessionPath == path) {
                ready = true
                return
            }
            releaseSession()
            failure = null
            val file = File(path)
            if (!file.exists()) {
                ready = false
                return
            }
            runCatching {
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
            }.onSuccess {
                session = it
                sessionPath = path
                ready = true
            }.onFailure {
                failure = it.message ?: it.javaClass.simpleName
                ready = false
            }
        }
    }

    private fun releaseSession() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionPath = null
        }
    }

    /**
     * Takes song in and gives the separated song back.
     *
     * [input] holds interleaved stereo at [sampleRate]; [inputFrames] says
     * how many frames are in it. [output] receives interleaved stereo at the
     * same rate, and [outputCapacity] says how many frames fit.
     *
     * The two counts are independent on purpose. The separator takes what it
     * has room for and gives back what it has ready, and the caller is
     * expected to keep offering the rest and to keep taking what comes.
     */
    fun exchange(
        input: FloatArray,
        inputFrames: Int,
        output: FloatArray,
        outputCapacity: Int,
    ): Exchange {
        if (!ready || inputFrames <= 0 || outputCapacity <= 0) return Exchange(0, 0)

        synchronized(lock) {
            accept(input, inputFrames)
            submitWindows()
            val produced = drain(output, outputCapacity)
            return Exchange(inputFrames, produced)
        }
    }

    /**
     * Puts the caller's audio into the window buffer.
     *
     * The buffer is bounded: a machine that cannot separate as fast as the
     * song arrives must not grow a queue until the process dies. What is
     * dropped when the bound is reached is the oldest audio still waiting
     * for its turn -- a gap in the output rather than a stall, and a stall
     * would be worse than a gap.
     */
    private fun accept(input: FloatArray, inputFrames: Int) {
        val needed = (pendingFrames + inputFrames) * 2
        if (needed > pending.size) {
            val grown = FloatArray(maxOf(pending.size * 2, needed))
            System.arraycopy(pending, 0, grown, 0, pendingFrames * 2)
            pending = grown
        }
        System.arraycopy(input, 0, pending, pendingFrames * 2, inputFrames * 2)
        pendingFrames += inputFrames

        val cap = (MAX_IN_FLIGHT + 2) * sourceChunkFrames()
        if (pendingFrames > cap) {
            val drop = pendingFrames - cap
            System.arraycopy(pending, drop * 2, pending, 0, (pendingFrames - drop) * 2)
            pendingFrames -= drop
        }
    }

    /**
     * Hands whole windows to the worker, as many as there is room for.
     *
     * A window that cannot be converted to the model's rate is left in the
     * buffer rather than thrown away: the conversion failing is a fault in
     * the front end, not a reason to lose the song.
     *
     * [final] is the end of the track: whatever is left in the buffer is
     * padded out to a whole window and handed over, so the last second of
     * a song is separated rather than dropped.
     */
    private fun submitWindows(final: Boolean = false) {
        val chunk = sourceChunkFrames()
        while (pendingFrames >= chunk && inFlight.size < MAX_IN_FLIGHT) {
            val dry = FloatArray(chunk * 2)
            System.arraycopy(pending, 0, dry, 0, chunk * 2)
            val modelAudio = toModelRate(dry, chunk) ?: return
            System.arraycopy(pending, chunk * 2, pending, 0, (pendingFrames - chunk) * 2)
            pendingFrames -= chunk
            val window = Window(dry = dry, modelRate = modelAudio)
            inFlight.addLast(window)
            workQueue.offer(window)
        }
        if (!final || pendingFrames <= 0 || inFlight.size >= MAX_IN_FLIGHT) return
        val chunkFrames = sourceChunkFrames()
        val dry = FloatArray(chunkFrames * 2)
        System.arraycopy(pending, 0, dry, 0, pendingFrames * 2)
        val modelAudio = toModelRate(dry, chunkFrames) ?: return
        val window = Window(dry = dry, modelRate = modelAudio, realFrames = pendingFrames)
        inFlight.addLast(window)
        workQueue.offer(window)
        pendingFrames = 0
    }

    /**
     * Whether anything is still waiting to come out.
     *
     * The end of a track is not the end of the separation: the last window
     * is still in the worker, and the song is not over until it has been
     * played.
     */
    val hasPendingWork: Boolean
        get() = synchronized(lock) { inFlight.isNotEmpty() || pendingFrames > 0 }

    /**
     * Writes out the answers that are ready, oldest first.
     *
     * The vocal is taken out by subtraction: the mix minus the vocal is the
     * instrumental, and the slider says how much of that subtraction to
     * apply, so a half-open slider is half a vocal and a closed one is the
     * song as it was.
     */
    private fun drain(output: FloatArray, outputCapacity: Int): Int {
        var produced = 0
        val amount = cut.coerceIn(0f, 1f)
        while (inFlight.isNotEmpty() && produced < outputCapacity) {
            val window = inFlight.first()
            val stem = window.stem ?: break
            val count = minOf(window.realFrames - window.written, outputCapacity - produced)
            if (count <= 0) break
            for (frame in 0 until count) {
                val source = (window.written + frame) * 2
                val target = (produced + frame) * 2
                output[target] = window.dry[source] - amount * stem[source]
                output[target + 1] = window.dry[source + 1] - amount * stem[source + 1]
            }
            produced += count
            window.written += count
            if (window.written >= window.realFrames) inFlight.removeFirst()
        }
        return produced
    }

    /**
     * The end of the track.
     *
     * The last of the song is still sitting in the window buffer, waiting
     * for a whole window to be made of it; this hands it over and gives
     * back whatever has been separated by now.
     */
    fun finish(output: FloatArray, outputCapacity: Int): Int {
        if (!ready || outputCapacity <= 0) return 0
        synchronized(lock) {
            submitWindows(final = true)
            return drain(output, outputCapacity)
        }
    }

    /** Empties every buffer. Used on a seek, and when the method changes. */
    fun reset() {
        synchronized(lock) {
            pendingFrames = 0
            inFlight.clear()
            workQueue.clear()
        }
    }

    /** Releases the model and stops the worker. */
    fun release() {
        running = false
        worker.interrupt()
        releaseSession()
    }

    // ---- the worker ----------------------------------------------------

    private fun drainLoop() {
        while (running) {
            val window = try {
                workQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                continue
            }
            val stem = separate(window.modelRate)
            // A window the network could not answer for is played dry rather
            // than not played at all: the song keeps going and the vocal
            // comes back for that stretch.
            window.stem = stem ?: FloatArray(window.dry.size)
        }
    }

    /**
     * Runs the network over one window and returns the vocal it found.
     *
     * The answer comes back at the model's rate; it is converted to the
     * source rate here so the caller can mix it against the song it came
     * from without thinking about rates.
     */
    private fun separate(modelAudio: FloatArray): FloatArray? {
        val active = synchronized(lock) { session } ?: return null
        val frames = MdxStft.FRAMES
        val tensor = MdxStft.forward(modelAudio, frames)
        if (tensor.isEmpty()) return null

        return runCatching {
            val environment = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, 4, MdxStft.BINS.toLong(), frames.toLong())
            // Straight into native memory: two megabytes of tensor on the
            // Java heap is two megabytes the phone does not need to keep.
            val backing = ByteBuffer.allocateDirect(tensor.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            backing.asFloatBuffer().put(tensor)
            OnnxTensor.createTensor(environment, backing.asFloatBuffer(), shape).use { input ->
                active.run(mapOf(active.inputNames.first() to input)).use { outputs ->
                    val outputTensor = outputs[0] as OnnxTensor
                    val size = outputTensor.info.shape.fold(1L) { acc, d -> acc * d }.toInt()
                    val values = FloatArray(size)
                    // Absolute reads: the view's own position is not
                    // something to depend on.
                    val source = outputTensor.floatBuffer
                    for (index in values.indices) values[index] = source.get(index)
                    val vocal = MdxStft.inverse(values, frames)
                    if (vocal.isEmpty()) null else toSourceRate(vocal)
                }
            }
        }.getOrNull()
    }

    // ---- rates ---------------------------------------------------------

    /** Frames of source-rate audio in one window. */
    private fun sourceChunkFrames(): Int =
        (MODEL_CHUNK_FRAMES.toLong() * sampleRate / MODEL_RATE).toInt().coerceAtLeast(1)

    /**
     * Converts a window of song to the model's rate.
     *
     * Returns the input untouched when it is already at the right rate,
     * which is the common case: most of what this app plays is 44.1 kHz.
     */
    private fun toModelRate(dry: FloatArray, frames: Int): FloatArray? {
        if (sampleRate == MODEL_RATE) return dry
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (frame in 0 until frames) {
            left[frame] = dry[frame * 2]
            right[frame] = dry[frame * 2 + 1]
        }
        val convertedLeft = MelSpectrogram.resample(left, sampleRate.toDouble(), MODEL_RATE.toDouble())
        val convertedRight = MelSpectrogram.resample(right, sampleRate.toDouble(), MODEL_RATE.toDouble())
        if (convertedLeft == null || convertedRight == null) return null
        val out = FloatArray(MODEL_CHUNK_FRAMES * 2)
        val count = minOf(MODEL_CHUNK_FRAMES, convertedLeft.size, convertedRight.size)
        for (frame in 0 until count) {
            out[frame * 2] = convertedLeft[frame]
            out[frame * 2 + 1] = convertedRight[frame]
        }
        return out
    }

    /**
     * Converts the vocal back to the rate the song arrived at.
     *
     * The answer is padded or trimmed to the length of the window it
     * belongs to, so the two always line up sample for sample.
     */
    private fun toSourceRate(vocal: FloatArray): FloatArray? {
        val frames = sourceChunkFrames()
        if (sampleRate == MODEL_RATE) {
            val out = FloatArray(frames * 2)
            System.arraycopy(vocal, 0, out, 0, minOf(vocal.size, out.size))
            return out
        }
        val left = FloatArray(MODEL_CHUNK_FRAMES)
        val right = FloatArray(MODEL_CHUNK_FRAMES)
        val count = minOf(MODEL_CHUNK_FRAMES, vocal.size / 2)
        for (frame in 0 until count) {
            left[frame] = vocal[frame * 2]
            right[frame] = vocal[frame * 2 + 1]
        }
        val convertedLeft = MelSpectrogram.resample(left, MODEL_RATE.toDouble(), sampleRate.toDouble())
        val convertedRight = MelSpectrogram.resample(right, MODEL_RATE.toDouble(), sampleRate.toDouble())
        if (convertedLeft == null || convertedRight == null) return null
        val out = FloatArray(frames * 2)
        val copied = minOf(frames, convertedLeft.size, convertedRight.size)
        for (frame in 0 until copied) {
            out[frame * 2] = convertedLeft[frame]
            out[frame * 2 + 1] = convertedRight[frame]
        }
        return out
    }

    companion object {
        /** The rate the network was trained on. */
        const val MODEL_RATE = 44_100

        /** Frames of audio in one window: 64 model frames of 1024 samples. */
        const val MODEL_CHUNK_FRAMES = MdxStft.FRAMES * MdxStft.HOP

        /** Windows the worker may be holding at once. */
        private const val MAX_IN_FLIGHT = 2
    }
}
