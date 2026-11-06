/*
 * Complex STFT front end for the MDX-Net vocal-separation model.
 *
 * The transform this implements is not the radix-2 one the other analyzer
 * front ends use, because the model's window is 6144 samples -- 2048 times
 * three, not a power of two. The decomposition below splits a 6144-point
 * transform into three 2048-point transforms and a three-point combination;
 * the index arithmetic is fiddly enough to be worth stating, because getting
 * it wrong produces a spectrum that looks plausible and separates nothing:
 *
 *   x[n]      n = r + 3*s,   r in [0,3), s in [0,2048)
 *   A_r[m] = sum_s x[r + 3*s] * W_2048^(s*m)
 *   X[k] = sum_r W_6144^(r*k) * A_r[k mod 2048]
 *
 * The rows are strided rather than contiguous: that is what makes the inner
 * term come out as a clean 2048-point transform, because W_6144^(3*s*k) is
 * W_2048^(s*k). The outer term then needs the full-length twiddle, not the
 * three-point one -- the small factor is in the *input* index here, not the
 * output index, so the three-point root never appears.
 *
 * The inverse transform is the conjugate trick: conj(FFT(conj(X))) is the
 * unnormalized inverse, and the 1/N that the usual definition carries is
 * applied by the overlap-add normalisation instead, exactly where the
 * reference implementation applies it.
 */

#include "mdx_stft.h"

#include <cmath>
#include <complex>
#include <vector>

namespace bitchord::smart {
namespace {

constexpr float kPi = 3.14159265358979323846f;

// Periodic Hann window, matching torch.hann_window(n, periodic=True): the
// divisor is the window length, not length - 1.
std::vector<float> HannWindow(size_t size) {
  std::vector<float> window(size);
  for (size_t index = 0; index < size; ++index) {
    window[index] = 0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(index) /
                                           static_cast<float>(size));
  }
  return window;
}

// A radix-2 FFT over a fixed size, with the bit-reversal table and the
// twiddle table built once. Private to this file, matching the convention
// the other analyzer files use.
class Radix2Fft {
 public:
  explicit Radix2Fft(size_t size) : size_(size), reversed_(size), twiddle_(size / 2) {
    size_t bits = 0;
    while ((size_t(1) << bits) < size_) ++bits;
    for (size_t index = 0; index < size_; ++index) {
      size_t reversed = 0;
      for (size_t bit = 0; bit < bits; ++bit) {
        if (index & (size_t(1) << bit)) reversed |= size_t(1) << (bits - 1 - bit);
      }
      reversed_[index] = static_cast<uint32_t>(reversed);
    }
    for (size_t index = 0; index < size / 2; ++index) {
      const float angle = -2.0f * kPi * static_cast<float>(index) / static_cast<float>(size);
      twiddle_[index] = std::complex<float>(std::cos(angle), std::sin(angle));
    }
  }

  // In-place forward transform: data[k] = sum_n input[n] * exp(-2*pi*i*n*k/size).
  void Forward(std::complex<float>* data) const {
    for (size_t index = 0; index < size_; ++index) {
      const size_t mirrored = reversed_[index];
      if (mirrored > index) std::swap(data[index], data[mirrored]);
    }
    for (size_t length = 2; length <= size_; length <<= 1) {
      const size_t half = length / 2;
      const size_t stride = size_ / length;
      for (size_t start = 0; start < size_; start += length) {
        for (size_t offset = 0; offset < half; ++offset) {
          const std::complex<float> even = data[start + offset];
          const std::complex<float> odd = data[start + offset + half] * twiddle_[offset * stride];
          data[start + offset] = even + odd;
          data[start + offset + half] = even - odd;
        }
      }
    }
  }

