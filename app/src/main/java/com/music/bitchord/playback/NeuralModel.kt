package com.music.bitchord.playback

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.playback.smart.MdxStft
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the separator needs of a model: to be open, and to answer.
 *
 * The interface is here so the separator's own arithmetic — the part that
 * decides what the listener hears and when — can be measured without a
 * thirty-megabyte network in the way. The arithmetic is where the bugs
 * were: a cursor that did not advance made the song repeat, and a cursor
 * that skipped made it jump.
 */
interface VocalModel {

    /** Whether the model is open and can answer. */
    val ready: Boolean

    /**
     * Why the last attempt failed, if it did.
     *
     * The separation failing quietly is the worst kind of failure: the
     * song plays, the counter says windows are being separated, and
     * nothing is different about the music. So the reason is kept where
     * the screen and the log can both read it.
     */
    val lastError: String?
        get() = null

    /**
     * The vocal in one window of song.
     *
     * [modelAudio] is interleaved stereo at [NeuralVocalSeparator.MODEL_RATE]
     * and holds one whole window. [frames] counts the *audio* frames in it,
     * not the transform's: the transform and the model's tensor are counted
     * in hops, and the two are not the same number.
     *
     * The answer comes back in the same shape: the vocal, at the same rate,
     * sample for sample with the song it came from. Null when the model
     * cannot answer, and the separator plays that stretch dry rather than
     * not playing it at all.
     */
    fun separate(modelAudio: FloatArray, frames: Int): FloatArray?
}

/**
 * The separator's model: one ONNX graph, opened once and shared.
 *
 * A model is thirty megabytes of weights and takes long enough to open that
 * it cannot be opened on the thread that plays the music. It is also the one
 * thing here worth holding on to: the separation itself is cheap by
 * comparison, and a phone that has to open the file again for every track
 * would spend its life doing nothing else.
 *
 * So the session lives here, separately from the audio that flows through it.
 * Two players can be playing at once during a crossfade, and each has its own
 * [NeuralVocalSeparator] with its own buffers — but they share this, and
 * ONNX Runtime's sessions are safe to run from more than one thread at a
 * time.
 *
 * The session is also what keeps a phone warm. ONNX Runtime's worker threads
 * spin rather than sleep, so a session left open while nothing is playing is
 * a phone running at full tilt for no reason: [close] is how the app puts it
 * down, and the model is opened again when there is music to separate.
 */
class NeuralModel(
    /** Where the model is, or null when none has been downloaded. */
    private val modelPath: () -> String?,
) : VocalModel {

    /** What the model is doing, for the screen that asks. */
    enum class State {
        /** No model on the phone. */
        NONE,

        /** A model is being opened. */
        OPENING,

        /** A model is open and can answer. */
        OPEN,

        /** A model is there but could not be opened. */
        FAILED,
    }

    private val lock = Any()
    private var session: OrtSession? = null
    private var sessionPath: String? = null

    /** Whether a model is open and the separator can do anything. */
    @Volatile
    override var ready: Boolean = false
        private set

    /** Set when the model could not be opened, for the log and the screen. */
    @Volatile
    var failure: String? = null
        private set

    /** What the model is doing at the moment. */
    @Volatile
    var state: State = State.NONE
        private set

    /**
     * Why the last separation failed, if it did.
     *
     * Empty when the last one worked. This is the difference between "the
     * model is quiet" and "the model is broken", and there is no other way
     * to tell them apart from the outside.
     */
    @Volatile
    override var lastError: String? = null
        private set

    /**
     * Opens the model, if there is one.
     *
     * Cheap to call repeatedly: it only does work when the path has changed.
     * Not called from the audio thread — opening a thirty-megabyte model
     * takes long enough to be heard as a hitch.
     */
    fun ensureSession() {
        val path = modelPath() ?: run {
            close()
            return
        }
        synchronized(lock) {
            if (session != null && sessionPath == path) {
                ready = true
                state = State.OPEN
                return
            }
            close()
            failure = null
            val file = File(path)
            if (!file.exists()) {
                ready = false
                state = State.NONE
                return
            }
            state = State.OPENING
            runCatching {
                val options = OrtSession.SessionOptions().apply {
                    // One thread per core, but no more: the separation runs on
                    // its own thread while the audio thread keeps feeding it,
                    // and more threads than that only make the audio thread
                    // wait longer for a core.
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // The arena and the memory pattern both hold on to large
                    // blocks for the life of the session, which is exactly
                    // what a closed session should not do.
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
            }.onSuccess {
                session = it
                sessionPath = path
                ready = true
                state = State.OPEN
                lastError = null
            }.onFailure {
                failure = it.message ?: it.javaClass.simpleName
                ready = false
                state = State.FAILED
            }
        }
    }

    /**
     * Puts the model down.
     *
     * The session's threads spin while it is open, so this is what a paused
     * app calls: the weights go back to the disk and the phone stops
     * working.
     */
    fun close() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionPath = null
            ready = false
            if (state != State.FAILED) state = State.NONE
        }
    }

    /**
     * Runs the model over one window and returns the vocal it found.
     *
     * [modelAudio] is interleaved stereo at [NeuralVocalSeparator.MODEL_RATE]
     * and holds one whole window; [frames] counts the audio frames in it.
     * The answer comes back in the same shape: the vocal, at the same rate,
     * sample for sample with the song it came from.
     */
    override fun separate(modelAudio: FloatArray, frames: Int): FloatArray? {
        val active = synchronized(lock) { session } ?: return failed("the model is not open")
        // The audio is counted in samples; the transform and the tensor are
        // counted in hops of 1024. A window is 32 hops and 32_768 samples,
        // and asking the transform for 32_768 frames of a window that holds
        // 32 gets an empty tensor back -- which is a model that appears to
        // do nothing at all, and very nearly was one.
        val stftFrames = MdxStft.framesFor(frames)
        if (stftFrames <= 0) return failed("the window is empty")
        val tensor = MdxStft.forward(modelAudio, stftFrames)
        if (tensor.isEmpty()) return failed("the transform returned nothing")

        return try {
            val environment = OrtEnvironment.getEnvironment()
            val shape = tensorShape(stftFrames)
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
                    val pcm = MdxStft.inverse(values, stftFrames)
                    if (pcm.isEmpty()) failed("the inverse transform returned nothing")
                    else {
                        lastError = null
                        pcm
                    }
                }
            }
        } catch (error: Throwable) {
            failed("the model refused the window: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Records why the last window could not be separated. */
    private fun failed(reason: String): FloatArray? {
        lastError = reason
        return null
    }

    companion object {

        /**
         * The tensor the model reads: one song, four channels, the bins,
         * and the frames.
         *
         * The last number is the one that matters: it is the transform's
         * frame count, not the window's sample count, and the two differ by
         * a factor of the hop.
         */
        internal fun tensorShape(stftFrames: Int): LongArray =
            longArrayOf(1, 4, MdxStft.BINS.toLong(), stftFrames.toLong())
    }
}
