# Third-party native code

| Folder | Upstream | Version | License |
|---|---|---|---|
| `signalsmith-stretch/` | https://github.com/Signalsmith-Audio/signalsmith-stretch | 1.3.2 (`88061fd4`) | MIT (`LICENSE.txt`) |
| `signalsmith-linear/` | https://github.com/Signalsmith-Audio/linear | 0.6.6 | MIT (`LICENSE.txt`) |

Copied verbatim, apart from the local patches below; the Automix loop mix
(`native/loopmix/`) is the only user.
Signalsmith Stretch includes `signalsmith-linear/stft.h`, which resolves
against this folder.

## Local patches

Edge cases Signalsmith Stretch never reaches with our settings, closed
anyway. Each line is marked `Auris patch` in the source, and none changes the
audio our loop mix renders.

- `signalsmith-linear/fft.h`: `SimpleFFT`, `SimpleRealFFT` and `RealFFT`
  return at once from a size-0 transform instead of touching bin 0 of empty
  storage; a one-sample `SimpleRealFFT` writes its DC bin in the split layout
  too, as the packed layout does.
- `signalsmith-linear/stft.h`: the default interval is never 0 for blocks
  under 4 samples; `reset()` clears both sample counters; the one-sample
  `moveOutput` fast path counts its sample; the confined-Gaussian window's
  offset scale is computed as `1/(e^(-8f) + 1)` so it can't divide two
  underflowed values.
