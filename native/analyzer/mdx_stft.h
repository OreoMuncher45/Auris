/*
 * Complex STFT front end for the MDX-Net vocal-separation model.
 *
 * The model this reads was trained on a complex spectrogram with a very
 * specific shape, and every constant here is dictated by that training data
 * rather than chosen for convenience:
 *
 *   n_fft   6144   the model's window; not a power of two, so the FFT below
 *                   is a mixed-radix 3 x 2044 decomposition rather than the
 *                   radix-2 one the other analyzer front ends use.
 *   hop     1024
 *   bins    2048   the model's dim_f. The FFT produces 3073 bins; the top
 *                   1025 of them (14.7 kHz and up) are outside the range the
 *                   network was trained on and are zeroed on the way back.
 *   frames  any    the network is fully convolutional in time, so the frame
 *                   count is a latency choice, not a model constraint. 64
 *                   frames is 1.49 s of audio, which is what the caller
 *                   normally asks for.
 *
 * The tensor layout is [channel][bin][frame] with the four channels being
 * left-real, left-imaginary, right-real, right-imaginary. That is the
 * model's own convention: it reads a stereo complex spectrogram and writes
 * the estimated vocal back into the same shape, so the inverse transform
 * needs no transpose and the caller never sees a complex number.
 *
 * The three lowest bins are zeroed on the way in. The network was trained
 * with DC and its two neighbours removed, so feeding them back in is
 * off-distribution.
 *
 * Calls borrow the input only until they return, own all returned storage,
 * and are reentrant. The work is O(frames * n_fft log n_fft) and allocates,
 * so it belongs on a worker thread.
 */

#pragma once

#include <cstddef>
#include <vector>

namespace bitchord::smart {

// The model's input rate. The caller resamples; this front end refuses
// anything else rather than quietly changing the spectrum.
inline constexpr double kMdxSampleRate = 44100;

// The analysis window, in samples.
inline constexpr size_t kMdxFft = 6144;

// How far the window advances per frame.
inline constexpr size_t kMdxHop = 1024;

// Bins the model reads. The FFT's own 3073 bins are cropped to these.
inline constexpr size_t kMdxBins = 2048;

// Bins the FFT itself produces.
inline constexpr size_t kMdxSpectrumBins = kMdxFft / 2 + 1;

// Left-real, left-imaginary, right-real, right-imaginary.
inline constexpr size_t kMdxTensorChannels = 4;

// DC and the two bins above it, removed before the network sees them.
inline constexpr size_t kMdxZeroBins = 3;

// The gain the inverse transform applies, matching the reference
// implementation the model was packaged with.
inline constexpr float kMdxCompensation = 1.035f;

// Frames per chunk when the caller does not care. 64 frames is 1.49 s.
inline constexpr size_t kMdxDefaultFrames = 64;

// The frame count the stock model was exported with.
inline constexpr size_t kMdxMaxFrames = 256;

/**
 * The model's tensor: four channels of complex bins over a frame axis.
 *
 * Row-major [channel][bin][frame], flattened: channel c, bin b, frame f is
 * at index (c * bins + b) * frames + f. That is exactly the layout the
 * ONNX tensor of shape [1, 4, bins, frames] wants, so the caller hands it
 * straight to an inference call with no transpose.
 */
struct MdxTensor {
  std::vector<float> values;
  size_t frames = 0;
  size_t bins = 0;
};

/**
 * Computes the complex STFT the vocal-separation model expects.
 *
 * `interleaved_stereo` holds planar-interleaved stereo Float32 PCM -- left,
 * right, left, right -- and must already be at kMdxSampleRate. It must hold
 * at least `frames * kMdxHop` samples.
 *
 * Returns an empty tensor when the input is too short for the requested
 * frame count, or when the frame count is zero or above kMdxMaxFrames.
 */
MdxTensor MdxForward(const std::vector<float>& interleaved_stereo, size_t frames);

/**
 * Rebuilds stereo PCM from the model's output tensor.
 *
 * The tensor is read in the same [left-real, left-imaginary, right-real,
 * right-imaginary] layout it was written in, so the network's output goes
 * straight back through the inverse transform with no rearrangement.
 *
 * Returns an empty vector for an empty tensor.
 */
std::vector<float> MdxInverse(const MdxTensor& tensor);

}  // namespace bitchord::smart
