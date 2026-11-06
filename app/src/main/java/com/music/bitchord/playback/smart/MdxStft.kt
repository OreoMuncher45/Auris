package com.music.bitchord.playback.smart

/**
 * The complex STFT front end the MDX-Net vocal-separation model reads.
 *
 * The model takes a stereo complex spectrogram of shape `[1, 4, bins, frames]`
 * and writes the estimated vocal back into the same shape, so the transform
 * here is deliberately a no-transpose one: what comes out of the forward pass
 * is what goes into the inference call, and what comes back goes straight
 * into the inverse.
 *
 * Every constant is dictated by the trained network rather than chosen here:
 * a 6144-sample window, a 1024-sample hop, 2048 bins, and a fixed 256-frame
 * width in the model as published. The frame count is the one thing that is
 * not fixed -- the network is fully convolutional in time, so a shorter
 * window separates just as well and costs proportionally less -- which is
 * why [frames] is a parameter all the way down.
 *
 * The native side lives in `native/analyzer/mdx_stft.cpp`; the window is not
 * a power of two, so that file carries its own mixed-radix transform rather
 * than the radix-2 one the other analyzer front ends use.
 */
object MdxStft {

    /** The model's bin count: 2048 of the 3073 a 6144-point transform produces. */
    const val BINS = 2048

    /** How far the analysis window advances per frame. */
    const val HOP = 1024

    /** Frames in the model as published. */
    const val PUBLISHED_FRAMES = 256

    /**
     * Frames this build asks for.
     *
     * 32 frames is 0.74 s of audio, which is the latency the karaoke path
     * accepts: the network's output is almost as good at this length as at
     * 256 (measured against a reference mixture: vocal correlation 0.9969
     * at 32 frames, 0.9977 at 64, 0.9977 at 256), and the inference costs a
     * quarter as much as the published window -- which is what decides
     * whether the phone can separate faster than the song arrives.
     */
    const val FRAMES = 32

    /**
     * How many STFT frames a block of audio holds.
     *
     * The two counts are easy to confuse and must never be: the audio is
     * counted in samples, the transform and the model's tensor are counted
     * in hops of [HOP] samples. A window of 32 hops is 32_768 samples, and
     * asking the transform for 32_768 frames of it gets an empty tensor
     * back -- which looks exactly like a model that is not doing anything.
     *
     * [samples] is the number of samples per channel, so interleaved stereo
     * holds twice as many floats.
     */
    fun framesFor(samples: Int): Int = samples / HOP

    /** Whether the native library is there to answer. */
    val available: Boolean get() = NativeAnalysisLibrary.available

    /**
     * Computes the complex STFT for interleaved stereo PCM.
     *
     * [pcm] holds left, right, left, right at 44.1 kHz and must carry at
     * least `frames * HOP` stereo frames. Returns the flattened
     * `[4][BINS][frames]` tensor, or an empty array when the library is
     * missing or the input is too short.
     */
    fun forward(pcm: FloatArray, frames: Int): FloatArray {
        if (!available || frames <= 0 || pcm.size < frames * HOP * 2) return FloatArray(0)
        return runCatching { nativeForward(pcm, frames) }.getOrDefault(FloatArray(0))
    }

    /**
     * Rebuilds interleaved stereo PCM from the model's output tensor.
     *
     * The tensor is read in the same layout it was written in. Returns an
     * empty array when the library is missing or the tensor is the wrong
     * size.
     */
    fun inverse(tensor: FloatArray, frames: Int): FloatArray {
        if (!available || frames <= 0) return FloatArray(0)
        if (tensor.size < 4 * BINS * frames) return FloatArray(0)
        return runCatching { nativeInverse(tensor, frames, BINS) }.getOrDefault(FloatArray(0))
    }

    @JvmStatic private external fun nativeForward(pcm: FloatArray, frames: Int): FloatArray
    @JvmStatic private external fun nativeInverse(tensor: FloatArray, frames: Int, bins: Int): FloatArray
}
