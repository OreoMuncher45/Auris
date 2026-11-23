This is a test branch that only combines 3.3 unified with 3.4.

# Changelog — 3.3 unified + 3.4 (combined test branch)

Every Vassista change already merged in the 3.3 / 3.4 lines, plus the still-open
Vassista PR stack carried on top. Generated from the combined branch; not a release.

## This build — the 3.4 test draft

- **Lyrics sharing, back to the 3.4 sheet.** The share is the record-coloured
  card and video it was before the BitChord port: the card is set in the song's
  own ink over the record's colour mesh, the sheet is in the player's dark
  material (its own white-alpha segments and switches), and the card carries the
  **Auris** mark — not the port's theme-coloured controls and BitChord wordmark.
  **Video / Image**, **Convert to card**, the logo switch and the instrumentals
  switch are all as they were.
- **Readable unsung lines.** A coming line sits at a higher alpha than the
  player's panel uses, and the line falloff is flatter, so a shared verse is
  legible a beat before it is sung instead of appearing only as it lights.
- **Nastaliq for Urdu.** An Urdu line — and an Urdu song's title and artist — is
  set in the app's bundled nastaliq (`auris_urdu.ttf`), Arabic in its naskh, so
  the card and the exported video are in the script they are read in rather than
  in a Latin face. The script is detected per line.
- **Includes instrumentals.** A skipped run that really held an instrumental break
  is named rather than collapsed to a bare omission; the switch turns it off again.
- **AutoMix model.** Loop selection now scores each candidate groove on its own
  analysis — the level it drives at and the drum hits in it (frame-to-frame RMS
  transients), gated by the same vocal detection as before — so a drum-led
  stretch is chosen over a later wash of the same loudness. The latest-out /
  earliest-in preference still holds unless a clearly better groove is in reach.
