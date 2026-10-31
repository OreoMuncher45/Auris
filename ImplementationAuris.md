# Auris / BitChord — Implementation Plan

Status: working repo `/home/ubuntu/AurisResearch`, branch `sync/auris-2.3`, origin `https://github.com/OreoMuncher45/Auris.git`. Private artifact only — no GitHub tag, no public release. UI name **Auris**, package IDs `com.dev.bitchord` / `com.music.bitchord`.

**Cave-man mode is ON.** Answers/tasks stay terse and technical. No filler, no fluff. This plan is written so a fresh bot with ZERO context can pick it up and execute.

---

## 1. Environment & build (verified)

- Android SDK at `/home/ubuntu/android-sdk`. `local.properties` exists (gitignored): `sdk.dir=/home/ubuntu/android-sdk`. SDK platforms android-36/37, build-tools 35/36.
- Root project name `BitChord`. Modules `:shared`, `:sharedUi`, `:desktopApp`, `:app`. `:app` only included when SDK present.
- Commands:
  - `./gradlew :app:compileDevDebugKotlin --offline` — compiles app module (~2–5 min). PASSED (verified after folder feature).
  - `./gradlew :app:assembleDevDebug` — APK build.
  - `./gradlew :app:testDevDebugUnitTest` — unit tests.
  - `./gradlew :desktopApp:compileKotlin`.
- No emulator / adb on this host → device validation is the user's job.
- NDK x86_64 clang segfaults under qemu on this aarch64 host (native builds fail locally). APK build is CI job per `.github/workflows/flow-debug-apk.yml`. Unit tests: Robolectric excluded on aarch64 (Conscrypt no linux-aarch64 build).
- Signed prod release keystore fails pre-existing ("Tag number over 30 is not supported") — do not chase release signing here.
- 16 unit tests fail on a clean tree: `GeniusTest` and `LocaleStringsTest` — **baseline, not a regression.** Do not report as regression.

Locale strings:
- `app/src/main/res/values/strings.xml` = 1175 translatable keys; 15 locale dirs (de, es, fr, he, hi, id, it, ja, pl, pt, ru, th, tr, vi, zh). LocaleStringsTest requires every key in all 15 locales. Baseline FAILS (missing 142 keys each on clean tree). When adding NEW app strings, add to base + all 15 locales (or mark translatable="false").
- `sharedUi/src/commonMain/composeResources/values/strings.xml` = 191 keys; no parity test, but all 15 locales already miss 13 keys (podcasts, radio, explore_hint, library_*, podcasts_empty). New sharedUi strings fall back to English — acceptable but document it.

Apostrophes in Android XML strings must be escaped (`track\'s menu`) — caused an aapt2 build failure, already fixed once.

---

## 2. What is DONE (verified compiling)

Branch `sync/auris-2.3`, 41 files changed, +765/−182. One new file: `app/src/main/java/com/music/bitchord/ui/components/FolderPickerSheet.kt`.

- **Navigation fix** `MainActivity.kt`: removed overlapping Settings sub-screen block (~lines 3997–4117), `pageKey` now routes `customization` before `settings`; top-bar title/back and `BackHandler` clear `settingsSubScreen`.
- **Fonts/RTL**: `sharedUi/.../ui/components/ScriptFonts.kt` — `detectScript` + `isRtlText()` (Hebrew, Arabic, Syriac, Arabic Supplement, Thaana, NKo, Samaritan, Mandaic, Arabic Extended-A, presentation forms A/B). `sharedUi/.../ui/player/PlayerLyrics.kt` — RTL lyric right-align.
- **Library segments**: order Playlists, Device, Artists, Albums; scrollable bar; `+` pinned at end with `new_folder` contentDescription.
- **Custom folders** (fully implemented, compiles): `LocalPlaylistStore` (`isFolder`, `createFolder`, `folders`, `addToFolder`, `removeFromFolder`, prefix `sp_folder_`); `FolderPickerSheet` + `RenameLocalCollectionForm`; `SongActionsSheet` `onAddToFolder`/`onRemoveFromFolder`; `BrowseActionsSheet` `LocalCollectionActions` rename/delete rows; `MainActivity` wiring (`localPlaylistItems`/`folderItems`, FolderPickerSheet modal, `LibraryScreen(folders=..., onNewFolder=...)`); `MainViewModel.folder_empty` message in both open paths, `collectSongs` local-playlist branch, `renameLocalCollection`/`closeLocalCollection`; `Downloads.recordIdOf` excludes `sp_folder_`; strings in app catalogue (all 16 files) + sharedUi (`new_folder`, `folders`).
- Verified: `./gradlew :app:compileDevDebugKotlin --offline` → BUILD SUCCESSFUL.

