# Auris vs upstream BitChord v1.9 — saved sub-agent findings

Work dir: `/home/ubuntu/AurisResearch` (public app, com.music.bitchord, branch `main`).
Upstream clone: `/home/ubuntu/BitChord` (tag `v1.9` = 04f64c9f; `origin/main` = b690dc14).
Our private add-on build stays in `/home/ubuntu/Auris-backup` (out of scope now).

## Objective (owner)
- Keep OUR UI. Upstream 1.9 UI rejected.
- Goal: app as fast as possible. Port upstream ENGINE/PERF work only.
- Public app: zero "Qobuz" / add-on mentions in code we touch.
- No public release. Test artifact only.
- Planned additions: Explore tab → Podcasts page, remove our top-left logo on Home,
  Lossless/Hi-Res badge → morph popup (Source / Bitrate / File format),
  bit-perfect → volume slider must cut audio directly (mixer bypass on some phones),
  updated WebView for Spotify.

## Repo topology
- Our `main` has 50 own commits, NO shared history with upstream (tree re-rooted via web uploads).
- `git diff main v1.9` = 1350 files (711 A / 373 D / 263 M).
- Upstream v1.9 tree = mostly upstream code; our tree has our UI (Flow, quick picks, replay, glass, classipod, desktopApp).
- DO NOT swap trees. Port selectively.

## Audit 1 — our 50 commits vs v1.9
### DROP (upstream supersedes)
- Apple/SF icon family, ic_output_*, ic_lossless, volume_up vector "dip" crash class — upstream uses SF Symbols lib.
- HeldContextMenu/HeldMenuGlass/SongActionsSheet, NowPlayingScreen/PlayerControls artwork-fade + transport geometry,
  GlassNavBar/foldingtabbar, Convx glass engine, PlayerIcons — upstream rebuilt all (UI: we keep OURS).
- LikedCache (upstream deleted), Classipod (upstream none), OctaveApi (deleted by us already).

### KEEP (ours-only, upstream lacks)
- offline mode (AppSettings.effectiveOffline, OfflineBanner, quick picks, Downloads.snapshotSongs, startup temp purge) — upstream v1.9 has NO offline mode.
- measured bitrate (playback/audio/MeasuredBitrate.kt) — upstream has no real measured impl.
- sing/vocal-removal (SingMethod, VocalCut*) — upstream has none.
- Flow feature (FlowStore, FlowScreen, FlowModels, FlowEngine), SpotifyImporter/SpotifyLibrary,
  ArtworkDownload, LocalMusicScreen, desktopApp/**, classipod/**, branding (LOGO.png, BANNER.png, README, MAINTAINERS, ADDITIONAL.md, DESKTOP.md).

### RE-APPLY (ours, not in upstream) — still valid
- `9d283412` version swap landing paused: MainActivity.kt `switchToMusicOnly` / `switchToVideo` success path
  must `if (resumeAfterResolution) c.play()` after `c.swapToVersion(target)`.
- `5f52848a` lossless-source ordering: SourceKind rank-1 lossless kinds; SourceRegistry.active() must NOT
  filter `awaitsLosslessOutput` sources. (Public tree: keep built-in sources generic, no add-on wording.)
- measured bitrate + SourcesTest `answerAfterMs = 0` flakiness fix.

### Party (listen-together)
- D1 invite link fix (`JamInviteLink.url(code, host)` = server's own /invite/CODE): ALREADY in our tree = upstream. Nothing to do.
- D2 `switchPartyWithRecovery` server fallback: upstream v1.9 has NO fallback → PORT:
  - `ListenTogether.kt`: `var targetJoinResult`, after failure if `isNoSuchParty()` try `fallbackServers(targetBase)`
    (configured server, default server, effective idle server — dedup, exclude target), track `joinedBase`,
    use it for stale-slot check + `activePartyServerBase` + `setCustomServerUrl(joinedNorm)`.
  - helpers: `fallbackServers(targetBase)`, `Throwable.isNoSuchParty() = this is PartyException && code == "no_such_party"`.
  - upstream has PartyException.code, parseAndNormalizeServerUrl, ServerUrlValidationResult, resolveHttpBase.

### Version / signing
- Our public app: versionCode 75, versionName "2.2.13" (app/build.gradle.kts:83-84).
- Upstream v1.9: root build.gradle.kts extras (bitchordVersionCode 35, name "1.9"); upstream/main bumps to 36.
- MUST stay > installed public build (43 per older report — re-check) so update installs.
- signingConfigs identical both trees; keystore.properties/local.properties untracked, keep them.

## Audit 2 — upstream v1.9 perf + stale UI
- No remote config / no web UI / no cached layout in upstream. Compose + prefs only.
- Real stale-UI mechanisms: (a) Top Picks row persisted `filesDir/top_picks_v4.json` (old-build content until hour rolls),
  (b) Coil image cache wiped only when versionCode rises, (c) CanvasCache/RemoteArtworkStore not version-stamped.
- Updater path bug: upstream AppUpdateChecker points at upstream repo + `UPDATE_APK_PREFIX "BitChord-"`, and
  installApk() never checks result code → refused install = silent old UI. Keep OUR repo + our prefix, add result check.
- Upstream perf wins worth porting (non-UI): 2 Hz position poll only while playing+foreground
  (PlayerConnection.kt:417-431), playhead in separate stable object (PlaybackPosition.kt), queue snapshot rebuilt
  only on content change, home feed + playlist card 60 s TTL caches (PlaybackService.kt:431-492),
  AudioCache queue lookahead 1 + 500 ms stagger, Coil memory 20% + disk 100 MB + hardware bitmaps off on emulated GPU,
  ComposeUiFlags.isAdaptiveRefreshRateEnabled=false (BitChordApplication.kt:87-93), performance mode refresh-rate request,
  OptimizedHaze fixed 0.33x sampling / 0.10x live menu, Isolated {} recompose scopes, SnapshotBackdrop 1/4-scale still.
- Post-v1.9 upstream commit: 2d0a26c6 "lag fixes" — MainActivity.kt: drops page `graphicsLayer{Offscreen}` during
  player slide + derivedStateOf for playerSliding. PORT (skip its versionCode bump).

## Owner feedback (latest)
- "1.9 UI sucks" → do NOT adopt upstream UI. Speed only.
- Stale UI bug = OUR Flow row rendering OLD ENLARGED Flow (as playlist) until refresh; we later made Flow small
  + added Quick picks + Replay. Root cause still to be found in our Flow persistence/render decision.
