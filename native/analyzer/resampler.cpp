/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

#include "resampler.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bitchord::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

// Kernel table entries per unit of input-sample offset. At 512 the spacing
// between entries is ~0.002 of a sample, and linear interpolation across that
// is orders of magnitude below the quantization already in the audio.
constexpr double kKernelResolution = 512.0;

double Sinc(double x) {
  if (std::abs(x) < 1e-12) return 1.0;
  const double scaled = kPi * x;
  return std::sin(scaled) / scaled;
}

// Blackman rather than Hann: the extra term buys roughly 20 dB of stopband
// attenuation for one more cosine per tap, and the stopband is the only reason
// this filter exists.
double Blackman(double position) {
  // `position` runs 0..1 across the whole window.
  return 0.42 - 0.5 * std::cos(2.0 * kPi * position) +
         0.08 * std::cos(4.0 * kPi * position);
}

}  // namespace

namespace {

// The filter's value at `offset` input samples from an output sample's centre.
double KernelAt(double offset, double cutoff, double half_width) {
  offset = std::abs(offset);
  if (offset >= half_width) return 0.0;
  const double window = Blackman((offset + half_width) / (2.0 * half_width));
  return 2.0 * cutoff * Sinc(2.0 * cutoff * offset) * window;
}

// The most distinct fractional positions [ResamplePolyphase] precomputes a
// filter for. 44.1 and 48 kHz into 11.025 or 22.05 kHz need at most 147; a
// pair of rates that would need more takes the general path below.
constexpr long kMaxPhases = 1024;

// Both rates as whole numbers, or false when either isn't one.
bool WholeRate(double rate, long* whole) {
  const double rounded = std::round(rate);
  if (rounded < 1.0 || std::abs(rate - rounded) > 1e-9) return false;
  *whole = static_cast<long>(rounded);
  return true;
}

long Gcd(long a, long b) {
  while (b != 0) {
    const long next = a % b;
    a = b;
    b = next;
  }
  return a;
}

// The same filter as the general path below, for rates in a whole-number
// ratio -- which is every pair this app converts between. Output sample i
// sits at i * down / up input samples, so its fractional position is one of
// only `up` values, and each of those gets its filter worked out once,
// exactly, up front. Away from the edges an output sample is then a plain
// dot product of that filter with a contiguous run of input: no table
// lookup, no interpolation and no clamping per tap, and a loop the compiler
// turns into vector multiply-adds. The general path spent nearly all of the
// analysis thread's time here on a phone; this is the same arithmetic done
// once instead of once per tap.
//
// Within a few hundredths of a part per million of the general path: its
// kernel is read off a table and interpolated where this one is exact, and it
// accumulates in double where this accumulates in float.
bool ResamplePolyphase(
  const std::vector<float>& input,
  long input_rate,
  long output_rate,
  std::vector<float>* out
) {
  const long divisor = Gcd(input_rate, output_rate);
  const long up = output_rate / divisor;
  const long down = input_rate / divisor;
  if (up > kMaxPhases) return false;

  const double ratio = static_cast<double>(up) / static_cast<double>(down);
  const double cutoff = 0.5 * std::min(1.0, ratio);
  const double half_width = static_cast<double>(kResamplerZeroCrossings) / (2.0 * cutoff);

  const size_t output_count =
    static_cast<size_t>(std::floor(static_cast<double>(input.size()) * ratio));
  if (output_count == 0) {
    out->clear();
    return true;
  }

  // Per phase: the first tap, relative to the output sample's whole input
  // position, and the taps' weights from there, normalized to unity gain.
  // Every phase gets the same tap count -- the widest any needs -- and the
  // taps a phase doesn't reach weigh zero, so each filter is one fixed-length
  // row the loop below never has to bound separately.
  const long reach = static_cast<long>(std::ceil(half_width)) + 1;
  const long taps = 2 * reach + 1;
  std::vector<float> weights(static_cast<size_t>(up * taps), 0.0f);
  for (long phase = 0; phase < up; ++phase) {
    const double fraction = static_cast<double>(phase) / static_cast<double>(up);
    double sum = 0.0;
    std::vector<double> row(static_cast<size_t>(taps));
    for (long k = 0; k < taps; ++k) {
      row[k] = KernelAt(fraction - static_cast<double>(k - reach), cutoff, half_width);
      sum += row[k];
    }
    float* destination = weights.data() + phase * taps;
    for (long k = 0; k < taps; ++k) {
      destination[k] = static_cast<float>(sum != 0.0 ? row[k] / sum : 0.0);
    }
  }

  out->assign(output_count, 0.0f);
  const long size = static_cast<long>(input.size());
  const float* samples = input.data();
  for (size_t index = 0; index < output_count; ++index) {
    // 64-bit whatever `long` is: a long track times a 640 step overflows 32.
    const int64_t position = static_cast<int64_t>(index) * down;
    const long whole = static_cast<long>(position / up);
    const long phase = static_cast<long>(position % up);
    const float* row = weights.data() + phase * taps;
    const long first = whole - reach;

    if (first >= 0 && first + taps <= size) {
      const float* run = samples + first;
      float sum = 0.0f;
      {
        // Reassociated so the sum can be split across vector lanes; the
        // rounding this changes is far below the audio's own.
#pragma clang fp reassociate(on)
        for (long k = 0; k < taps; ++k) sum += row[k] * run[k];
      }
      (*out)[index] = sum;
    } else {
      // The edges, clamped as the general path clamps them and normalized
      // by the weight the taps actually landed on.
      double sum = 0.0;
      double weight_sum = 0.0;
      for (long k = 0; k < taps; ++k) {
        const long tap = std::min(std::max(first + k, 0L), size - 1);
        sum += static_cast<double>(row[k]) * static_cast<double>(samples[tap]);
        weight_sum += row[k];
      }
      (*out)[index] = static_cast<float>(weight_sum != 0.0 ? sum / weight_sum : 0.0);
    }
  }
  return true;
}

}  // namespace