Already implemented but not yet tested on device: RTTL correct opus decoding.

---

## 3. Remaining work (in priority order)

### A. Audiobook search fix + real covers — HIGH (in progress)

**Root cause (empirically verified):** LibriVox `feed/audiobooks/` `title` param is an **exact, case-insensitive, trimmed full-title match** — `.*Crime.*`, `Letters`, `^…$`, `\Q…\E` all 404 `{"error":"Audiobooks could not be found"}`. `genre` works, `author` fails. Catalogue ≈22k books. So app's `.*${escape(q)}.*` regex never matches → user-reported 404. Also default feed lacks `url_iarchive`, so `coverUrl()` fell back to numeric id → archive.org generic logo / 403.

**Verified working replacement:** Internet Archive.
- `GET https://archive.org/advancedsearch.php?q=collection:librivoxaudio AND mediatype:(audio)&fl[]=identifier,title,creator,downloads&rows=50&output=json` → `numFound` ~21901, docs with identifier/title/creator/downloads. Search: `q=collection:librivoxaudio AND title:(sherlock holmes)` works; `title:(…)` is phrase-ish fuzzy. Use `title:(escaped words)` not regex.
- `GET https://archive.org/metadata/{identifier}` → metadata (`identifier,title,creator,description,date,language,subject,collection,runtime`) + files list. Chapters = files where `source=original`, `format` contains `VBR MP3`, name ends `.mp3`, no `original` field (excludes `_64kb.mp3` derivatives). Fields: `name` (URL path), `title` ("01 - A Scandal in Bohemia"), `track` ("1/12"), `length` (seconds, numeric or "65:06"), `size`, `artist`.
- Covers: `https://archive.org/services/img/{identifier}` → real JPEG cover (verified 180×180 for Sherlock items). `https://archive.org/metadata/{identifier}` also lists `{Title}_NNNN.jpg` / `__ia_thumb.jpg`.
- iTunes Search API reachable, key-less: `https://itunes.apple.com/search?term=…&media=podcast&limit=2` → 200, artwork URLs `artworkUrl100/600`, `feedUrl`.

**Implementation plan:**
1. Replace `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/audiobook/LibriVox.kt` (182 lines) with an Internet Archive client: `books(limit, offset, title, genre)` using `advancedsearch.php`; `chapters(bookId)` using `metadata/{identifier}` filtering original VBR MP3s.
2. Cover URL → `https://archive.org/services/img/{identifier}` (real cover, not logo). Keep `Audiobook.coverUrl` / `AudiobookChapter.coverUrl` fields (already exist).
3. Wire through existing `MainViewModel.loadAudiobooks`/`openAudiobook`/`reloadAudiobookChapters` (already exist at `MainViewModel.kt:1671,1684,1697`) and existing screens `AudiobooksScreen.kt` (175 lines) / `AudiobookScreen.kt` (172 lines) — screens already render `coverUrl` via `AsyncImage`. Minimal UI changes needed.
4. Keep query-empty = whole catalogue. Keep `sectionCount`, `language`, `genres` fields.
5. Add strings (base + 15 app locales): `audiobook_search_hint`, `audiobooks_empty`, `audiobook_chapter_count`, etc. already exist.
6. Compile `:app:compileDevDebugKotlin --offline`. Device test by user (no emulator).

