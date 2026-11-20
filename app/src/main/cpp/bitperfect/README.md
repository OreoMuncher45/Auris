# Bit-perfect native output

`exclusive_output.cpp` opens an AAudio exclusive stream for a built-in DAC
(a DAP's headphone or line output) at a track's own rate and format, and
accepts the stream only if Android granted exclusive mode exactly as asked.

It is taken unchanged from upstream BitChord (commit `b5c469be`), which
adapted it from [Mornye](https://github.com/mornyeapp/Mornye), Copyright 2026
zarzet, licensed LGPL-3.0-only. The file keeps that licence in its header.

| Library | Upstream | Version | Licence | How |
|---|---|---|---|---|
| Oboe | https://github.com/google/oboe | 1.11.0, SHA-256 pinned in `../CMakeLists.txt` | Apache-2.0 | Fetched at configure time, linked statically into `libbitchord_exclusive.so` |
