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

// Sample-rate conversion for the DSP analyzer's front end.
//
// The analyzer accepts exactly kAnalysisSampleRate (see audio_analysis.h /
// TrackFeatures.kt) and refuses anything else, so something has to convert;
// streams arrive at 44.1 or 48 kHz. Doing it by dropping samples would fold
// everything above the analyzer's Nyquist back down into the band it reads,
// and an aliased spectrum produces a wrong tempo/key/structure reading rather
// than a noisy one -- the transition planner would then take that reading at
// face value. So this is a windowed-sinc resampler rather than decimation:
// the anti-alias filter is the point of it.
//
// Calls borrow the input only until they return, own the returned storage,
// and are reentrant. The work is O(taps * output) and allocates, so it
// belongs on a worker thread alongside the rest of analysis.

#pragma once

#include <cstddef>
#include <vector>

namespace bitchord::smart {

// Zero crossings kept either side of each output sample. Higher is a better
// stopband at linear cost; 32 puts the aliasing well below the noise floor of
// anything that reaches this code as lossy audio.
inline constexpr size_t kResamplerZeroCrossings = 32;

/**
 * Resamples contiguous mono float PCM from `input_rate` to `output_rate`.
 *
 * Returns the input unchanged when the rates already match, and an empty
 * vector when either rate is not positive or the input is empty. There is no
 * error channel: callers treat empty as "no analysis available", which is
 * what every other stage of this pipeline does.
 */
std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
);

/**
 * The streaming form of [Resample].
 *
 * [Resample] converts a whole array at a time: every output sample has the
 * input on both sides of it, so the filter can be centred there, and the two
 * ends are clamped because there is nothing beyond them. A live stream has
 * neither -- the audio arrives in pieces, and the piece that arrives now has
 * no future attached to it.
 *
 * This one carries the pieces. The samples that have not been converted yet
 * are kept, the output's place on the input timeline is carried as an exact
 * fraction of a sample, and the filter is centred on the same grid the
 * whole-array version would have used. A long stream therefore converts at
 * exactly the rate it should: no drift over minutes, and no seam at the
 * joins.
 *
 * Interleaved, with a fixed channel count, because the separator needs both
 * channels of a stereo pair converted the same way.
 *
 * The converter waits [lookahead] input samples before it can produce its
 * first output sample -- the filter reaches that far either side of the
 * sample it is centred on. Everything downstream of it is therefore that
 * much later than the audio that went in.
 *
 * A rate pair it cannot do exactly (a rate that is not a whole number of
 * hertz, or one that needs more distinct phases than the table holds) is
 * reported by [supported] as false rather than approximated: the caller
 * decides what to do about it, and a stream that drifts is worse than no
 * conversion at all.
 */
class StreamResampler {
 public:
  StreamResampler(double input_rate, double output_rate, int channels);

  /** Whether this rate pair can be converted exactly. */
  bool supported() const { return supported_; }

  /** Whether the rates are the same, so the converter only copies. */
  bool passthrough() const { return passthrough_; }

  /**
   * How many input samples the output waits for.
   *
   * The filter's reach either side of the sample it is centred on: the
   * converter cannot answer for a position until the input that far past it
   * has arrived.
   */
  size_t lookahead() const { return reach_; }

  /** Forgets everything, as though the stream had just started. */
  void Reset();

  /**
   * Converts a block of interleaved input and appends the result to `out`.
   *
   * The output is however much of the stream has become convertible by now,
   * which is usually less than the input asks for: the tail of the filter
   * reaches past the end of what has arrived.
   */
  void Process(const float* input, size_t frames, std::vector<float>* out);

  /**
   * Ends the stream: the input is padded with silence and whatever is left
   * of it is converted.
   */
  void Flush(std::vector<float>* out);

 private:
  void AppendInput(const float* input, size_t frames);
  void Produce(std::vector<float>* out);
  void BuildWeights();

  int channels_ = 1;
  bool supported_ = false;
  bool passthrough_ = false;

  // The rate pair as whole numbers: output samples per `up_` input samples
  // step `down_` input samples on.
  long up_ = 1;
  long down_ = 1;
  double cutoff_ = 0.5;
  double half_width_ = 0.0;
  long reach_ = 0;
  long taps_ = 0;

  // One filter row per phase, each `taps_` weights long.
  std::vector<float> weights_;

  // The input that has arrived but has not been converted yet, interleaved.
  std::vector<float> fifo_;
  // Frames in the fifo.
  size_t fifo_frames_ = 0;
  // The stream position of the fifo's first frame.
  long long in_base_ = 0;
  // How many output samples have been made.
  long long out_index_ = 0;
};

}  // namespace bitchord::smart