**Progress for audiobooks:** "remaining" = totalSeconds − played seconds. Track played position per bookId in a local store (SharedPreferences keyed `audiobook_progress:{bookId}` → seconds played). Show `X of Y chapters · Z remaining` on book row/chapter screen. Resume chapter on reopen.

### B. Audio pipeline — Dolby Atmos, bit-perfect, resampling — HIGH

User reports:
1. Dolby Atmos (E-AC-3 JOC → 5.1 PCM, 6 channels) plays weirdly; JamesDSP doesn't work on it; Spatial audio doesn't work on it; **"dolby is just playing and not sending audio to dac tok"** → multichannel never reaches the USB DAC.
2. Audio resampling randomly gets enabled even with bit-perfect output on; **"downsampling error is global"** (all tracks, not just Atmos).
3. **"Bit-Perfect needs to stop dpinf that"** → bit-perfect must prevent the resampling/downsampling.

**Verified root causes in code:**

| # | File:line | Condition | Effect |
|---|-----------|-----------|--------|
| 1 | `PrecisionAudioSink.kt:374` | `format.channelCount !in 1..2` → `publishExclusiveUnavailable("This track has N channels; the Android mixer stays in the path"); return false` | 5.1 Atmos never claims exclusive USB → audio stays on Android mixer, never reaches DAC |
| 2 | `ExclusiveUsbOutput.kt:307` | `format.channelCount !in 1..2` → return false | Second gate; same effect |
| 3 | `PrecisionAudioSink.kt:220` | `format.channelCount in 1..2` required to keep exclusive session alive across configure | Session torn down on multichannel |
| 4 | `PrecisionAudioSink.kt:483` (`maybeAdoptExclusive`) | `channelCount !in 1..2` → return | Mid-track adoption blocked |
| 5 | `PrecisionAudioSink.kt:982` (`getFormatSupport`) | `channelCount in 1..2` required for direct support | No direct path for multichannel |
| 6 | `BitPerfectMixer.kt:101` | `channelCount !in 1..2` → State(false,"Multichannel track") | Platform bit-perfect mixer refused for multichannel (framework limit; honest) |
| 7 | `JamesDspProcessor.kt:137` | `block.channelCount !in 1..2` → return (bypass) | JamesDSP silent on Atmos |
| 8 | `JamesDspProcessor.kt:117` | scratch only allocated for 1..2 channels | |
| 9 | `SpatialAudioProcessor.kt` | `process` returns if `block.channelCount != 2`; `onConfigure` requires PCM_16BIT && 2ch | Spatial silent on Atmos |
| 10 | Media3 DefaultAudioSink 1.11 | NO internal ResamplingAudioProcessor class (verified absent in `media3-common`/`media3-exoplayer` aars); SonicAudioProcessor only for speed changes | App itself has no resampler; downsampling = Android AudioFlinger mixer resampling AudioTrack to device native rate (global) |

