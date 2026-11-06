package com.music.bitchord.playback.smart

/**
 * A live rate converter.
 *
 * The separator needs the song at the model's rate while the network looks
 * at it, and the song is usually at its own rate: 48 kHz, 96 kHz, whatever
 * the release was made at. The conversion has to be *exact* over a whole
 * track, not merely close: a converter that restarts its phase at every
 * buffer drifts by a fraction of a sample each time, and a fraction of a
 * sample between the song and the vocal taken out of it is a comb filter --
 * which is what "the vocal is still there, but slightly off pitch" sounds
 * like.
 *
 * So this one carries its state from one buffer to the next. The native side
 * keeps the samples that have not been converted yet, holds the output's
 * place on the input timeline as an exact fraction of a sample, and centres
 * its filter on the same grid a whole-array conversion would have used.
 *
 * Interleaved stereo, because the separator works on a stereo pair and both
 * channels have to be converted the same way.
 *
 * ## The library has to be loaded first
 *
 * A native method is found by name in the libraries the process has loaded,
 * and `System.loadLibrary` is what puts them there. Nothing else in the
 * Studio path does it: the STFT front end loads the library when it is
 * first asked for a spectrogram, and the spectrogram is computed *after* the
 * converter has already been made. So a converter that did not load the
 * library itself asked for a symbol that was not in memory, and the runtime
 * answered with an `UnsatisfiedLinkError` -- on the thread that plays the
 * music, which is the worst place for one.
 *
 * So the class loads the library itself, before anything asks it for a
 * symbol, and every call into it is guarded: a converter that cannot be
 * made is a converter that says so, rather than a crash.
 */
class MdxResampler private constructor(
    private var handle: Long,
    /** How many input frames the output waits for. */
    val lookaheadFrames: Int,
) : AutoCloseable {

    /** Whether this pair of rates can be converted. */
    val supported: Boolean get() = handle != 0L

    /**
     * Converts a block of interleaved frames and returns what came out.
     *
     * The answer is usually shorter than the question: the filter reaches
     * past the end of what has arrived, and the last of it waits for the
     * next block.
     */
    fun process(input: FloatArray, frames: Int): FloatArray {
        if (handle == 0L || frames <= 0) return FloatArray(0)
        return runCatching { nativeProcess(handle, input, frames) }.getOrDefault(FloatArray(0))
    }

    /**
     * Ends the stream: the input is padded with silence and whatever is left
     * of it is converted.
     */
    fun flush(): FloatArray {
        if (handle == 0L) return FloatArray(0)
        return runCatching { nativeFlush(handle) }.getOrDefault(FloatArray(0))
    }

    /** Forgets everything, as though the stream had just started. */
    fun reset() {
        if (handle != 0L) runCatching { nativeReset(handle) }
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { nativeDestroy(handle) }
            handle = 0L
        }
    }

    companion object {

        /**
         * Loads the native library before anyone asks it for a symbol.
         *
         * The load itself cannot fail loudly: a phone without the library
         * is a phone that plays the song unseparated, and that is a far
         * better outcome than a crash on the music thread.
         */
        init {
            NativeAnalysisLibrary.available
        }

        /**
         * Makes a converter, or one that cannot convert anything when the
         * rates are ones it cannot do exactly.
         */
        fun create(inputRate: Double, outputRate: Double, channels: Int = 2): MdxResampler {
            if (inputRate <= 0 || outputRate <= 0) {
                return MdxResampler(0L, 0)
            }
            // Without the library there is no converter, and a converter
            // that does not exist must not look like one that does.
            if (!NativeAnalysisLibrary.available) return MdxResampler(0L, 0)
            val handle = runCatching {
                nativeCreate(inputRate, outputRate, channels)
            }.getOrDefault(0L)
            if (handle == 0L) return MdxResampler(0L, 0)
            val lookahead = runCatching { nativeLookahead(handle) }.getOrDefault(0)
            return MdxResampler(handle, lookahead)
        }

        @JvmStatic private external fun nativeCreate(
            inputRate: Double,
            outputRate: Double,
            channels: Int,
        ): Long

        @JvmStatic private external fun nativeLookahead(handle: Long): Int

        @JvmStatic private external fun nativeProcess(
            handle: Long,
            input: FloatArray,
            frames: Int,
        ): FloatArray

        @JvmStatic private external fun nativeFlush(handle: Long): FloatArray

        @JvmStatic private external fun nativeReset(handle: Long)

        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