std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
) {
  if (input.empty() || input_rate <= 0.0 || output_rate <= 0.0) return {};
  if (std::abs(input_rate - output_rate) < 1e-6) return input;

  long whole_input = 0;
  long whole_output = 0;
  if (WholeRate(input_rate, &whole_input) && WholeRate(output_rate, &whole_output)) {
    std::vector<float> output;
    if (ResamplePolyphase(input, whole_input, whole_output, &output)) return output;
  }

  const double ratio = output_rate / input_rate;
  // Cutoff in cycles per *input* sample. When downsampling the limit is the
  // output Nyquist, which is what removes the content that would otherwise
  // alias; when upsampling there is nothing above the input Nyquist to remove.
  const double cutoff = 0.5 * std::min(1.0, ratio);
  const double half_width = static_cast<double>(kResamplerZeroCrossings) / (2.0 * cutoff);

  const size_t output_count =
    static_cast<size_t>(std::floor(static_cast<double>(input.size()) * ratio));
  if (output_count == 0) return {};

  // The kernel is precomputed rather than evaluated per tap. Evaluating it
  // inline costs a sin and two cos for every tap, and at ~128 taps per output
  // sample that is a quarter of a billion transcendental calls for a thirty
  // second window -- absorbed on a desktop, emphatically not on a phone. The
  // kernel is symmetric about the centre, so a table over |offset| covers it,
  // and linear interpolation between entries is far below the error already
  // present in the audio.
  const size_t table_size =
    static_cast<size_t>(std::ceil(half_width * kKernelResolution)) + 2;
  std::vector<double> kernel(table_size);
  for (size_t index = 0; index < table_size; ++index) {
    const double offset = static_cast<double>(index) / kKernelResolution;
    const double window = Blackman((offset + half_width) / (2.0 * half_width));
    kernel[index] = 2.0 * cutoff * Sinc(2.0 * cutoff * offset) * window;
  }

  std::vector<float> output(output_count, 0.0f);
  const auto last = static_cast<long>(input.size()) - 1;

  for (size_t index = 0; index < output_count; ++index) {
    // Where this output sample sits on the input timeline.
    const double centre = static_cast<double>(index) / ratio;
    const long first_tap = static_cast<long>(std::ceil(centre - half_width));
    const long last_tap = static_cast<long>(std::floor(centre + half_width));

    double sum = 0.0;
    double weight_sum = 0.0;
    for (long tap = first_tap; tap <= last_tap; ++tap) {
      const double offset = std::abs(centre - static_cast<double>(tap));
      const double scaled = offset * kKernelResolution;
      const auto slot = static_cast<size_t>(scaled);
      if (slot + 1 >= table_size) continue;
      const double fraction = scaled - static_cast<double>(slot);
      const double weight =
        kernel[slot] + fraction * (kernel[slot + 1] - kernel[slot]);

      // Clamping rather than zero-padding at the edges: zeros would read as a
      // hard cut and ring, which at the start of a track is exactly where the
      // beat tracker is looking for the first onset.
      const long clamped = std::min(std::max(tap, 0L), last);
      sum += weight * static_cast<double>(input[static_cast<size_t>(clamped)]);
      weight_sum += weight;
    }

    // Normalizing by the realized window keeps unity gain even where the taps
    // were clamped, so the edges do not come out quieter than the middle.
    output[index] = static_cast<float>(weight_sum != 0.0 ? sum / weight_sum : 0.0);
  }

  return output;
}