**Fixes:**
1. **Dolby → DAC:** Allow multichannel over the exclusive USB path. `UsbAudioDevice.findAltSetting(bits, rateHz, channelCount)` (`audio/decent-usb-audio-driver/.../UsbAudioDevice.kt:689`) ALREADY supports channel-count matching via `parsedAltChannels` — driver supports it; only the code gates block it. Change `channelCount !in 1..2` → `channelCount !in 1..MAX_CHANNELS` (MAX_CHANNELS=8 at `PrecisionAudioSink.kt:1194`) in `ExclusiveUsbOutput.configure` (307), `startExclusiveIfWanted` (374), `configure` reset gate (220), `maybeAdoptExclusive` (483), `getFormatSupport` (982). If the DAC offers no alt for that channel count, `findAltSetting` returns null → fail closed, fall back to AudioTrack with honest detail. Update `BitPerfectMixer` multichannel refusal? No — framework genuinely can't do bit-perfect mixer for multichannel (platform limit); leave as honest refusal.
2. **JamesDSP on multichannel:** `JamesDspProcessor.process` currently bails for `channelCount !in 1..2`. Change to process each channel pair (or front L/R pair only) through the stereo engine, leaving remaining channels untouched. Reuse one native engine handle per block over pairs (reverb tail carries — acceptable), or create per-pair engines. Do NOT downmix.
3. **Spatial on multichannel:** `SpatialAudioProcessor.process` bails if `block.channelCount != 2`. Apply widening to front L/R pair (channels 0,1) only, leave surround channels untouched. Document behaviour.
4. **Stop global downsampling under bit-perfect:** The app has no resampler; the system mixer resamples to device native rate when AudioTrack rate ≠ device rate. To keep bit-perfect honest: when bit-perfect is on, prefer exclusive USB (DAC clock must equal source rate — already enforced at `ExclusiveUsbOutput.kt:593-602`, "DAC clock is N Hz, track needs M Hz (no resampling in bit-perfect)"). When exclusive USB unavailable, the system mixer resamples — the readout should say so honestly (`AudioPipelineDialog` System row already does at line 546-562; the old "Resampled 44.1→96" false attribution at 557-558 was already corrected). Verify `outputStatus.actualSampleRateHz` (= AudioTrack rate from `onAudioTrackInitialized`, `PlaybackService.kt:1292`) vs source rate; if they differ on a bit-perfect request, publish an honest mixer note rather than claiming passthrough.

**Compile check:** `:app:compileDevDebugKotlin --offline`. Device test by user.

### C. RTL layout for Urdu songs — HIGH

Already done for lyrics (ScriptFonts.isRtlText + PlayerLyrics right-align). Remaining: full RTL layout for Urdu songs — now-playing title/artist and anywhere Urdu song titles render (`SongText.kt` uses `.forText(song.title)` — already script-aware font). Check: `PlayerLyrics.kt` RTL applied to duet lines. For titles, Compose `Text` needs `textDirection = TextDirection.Content` or `LocaleLayoutDirection` when `isRtlText`. Add RTL text alignment for Urdu/Arabic song titles wherever `SongText`/`ExplicitSongTitle` render titles, gated on `isRtlText(title)`. Fonts already shipped: `auris_arabic.ttf` (Tafkir Arabic TRIAL Medium), `auris_urdu.ttf` (Jameel Noori Nastaleeq).

### D. Icons — replace with premium Apple set — HIGH

**Requirement:** "replace our svgs, all of them with extremely premium icons from somewhere online. Preferably Apple. Airplay and Lyrics icon MUST STRICTLY BE APPLE'S."

**Verified reachable source:** SF Symbols SVGs at `https://raw.githubusercontent.com/brendanballon/sfsymbols-svg/master/symbols/<name>.svg` — all needed glyphs HTTP 200:
- `airplay.audio.svg` (AirPlay), `quote.bubble.svg` (Lyrics), `play.fill.svg`, `pause.fill.svg`, `backward.fill.svg`, `forward.fill.svg`, `star.fill.svg`, `ellipsis.svg`, `speaker.wave.1/2/3.fill.svg`, `list.bullet.svg` — all 200.

**Conversion:** SVG pathData (`M,L,H,V,C,S,Q,T,A,Z`) maps 1:1 to Android VectorDrawable `android:pathData`. SF Symbols SVGs carry `transform='scale(1,-1) translate(0,-H)'` → wrap in `<group android:scaleX="1" android:scaleY="-1" android:translateY="H"/>`. Write a small Python script (cairosvg not needed; parse XML + emit XML) to convert each SVG → `res/drawable/<name>.xml`. viewportWidth/Height from svg width/height attributes.

