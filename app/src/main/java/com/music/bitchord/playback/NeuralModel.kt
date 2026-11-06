package com.music.bitchord.playback

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.playback.smart.MdxStft
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
) {

    private val lock = Any()
    private var session: OrtSession? = null
    private var sessionPath: String? = null

    /** Whether a model is open and the separator can do anything. */
    @Volatile
    var ready: Boolean = false
        private set

    /** Set when the model could not be opened, for the log and the screen. */
    @Volatile
    var failure: String? = null
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
                return
            }
            close()
            failure = null
            val file = File(path)
            if (!file.exists()) {
                ready = false
                return
            }
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
            }.onFailure {
                failure = it.message ?: it.javaClass.simpleName
                ready = false
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
        }
    }

    /**
     * Runs the model over one window and returns the vocal it found.
     *
     * [modelAudio] is interleaved stereo at [NeuralVocalSeparator.MODEL_RATE]
     * and holds one whole window. The answer comes back in the same shape:
     * the vocal, at the same rate, sample for sample with the song it came
     * from.
     */
    fun separate(modelAudio: FloatArray, frames: Int): FloatArray? {
        val active = synchronized(lock) { session } ?: return null
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
                    MdxStft.inverse(values, frames)
                }
            }
        }.getOrNull()
    }
}