- **The main player plays the music video (BitChord's player, ported).** Taking a
  track to its video puts the picture over the sleeve at the stream's own aspect
  ratio — letterboxed, never stretched to the square — with the album art, the
  animated cover and the Canvas all standing down, the blurred backdrop staying,
  and a quality box and full-screen button over it. It is upstream's
  `MusicVideoEngine` whole: InnerTubeX's video extraction at up to 1080p, an
  age-gated retry, an H.264 step-down, and the quality drawer.
- **The video plays its own audio.** The picture's video-only rendition is merged
  with the track's audio in one ExoPlayer and the app's player goes quiet under
  it, so the sound and the image are one recording and cannot drift apart.
- **Smooth video playback.** The picture buffers well ahead of the playhead and
  jumps to the app's clock only on a drift no ear can hear, so a music video no
  longer re-buffers to close a difference that was never visible.
- **Switching to audio doesn't freeze the app.** The catalogue resolve behind the
  switch runs off the main thread, and the choice **sticks**: asking a music
  video for its audio keeps the tracks after it on their songs, and asking for
  the video keeps the ones after it on their videos.
- **No YouTube history.** Search is asked anonymously and play registration is
  off, so nothing the app searches or plays lands in the listener's YouTube app.

## Added

- Sandboxed extension files so Deezer can decrypt and play ([#74](https://github.com/OreoMuncher45/Auris/pull/74))
- Replace Auris's AutoMix with BitChord's engine and model (stacked on #96) ([#98](https://github.com/OreoMuncher45/Auris/pull/98))
- BitChord v1.10 features (Premium audio, liked downloads, sleep fade, QS tile) ([#99](https://github.com/OreoMuncher45/Auris/pull/99))
- Animated play/pause and skip buttons, Best Part ([#100](https://github.com/OreoMuncher45/Auris/pull/100))
- Download the models on first use, AutoMix screen extras and cover reveal ([#106](https://github.com/OreoMuncher45/Auris/pull/106))
- Song Info credits, queue swipe, undo and multi-select ([#107](https://github.com/OreoMuncher45/Auris/pull/107))
- Live-search swipe, Library filter, playlist search, track-number sort ([#108](https://github.com/OreoMuncher45/Auris/pull/108))
- Strip glow and sweep, glow while picking, sources dialog fix ([#109](https://github.com/OreoMuncher45/Auris/pull/109))
- Your Mixes on the Radio screen ([#110](https://github.com/OreoMuncher45/Auris/pull/110))
- Background follows the Automix cover reveal ([#115](https://github.com/OreoMuncher45/Auris/pull/115))

## Fixed

- The four leftover findings from the #5 splits (#5) ([#17](https://github.com/OreoMuncher45/Auris/pull/17))
- Review fixes for the stream resolver's pins and auditions (#5) ([#18](https://github.com/OreoMuncher45/Auris/pull/18))
- Resolve the CodeRabbit review comments on #11 and #17 (#5) ([#25](https://github.com/OreoMuncher45/Auris/pull/25))
- Four now-playing bugs CodeRabbit found in #14's diff (#5) ([#26](https://github.com/OreoMuncher45/Auris/pull/26))
- Resolver and pin hand-over fixes from review (#5) ([#27](https://github.com/OreoMuncher45/Auris/pull/27))
- Search lossless catalogues without the Eclipsa Audio suffix ([#34](https://github.com/OreoMuncher45/Auris/pull/34))
- Give the quality badge its own tap target under the seek bar ([#35](https://github.com/OreoMuncher45/Auris/pull/35))
- Now Playing ink judged against the backdrop actually drawn ([#36](https://github.com/OreoMuncher45/Auris/pull/36))
- Snap shelves to the leading inset instead of stopping mid-item ([#37](https://github.com/OreoMuncher45/Auris/pull/37))
- Replay and Flow as tiles in Spotify's quick-access grid ([#38](https://github.com/OreoMuncher45/Auris/pull/38))
- Animate the artwork through a track change ([#39](https://github.com/OreoMuncher45/Auris/pull/39))
- Round the cover onto the pill's end cap ([#40](https://github.com/OreoMuncher45/Auris/pull/40))
- Live Liquid Glass under the Now Playing ⋯ menu (UI 08) ([#41](https://github.com/OreoMuncher45/Auris/pull/41))
- Real artwork in the Spotify Replay and Flow tiles (UI 09) ([#42](https://github.com/OreoMuncher45/Auris/pull/42))
- Make the Spotify / YouTube Music catalogue switch easy to find (UI 11) ([#45](https://github.com/OreoMuncher45/Auris/pull/45))
- Keep Settings open over pages that update, and over every page ([#53](https://github.com/OreoMuncher45/Auris/pull/53))
- A finished search leaves the in-flight board before its caller resumes ([#55](https://github.com/OreoMuncher45/Auris/pull/55))
- Pages pushed over Replay, audiobooks and podcasts come up on top; stop root recomposing per publish ([#56](https://github.com/OreoMuncher45/Auris/pull/56))
- Fold the bar on pages opened over Search; stop the root recomposing per bar-height frame ([#64](https://github.com/OreoMuncher45/Auris/pull/64))
- Resolve streams once, download own-catalogue tracks directly, decrypt keyed answers ([#68](https://github.com/OreoMuncher45/Auris/pull/68))
- No black half after rotating ([#69](https://github.com/OreoMuncher45/Auris/pull/69))
- Stop the Discord/Reddit card flashing when Settings opens ([#70](https://github.com/OreoMuncher45/Auris/pull/70))
- Play cached songs whatever key they sit under; say why Automix is off with Bit-perfect ([#71](https://github.com/OreoMuncher45/Auris/pull/71))
- Give the DAC back to Android when bit-perfect closes or fails ([#72](https://github.com/OreoMuncher45/Auris/pull/72))
- SoundCloud stream, Spotify Web search, Jellyfin "*" grant, refused-search advice ([#75](https://github.com/OreoMuncher45/Auris/pull/75))
- Refuse sources whose stream contradicts a lossless claim ([#82](https://github.com/OreoMuncher45/Auris/pull/82))
- Snap the artist page and Replay rows like Home's ([#83](https://github.com/OreoMuncher45/Auris/pull/83))
- Make home track cards playable ([#84](https://github.com/OreoMuncher45/Auris/pull/84))
- Follow the network callbacks, not the lagging system answer ([#85](https://github.com/OreoMuncher45/Auris/pull/85))
- Refuse re-uploads, tribute acts, DJ-mix cuts and fan blends ([#86](https://github.com/OreoMuncher45/Auris/pull/86))
- Light only the playing track, show one recording once ([#87](https://github.com/OreoMuncher45/Auris/pull/87))
- Keep Now Playing dark in dark theme over light artwork ([#88](https://github.com/OreoMuncher45/Auris/pull/88))
- Stream-resample whole-track analysis and degrade on OOM ([#89](https://github.com/OreoMuncher45/Auris/pull/89))
- Relaunch reliably after a catalogue switch on Android 14/15 ([#91](https://github.com/OreoMuncher45/Auris/pull/91))
- Let a Canvas read clearly under its control deck ([#95](https://github.com/OreoMuncher45/Auris/pull/95))
- BitChord v1.10 bug fixes (lyrics, matching, covers, playback, Auto, Replay) ([#96](https://github.com/OreoMuncher45/Auris/pull/96))
- Keep a Canvas's deck on one ink while the clip plays ([#101](https://github.com/OreoMuncher45/Auris/pull/101))

## Performance

- Wait for a read-ahead pin instead of polling for it (#5) ([#9](https://github.com/OreoMuncher45/Auris/pull/9))
- Move two click handlers' disk I/O off the main thread (#5) ([#11](https://github.com/OreoMuncher45/Auris/pull/11))
- Tick the sleep-timer countdown only in Settings (#5, stacked on #12) ([#13](https://github.com/OreoMuncher45/Auris/pull/13))
- Move non-UI work off the main thread ([#28](https://github.com/OreoMuncher45/Auris/pull/28))
- Broader baseline profile and less main-thread work before the first frame ([#29](https://github.com/OreoMuncher45/Auris/pull/29))
- Make live Liquid Glass as cheap as Zenith's ([#30](https://github.com/OreoMuncher45/Auris/pull/30))
- Cut memory, idle work and APK size ([#32](https://github.com/OreoMuncher45/Auris/pull/32))
- Cut recomposition and per-item work in lists, rows and the mini player ([#33](https://github.com/OreoMuncher45/Auris/pull/33))
- Faster home load: kept Spotify tokens and last launch's page on the first frame (UI 10) ([#46](https://github.com/OreoMuncher45/Auris/pull/46))
- Smooth the player ⇄ pill transition ([#92](https://github.com/OreoMuncher45/Auris/pull/92))
- No endless loading skeleton when signed out ([#105](https://github.com/OreoMuncher45/Auris/pull/105))

## Changed

- Split BitChordApp's root shell (252 → 122 lambda args) ([#6](https://github.com/OreoMuncher45/Auris/pull/6))
- Page host, RootNav, method-size check; perf: player state reads (#5) ([#7](https://github.com/OreoMuncher45/Auris/pull/7))
- Split PlaybackService.onCreate and its stream resolver (#5) ([#8](https://github.com/OreoMuncher45/Auris/pull/8))
- Split SettingsScreen by category (#5) ([#10](https://github.com/OreoMuncher45/Auris/pull/10))
- Split BitChordDesktopApp into holders and hosts; perf: playhead recomposition (#5) ([#12](https://github.com/OreoMuncher45/Auris/pull/12))
- Split NowPlayingScreen into artwork, transport, lyrics, queue and output pieces (#5) ([#14](https://github.com/OreoMuncher45/Auris/pull/14))
- One shared anchored menu, Now Playing ⋯ first (UI 12, stage 1) ([#50](https://github.com/OreoMuncher45/Auris/pull/50))
- Song, release and podcast menus hang off their button (UI 12 stage 2) ([#54](https://github.com/OreoMuncher45/Auris/pull/54))

## Other

- Unified mini/full player panel (ported from Zenith) + lighter blur ([#31](https://github.com/OreoMuncher45/Auris/pull/31))
- Canvas: only play a clip whose search hit really is the playing track ([#94](https://github.com/OreoMuncher45/Auris/pull/94))
- Export and import add-ons as a password-protected file ([#97](https://github.com/OreoMuncher45/Auris/pull/97))
- Keep a held nav-bar glass pill on the bar (#104) ([#113](https://github.com/OreoMuncher45/Auris/pull/113))
- Redeem pasted Mornye verification links and explain refusals (#90) ([#114](https://github.com/OreoMuncher45/Auris/pull/114))
- Run Robolectric offline against Gradle-resolved android-all jars ([#117](https://github.com/OreoMuncher45/Auris/pull/117))

## Base integrations already in the 3.3 / 3.4 line

- 3.3 beta: v4 and UI 12 stages 3-6 (#81)
- 3.2 stable fixes merged into the 3.3 beta (#80 content)
- Owner's unified 3.2 fixes: Dolby, attribution, Tidal search
- Remaining Tidal fixes: restore Tidal streams and verification

## Carried on this branch

- #98 — BitChord AutoMix engine and model
- #110 — v1.10 features, queue, search, lyrics, radio; carries #96, #99, #100, #106, #107, #108, #109
- #115 — player background follows the Automix cover reveal
- #117 — Robolectric runs offline against Gradle-resolved android-all jars

## Open issues not confirmed fixed here

- #118 — Local music: per-track artwork on Android 9 (API 28)
- #73 — Downloaded songs do not play, and Automix/Crossfade may not work with Bit-perfect off

## Not in this branch

- #80 — open, targets `release/v3.2-stable-final`
- #103 — opened by Shinutrk1, not Vassista
- #111, #112 — opened by OreoMuncher45, not Vassista