**Icon map (one-file swap via PlayerIcons):**
- `PlayerAirPlay` → `airplay.audio.svg` (Apple's AirPlay mark) — **strictly Apple's.**
- `PlayerLyrics` → `quote.bubble.svg` (Apple's lyrics mark) — **strictly Apple's.**
- `PlayerPlay`/`PlayerPause` → `play.fill.svg`/`pause.fill.svg`.
- Skip next/prev → `forward.fill.svg`/`backward.fill.svg` (replaces `apple_skip_next.xml`/`apple_skip_previous.xml` drawables).
- Queue → `list.bullet.svg`.
- Volume low/high/muted → `speaker.wave.1/2/3.fill.svg`.
- Star liked/unliked → `star.fill.svg`.
- More/ellipsis → `ellipsis.svg`.
- Transport also in `sharedUi/.../ui/icons/BitChordIcons.kt` (hand-written ImageVectors: `PlayerAirPlay`, `PlayerLyrics`, `PlayerPlay`, …) — replace with the converted vectors.

**License caveat (from docs/APPLE_UI_PORT_PLAN.md):** SF Symbols licence permits use in applications built for Apple platforms, not redistribution inside an Android APK. User explicitly demanded Apple's icons and is aware. **Do not ship SF Symbols in the APK until the user confirms the licence is acceptable.** Alternative if not acceptable: Apple-published AirPlay logo (Apple permits third-party AirPlay logo use in apps that support AirPlay) + a non-SF Symbols lyrics glyph.

**Files:** app `app/src/main/res/drawable/*.xml` (40 files), sharedUi `sharedUi/src/commonMain/composeResources/drawable/*.xml` (~26), 4 SVGs (`desktopApp/src/main/composeResources/drawable/logo.svg`, `logo_mark.svg`, `app/src/main/assets/Logo.svg`, `LogoTransparent.svg`) — SVGs are logos, low priority.

### E. Podcast recommendations — MEDIUM ("I don't care if it requires an external API")

Current `PodcastsScreen.kt` shows only saved shows (`YtMusicRepository.podcastShows()` → `FEmusic_library_non_music_audio_list`). `MainViewModel` already has `Feed.PODCASTS`, `loadPodcasts()`/`fetchPodcasts()`, `ensurePodcastsLoaded()` (MainViewModel.kt:1618-1710).

**Plan:** Add recommendations section to podcast page via iTunes Search API (`https://itunes.apple.com/search?term=…&media=podcast&entity=podcast&limit=20&country=US`) → artwork `artworkUrl100/600`, `feedUrl`, `collectionName`, `artistName`, `primaryGenreName`, `trackCount`. Map to a lightweight recommendation model (or reuse `ShelfItem`). Show a horizontal "Recommended shows" row above the saved-shows grid. Clicking opens the show (RSS feed parse for episodes → stream MP3 enclosures via the player). Feed URL parsing is new work; alternatively recommend and let user open in Apple Podcasts/web. Keep scope minimal: row + artwork + title + artist, no deep episode playback unless simple RSS enclosure streaming is feasible.

### F. Read/listened progress for Audiobooks and Podcasts — MEDIUM

- Audiobooks: per bookId store seconds played (SharedPreferences `audiobook_progress:{bookId}`). Show `chapter X of Y · Z remaining` on book/chapter screens; resume at last position.
- Podcasts: per show/episode store played seconds. Show "remaining" on episode rows.

### G. Bulk-download ANR — HIGH

`Downloads.enqueueAll` (`Downloads.kt:369`) runs on caller's thread (MainActivity:2246/2423), copies into `pending` map under lock then `DownloadSession.queuedAll` (`DownloadSession.kt:172`) mutates an immutable `List<Item>` per song → O(n²) copies on main thread. Hundreds of tracks = ANR.

**Fix:** make `queuedAll` batch-insert in one `update` copy (build the new list once, not per-song), and move `enqueueAll` acceptance off the main thread (dispatch to a coroutine/Worker). Keep `Downloads` API call-sites unchanged except switching to async.

### H. Verification items — MEDIUM

- 90% bar opacity (`PlayerControls.kt:298 BAR_OPACITY = 0.9f`) — verify visually on device.
- Quality badge morph (`SongText.kt` `qualityBadge`, `DetailScreen.kt:1853/1913`) — verify LOSSLESS chip renders correctly.
- Page transitions (`NavTransitionFreeze`, `BackdropFreeze`, `LocalAppBackdropFrozen` exist but never provided — drive during sheet swipe per docs) — glass perf on low-end.
- Glass perf: `GlassPool.kt` pools backdrop layers; shader warmup at startup not yet done (docs/APPLE_UI_PORT_PLAN.md "SpotiFLAC glass study" section).

### I. Build APK + unit tests + private artifact — HIGH

- Run `:app:assembleDevDebug`. Run `:app:testDevDebugUnitTest` (baseline 16 failures expected — GeniusTest, LocaleStringsTest).
- CI workflow `.github/workflows/flow-debug-apk.yml` builds minified signed dev APK and uploads artifact `bitchord-next-release`. Requires `ANDROID_KEYSTORE_B64` secret (not available locally).
- Local APK: `./gradlew :app:assembleDevDebug` → `app/build/outputs/apk/dev/debug/`. Deliver that APK privately to user. Do NOT tag or create GitHub Release.

---

## 4. Suggested execution order

1. Audiobook archive.org client (A) — blocks nothing else, clear win.
2. Audio pipeline Dolby/bit-perfect fixes (B) — user-flagged regression, high visibility.
3. RTL Urdu full layout (C).
4. Icons swap (D) — after user confirms SF Symbols licence, else use Apple-published alternatives.
5. Bulk-download ANR fix (G).
6. Podcast recommendations (E) + read/listened progress (F).
7. Verification items (H).
8. Build APK + unit tests + deliver private artifact (I).

---

## 5. Key file inventory

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/audiobook/LibriVox.kt` — to be replaced with archive.org client.
- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt` — `loadAudiobooks` (1671), `openAudiobook` (1684), `reloadAudiobookChapters` (1697), `loadPodcasts`/`fetchPodcasts` (~1618-1710), `Feed.PODCASTS`.
- `app/src/main/java/com/music/bitchord/ui/screens/AudiobooksScreen.kt`, `AudiobookScreen.kt` — existing audiobook UI, already render `coverUrl`.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/PodcastsScreen.kt` — podcast UI, add recommendations row here.
- `app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt` — multichannel gates at 220/374/483/982, MAX_CHANNELS=8 at 1194.
- `app/src/main/java/com/music/bitchord/playback/usb/ExclusiveUsbOutput.kt` — multichannel gate at 307, clock/resample enforcement 593-602.
- `app/src/main/java/com/music/bitchord/playback/audio/JamesDspProcessor.kt` — multichannel bail at 137, scratch at 117.
- `app/src/main/java/com/music/bitchord/playback/SpatialAudioProcessor.kt` — stereo-only at `process` + `onConfigure`.
- `app/src/main/java/com/music/bitchord/playback/audio/BitPerfectMixer.kt` — multichannel refusal at 101.
- `app/src/main/java/com/music/bitchord/download/Downloads.kt` — `enqueueAll` at 369 (main-thread O(n²) ANR).
- `app/src/main/java/com/music/bitchord/download/DownloadSession.kt` — `queuedAll` at 172 (per-song list copy).
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerIcons.kt` — one-file icon indirection map (roles: Play, Pause, SkipNext, SkipPrevious, VolumeLow/High/Muted, Queue, Lyrics, More, Liked/LikedOff, AirPlay, Shuffle, Repeat, Autoplay, Output, Party).
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/BitChordIcons.kt` — hand-written ImageVectors to replace.
- `app/src/main/res/drawable/*.xml` (40), `sharedUi/src/commonMain/composeResources/drawable/*.xml` (~26) — icon XML targets.
- `app/src/test/java/com/music/bitchord/LocaleStringsTest.kt` — app string parity test (baseline fails).
