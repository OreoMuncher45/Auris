# Audit — Auris 2.3 against upstream v1.9

What this app has, what it does not, and what was deliberately left out.
Written after the 2.3 cycle, from a file-by-file comparison with the upstream
v1.9 tree plus a pass over the feature list this cycle was asked for.

## Shipped this cycle

- **Performance** — the frame-cost work, ported rather than guessed:
  - The page behind a full-screen player stops drawing while the player covers
    it (`MainActivity.kt`, `drawWithContent`).
  - Opening Home re-fetches the Recents shelf only, not the whole feed
    (`MainViewModel.refreshHomeRecents`).
  - Adaptive refresh rate is off (`ComposeUiFlags.isAdaptiveRefreshRateEnabled`).
  - Release builds are silent: `android.util.Log` calls are assumed to have no
    side effects in ProGuard, and Media3's log level is `OFF` at runtime.
  - The polyphase resampler's fast path is ported (`native/analyzer/resampler.cpp`).
  - The track analyzer remembers a rendition it has already rejected
    (`badRenditions`), and stops re-probing while the device is hot
    (`thermalStatus`).
  - The audio cache moved to `noBackupFilesDir` and is no longer wiped on
    every version bump (`AudioCache`, `CACHE_FORMAT` stamp).
- **Flow** — the snapshot carries a version and an app stamp, so a stale
  snapshot from an older build is dropped rather than shown (`FlowStore`).
  The eighteen-locale quick-picks matcher no longer loses rows to a locale
  mismatch, and Flow can never become the hero shelf.
- **Party** — fallback servers, joined-base URLs and the invite link were
  already correct in this tree; verified rather than changed.
- **Spotify sign-in** — moved out of the library page into a root overlay, so
  a WebView is no longer composited inside a scrolling page.
- **Bit-perfect volume** — the software gain is applied only when the mixer is
  really bypassed, the exclusive USB path drives the DAC's own volume, and a
  bit-perfect stream is never attenuated twice.
- **Downloads** — a bulk enqueue is one lock, one service start and one
  progress broadcast instead of one per track; progress is throttled to 250 ms;
  the worker count is a setting (1–6, default 4).
- **Fonts** — an app font choice (default, system, serif, rounded, custom file
  via the system picker), plus script-aware faces for Arabic and Urdu, applied
  to lyrics and titles.
- **Customization** — its own page now, with six sections (Now Playing, Home,
  Library, Theme, Fonts, Motion), a live preview of the player, a custom-font
  picker, and motion switches (disable animations, reduce dynamic blur, liquid
  glass, folding nav bar, high-performance mode).
- **Motion** — page transitions are directional (tab swap slides, a pushed
  page slides and fades), and "disable animations" removes them entirely.
- **Library** — segmented (Device, Playlists, Artists, Albums), each segment
  with its own search query and its own empty state.
- **Podcasts** — a tab of its own, reading YouTube Music's podcast feed;
  a show's episodes are credited to the show rather than to nobody.
- **Audiobooks** — LibriVox's public-domain recordings, with a title search,
  and chapters as the rows they are, each playable on its own.
- **Quality badge** — tapping the Lossless/Hi-Res badge opens a card with the
  source, the bitrate, the file format and the resolution behind the claim.
- **JamesDSP** — renamed, with James Fung credited.

## Gaps against upstream v1.9

Kept out of this cycle, each with the reason:

| Upstream has | We do not | Why |
| --- | --- | --- |
| Loop Mix (a DJ-style remix view) | — | A large feature of its own; it needs its own transport, waveform and session model. Not started. |
| Onboarding | — | This app signs in through YouTube Music, and the first run already lands on a working feed. A guided tour would be nice; it is not a gap in function. |
| Playlist reordering page | — | The playlist page reads the order YouTube returns; there is no drag-to-reorder. |
| Uploaded tracks | — | The account's own uploads are not surfaced. |
| Offline playlists / playlist sync | — | Downloads are per-track. A synced offline playlist is a different storage model. |
| Remote speaker (cast to another device) | — | Not started. |
| Song info dialog (credits, identifiers) | Partly | The quality badge now opens a card; the fuller song-info sheet is still missing. |
| Signature editor (drawn artist signatures) | — | Not started. |
| Synced lyrics panel | — | Lyrics render in the player; a second synchronised surface is missing. |
| Top Picks shelf | — | Not started. |
| Queue window (bounded, paged queue) | — | The queue is unbounded in the app; upstream bounds it. |
| Cover placeholders, fading edges, continuous corners | — | Small visual polish; the placeholders exist in a simpler form. |
| Wear OS / watch app | — | Out of scope for an Android phone build. |
| Desktop and iOS targets | — | Out of scope; this cycle is the Android app. |

Third-party streaming sources are excluded by policy and are not part of this
audit.

## Known risks

- **Audiobook playback** is a new path: a chapter is a plain HTTPS file
  played straight from the archive, with no YouTube resolver behind it. The
  quality badge, the scrobblers and the download button have nothing to say
  about such a track, and are silent rather than wrong.
- **Podcasts** depend on YouTube Music's own feed shape. The episode parser
  is written defensively (a missing renderer means an empty list, not a
  crash), but the feed is not a documented API.
- **The Podcasts tab replaced the Explore tab.** Moods and genres moved to a
  page opened from the Radio tab; the old top-level entry point is gone.
- **The Arabic and Urdu faces are subsetted** to the glyphs those scripts
  need, so a font picker that offers a custom file is the way to get a wider
  one.
- **Locale strings tests fail on the pre-existing fifteen-locale set**, and
  the live Genius test fails against the network. Neither is new.

## Next candidates, in order of value

1. Play a whole book without stopping — resume position per book, and a
   "continue listening" shelf.
2. A fuller song-info sheet behind the badge, with credits and identifiers.
3. Playlist reordering.
4. An onboarding pass for a first run.
5. Loop Mix, if a DJ-style view is wanted enough to build its transport.