StreamResampler::StreamResampler(
    double input_rate,
    double output_rate,
    int channels)
    : channels_(channels > 0 ? channels : 1) {
  if (input_rate <= 0.0 || output_rate <= 0.0) return;

  if (std::abs(input_rate - output_rate) < 1e-6) {
    supported_ = true;
    passthrough_ = true;
    return;
  }

  long whole_input = 0;
  long whole_output = 0;
  if (!WholeRate(input_rate, &whole_input) || !WholeRate(output_rate, &whole_output)) {
    return;
  }
  const long divisor = Gcd(whole_input, whole_output);
  up_ = whole_output / divisor;
  down_ = whole_input / divisor;
  if (up_ > kMaxPhases) return;

  // The same filter the whole-array path uses, on the same grid: output
  // sample i sits at i * down_ / up_ input samples.
  const double ratio = static_cast<double>(up_) / static_cast<double>(down_);
  cutoff_ = 0.5 * std::min(1.0, ratio);
  half_width_ = static_cast<double>(kResamplerZeroCrossings) / (2.0 * cutoff_);
  reach_ = static_cast<long>(std::ceil(half_width_)) + 1;
  taps_ = 2 * reach_ + 1;
  BuildWeights();
  supported_ = true;
}

void StreamResampler::BuildWeights() {
  weights_.assign(static_cast<size_t>(up_ * taps_), 0.0f);
  std::vector<double> row(static_cast<size_t>(taps_));
  for (long phase = 0; phase < up_; ++phase) {
    const double fraction = static_cast<double>(phase) / static_cast<double>(up_);
    double sum = 0.0;
    for (long k = 0; k < taps_; ++k) {
      row[static_cast<size_t>(k)] =
          KernelAt(fraction - static_cast<double>(k - reach_), cutoff_, half_width_);
      sum += row[static_cast<size_t>(k)];
    }
    float* destination = weights_.data() + phase * taps_;
    for (long k = 0; k < taps_; ++k) {
      destination[k] = static_cast<float>(
          sum != 0.0 ? row[static_cast<size_t>(k)] / sum : 0.0);
    }
  }
}

void StreamResampler::Reset() {
  fifo_.clear();
  fifo_frames_ = 0;
  in_base_ = 0;
  out_index_ = 0;
}

void StreamResampler::AppendInput(const float* input, size_t frames) {
  if (frames == 0) return;
  fifo_.insert(fifo_.end(), input, input + frames * static_cast<size_t>(channels_));
  fifo_frames_ += frames;
}

void StreamResampler::Process(
    const float* input,
    size_t frames,
    std::vector<float>* out) {
  if (!supported_ || frames == 0) return;
  if (passthrough_) {
    out->insert(out->end(), input, input + frames * static_cast<size_t>(channels_));
    return;
  }
  AppendInput(input, frames);
  Produce(out);
}

void StreamResampler::Flush(std::vector<float>* out) {
  if (!supported_ || passthrough_) return;
  // The filter reaches `reach_` samples past the sample it is centred on, so
  // the last outputs cannot be made until the stream has ended. Silence
  // stands in for what was never there.
  const std::vector<float> silence(static_cast<size_t>(reach_ * channels_), 0.0f);
  AppendInput(silence.data(), static_cast<size_t>(reach_));
  Produce(out);
}

void StreamResampler::Produce(std::vector<float>* out) {
  for (;;) {
    // Where the next output sample sits on the input timeline, as an exact
    // fraction: `out_index_ * down_` units of 1/up_ input samples.
    const long long position = out_index_ * down_;
    const long long whole = position / up_;
    const long phase = static_cast<long>(position % up_);
    const long long available = in_base_ + static_cast<long long>(fifo_frames_);

    // The filter's far tap has to have arrived before this sample can be
    // made: the converter is centred on the sample, not on the past.
    if (whole + reach_ >= available) break;

    const float* row = weights_.data() + static_cast<size_t>(phase) * taps_;
    for (int channel = 0; channel < channels_; ++channel) {
      double sum = 0.0;
      double weight_sum = 0.0;
      for (long k = 0; k < taps_; ++k) {
        const float weight = row[k];
        if (weight == 0.0f) continue;
        long long index = whole - reach_ + k;
        // Before the stream began there is nothing to read, so the tap sits
        // on the first sample -- the same clamp the whole-array path uses at
        // its edges, and the same normalization by the weight that landed.
        if (index < in_base_) index = in_base_;
        const size_t offset =
            static_cast<size_t>(index - in_base_) * static_cast<size_t>(channels_) +
            static_cast<size_t>(channel);
        sum += static_cast<double>(weight) * fifo_[offset];
        weight_sum += weight;
      }
      out->push_back(
          static_cast<float>(weight_sum != 0.0 ? sum / weight_sum : 0.0));
    }
    out_index_++;

    // The input the next output cannot reach back to is let go, but the
    // filter's past is kept: the next sample still needs it.
    const long long next_position = out_index_ * down_;
    const long long next_whole = next_position / up_;
    const long long new_base = next_whole - reach_;
    if (new_base > in_base_) {
      const size_t drop = static_cast<size_t>(new_base - in_base_);
      fifo_.erase(
          fifo_.begin(),
          fifo_.begin() + drop * static_cast<size_t>(channels_));
      fifo_frames_ -= drop;
      in_base_ = new_base;
    }
  }
}

}  // namespace bitchord::smart