 private:
  size_t size_;
  std::vector<uint32_t> reversed_;
  std::vector<std::complex<float>> twiddle_;
};

// Scratch space for the 6144-point transform, so the hot loop does not
// allocate once per frame.
class MdxFft {
 public:
  MdxFft() : row_count_(kMdxFft / 3), row_fft_(kMdxFft / 3) {
    twiddle_.resize(kMdxFft);
    for (size_t index = 0; index < kMdxFft; ++index) {
      const float angle = -2.0f * kPi * static_cast<float>(index) / static_cast<float>(kMdxFft);
      twiddle_[index] = std::complex<float>(std::cos(angle), std::sin(angle));
    }
  }

  void Forward(std::complex<float>* data) {
    // The three strided rows: row r is x[r], x[r+3], x[r+6], ...
    for (size_t r = 0; r < 3; ++r) {
      for (size_t s = 0; s < row_count_; ++s) {
        row_[s] = data[r + 3 * s];
      }
      row_fft_.Forward(row_.data());
      for (size_t s = 0; s < row_count_; ++s) {
        row_values_[r * row_count_ + s] = row_[s];
      }
    }

    // X[k] = sum_r W_6144^(r*k) * A_r[k mod 2048]
    for (size_t m = 0; m < row_count_; ++m) {
      const std::complex<float> a0 = row_values_[m];
      const std::complex<float> a1 = row_values_[row_count_ + m];
      const std::complex<float> a2 = row_values_[2 * row_count_ + m];
      for (size_t t = 0; t < 3; ++t) {
        const size_t k = m + row_count_ * t;
        const std::complex<float> w1 = twiddle_[k];
        const std::complex<float> w2 = twiddle_[(2 * k) % kMdxFft];
        scratch_[k] = a0 + w1 * a1 + w2 * a2;
      }
    }
    for (size_t index = 0; index < kMdxFft; ++index) data[index] = scratch_[index];
  }

  // In-place unnormalized inverse: data[n] = sum_k input[k] * exp(+2*pi*i*n*k/size).
  void Inverse(std::complex<float>* data) {
    for (size_t index = 0; index < kMdxFft; ++index) data[index] = std::conj(data[index]);
    Forward(data);
    for (size_t index = 0; index < kMdxFft; ++index) data[index] = std::conj(data[index]);
  }

 private:
  size_t row_count_;
  Radix2Fft row_fft_;
  std::vector<std::complex<float>> twiddle_;
  std::vector<std::complex<float>> row_ = std::vector<std::complex<float>>(kMdxFft / 3);
  std::vector<std::complex<float>> row_values_ = std::vector<std::complex<float>>(kMdxFft);
  std::vector<std::complex<float>> scratch_ = std::vector<std::complex<float>>(kMdxFft);
};

// The model's window size is fixed, so the tables are built once.
const std::vector<float>& Window() {
  static const std::vector<float> window = HannWindow(kMdxFft);
  return window;
}

}  // namespace

MdxTensor MdxForward(const std::vector<float>& interleaved_stereo, size_t frames) {
  MdxTensor result;
  if (frames == 0 || frames > kMdxMaxFrames) return result;

  const size_t samples = frames * kMdxHop;
  if (interleaved_stereo.size() < samples * 2) return result;

  const size_t pad = kMdxFft / 2;
  const size_t padded = samples + 2 * pad;
  const std::vector<float>& window = Window();

  result.frames = frames;
  result.bins = kMdxBins;
  result.values.assign(kMdxTensorChannels * kMdxBins * frames, 0.0f);

  std::vector<float> channel(padded);
  std::vector<std::complex<float>> spectrum(kMdxFft);
  MdxFft fft;

  for (size_t side = 0; side < 2; ++side) {
    // Reflect padding, the way torch.stft(center=True, pad_mode="reflect")
    // does it: the edge sample is not repeated, so the left pad reads
    // x[pad], x[pad-1], ... x[1] and the right pad x[n-2], x[n-3], ...
    for (size_t index = 0; index < pad; ++index) {
      channel[pad - 1 - index] = interleaved_stereo[(1 + index) * 2 + side];
    }
    for (size_t index = 0; index < samples; ++index) {
      channel[pad + index] = interleaved_stereo[index * 2 + side];
    }
    for (size_t index = 0; index < pad; ++index) {
      channel[pad + samples + index] = interleaved_stereo[(samples - 2 - index) * 2 + side];
    }

    for (size_t frame = 0; frame < frames; ++frame) {
      const float* source = channel.data() + frame * kMdxHop;
      for (size_t index = 0; index < kMdxFft; ++index) {
        spectrum[index] = std::complex<float>(source[index] * window[index], 0.0f);
      }
      fft.Forward(spectrum.data());

      const size_t real_channel = side * 2;
      for (size_t bin = 0; bin < kMdxBins; ++bin) {
        const size_t index = (real_channel * kMdxBins + bin) * frames + frame;
        result.values[index] = spectrum[bin].real();
        result.values[index + kMdxBins * frames] = spectrum[bin].imag();
      }
      // The three lowest bins are removed before the network sees them.
      for (size_t bin = 0; bin < kMdxZeroBins; ++bin) {
        const size_t index = (real_channel * kMdxBins + bin) * frames + frame;
        result.values[index] = 0.0f;
        result.values[index + kMdxBins * frames] = 0.0f;
      }
    }
  }
  return result;
}

std::vector<float> MdxInverse(const MdxTensor& tensor) {
  std::vector<float> result;
  const size_t frames = tensor.frames;
  const size_t bins = tensor.bins;
  if (frames == 0 || bins == 0 || tensor.values.size() < kMdxTensorChannels * bins * frames) {
    return result;
  }

  const size_t samples = frames * kMdxHop;
  const size_t pad = kMdxFft / 2;
  const size_t padded = samples + 2 * pad;
  const std::vector<float>& window = Window();

  // The overlap-add denominator: the sum of the squared windows at each
  // output sample. Constant for a given frame count, so it is built once.
  std::vector<float> norm(padded, 0.0f);
  for (size_t frame = 0; frame < frames; ++frame) {
    const size_t start = frame * kMdxHop;
    for (size_t index = 0; index < kMdxFft; ++index) {
      norm[start + index] += window[index] * window[index];
    }
  }

  std::vector<float> accum(padded, 0.0f);
  std::vector<std::complex<float>> work(kMdxFft);
  MdxFft fft;

  result.assign(samples * 2, 0.0f);

  for (size_t side = 0; side < 2; ++side) {
    std::fill(accum.begin(), accum.end(), 0.0f);
    const size_t real_channel = side * 2;

    for (size_t frame = 0; frame < frames; ++frame) {
      // The transform is over the full 6144 points, so the half-spectrum the
      // model works in has to be mirrored back: bin k's partner is 6144-k
      // and holds its conjugate.
      work[0] = std::complex<float>(tensor.values[(real_channel * bins + 0) * frames + frame], 0.0f);
      const size_t copy = bins < kMdxSpectrumBins ? bins : kMdxSpectrumBins;
      for (size_t bin = 1; bin < copy; ++bin) {
        const size_t index = (real_channel * bins + bin) * frames + frame;
        const std::complex<float> value(
            tensor.values[index], tensor.values[index + bins * frames]);
        work[bin] = value;
        work[kMdxFft - bin] = std::conj(value);
      }
      // Everything above the model's range is zero: the network never saw it.
      for (size_t bin = copy; bin < kMdxSpectrumBins; ++bin) {
        work[bin] = std::complex<float>(0.0f, 0.0f);
        work[kMdxFft - bin] = std::complex<float>(0.0f, 0.0f);
      }

      fft.Inverse(work.data());

      const size_t start = frame * kMdxHop;
      for (size_t index = 0; index < kMdxFft; ++index) {
        accum[start + index] += work[index].real() * window[index];
      }
    }

    for (size_t index = 0; index < samples; ++index) {
      const size_t at = pad + index;
      const float denominator = norm[at] * static_cast<float>(kMdxFft);
      const float value = denominator > 1e-8f ? (accum[at] / denominator) * kMdxCompensation : 0.0f;
      result[index * 2 + side] = std::isfinite(value) ? value : 0.0f;
    }
  }
  return result;
}

}  // namespace bitchord::smart
