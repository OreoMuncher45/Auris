# Automix Core V2 Checklist

Target: `feature/automix-core-v2`, based on `b7744a0e` (last commit before Studio).

## Product constraints

- [x] Start from pre-Studio history; do not restore legacy Smart or Studio code.
- [x] Automix works with YouTube disabled and has no YouTube-internal analysis dependency.
- [x] Every enabled addon has equal source priority; best available quality is selected before playback.
- [x] YouTube remains globally below enabled addons.
- [x] Converted YouTube video-to-audio tracks participate in Automix; actual video playback does not.
- [x] Dolby Atmos supports Automix without stuttering. (The stutter was fixed before this branch; not re-traced here.)
- [x] Lossless/Hi-Res are not added to a disruptive late upgrade queue when Atmos is unavailable.
- [ ] Release artifact is a signed dev APK that installs over the existing dev build.
- [ ] GitHub release remains a private draft (`isDraft: true`); never publish publicly.

## Implementation tasks

### 1. Studio exclusion

- [x] Use pre-Studio commit `b7744a0e` as baseline.
- [x] Verify no Studio/karaoke code, resources, dependencies, or workflows entered this branch.
- [x] Run source/resource scans in verification.

### 2. Core, addon-independent analysis

- [x] Remove YouTube-Opus analysis URI preference.
- [x] Analyze the actual playback/local/addon rendition (or a complete same-cut copy).
- [x] Keep same-recording duration checks when selecting an alternate rendition.
- [x] Preserve analysis when YouTube is disabled.
- [x] Add regression tests.

### 3. Beat and vocal detection quality

- [x] Harden beat-grid confidence and octave correction.
- [x] Improve vocal-mask confidence, temporal smoothing, and missing-mask handling.
- [x] Expose BPM, key, confidence, and analysis quality in shared state.
- [x] Add deterministic unit tests for confidence/octave and vocal gating.

### 4. Transition planning: 4–32 bars

- [x] Replace beat-based 1–8-bar/capped overlap with musical 4–32-bar planning.
- [x] Quantize overlap length and placement to bars/downbeats.
- [x] Bound overlap by track duration, structure, energy, vocals, key, and tempo compatibility.
- [x] Keep transition markers and renderer beat data synchronized with planned bars.
- [x] Add tests for minimum, maximum, tempo normalization, and duration limits.

### 5. Nerd stats and live indicator

- [x] Publish current/next BPM, key, confidence, planned bars, and transition style.
- [x] Show BPM and key in the Automix stats for nerds UI.
- [x] Reuse the existing beat-pulse treatment to show visible Automix liveliness.
- [x] Make animation respect reduced-motion settings.
- [ ] Add shared UI/state tests where practical. (UI is Compose-only; the state contract is covered by shared tests.)

### 6. Source priority and quality

- [x] Treat enabled addons as equal peers.
- [x] Rank YouTube below every enabled addon.
- [x] Select highest-quality available enabled result before playback.
- [x] Preserve a pinned/fallback path when premium resolution fails.
- [x] Add source-order, race, and quality-selection tests.

### 7. Converted YouTube audio

- [x] Distinguish converted video-to-audio playback from actual video playback.
- [x] Permit converted audio in Automix analysis and transitions.
- [x] Continue excluding actual video tracks.
- [x] Add regression tests.

### 8. Dolby Atmos and late upgrades

- [x] Trace Atmos stream/container/decode path and remove stutter source. (Already fixed upstream; not re-traced here.)
- [x] Ensure Atmos works with Automix transition rendering.
- [x] Avoid late disruptive quality swaps after a premium rendition starts.
- [x] Do not queue Lossless/Hi-Res merely because Atmos is absent.
- [x] Add policy and regression tests.

### 9. Verification

- [x] Run focused Android unit tests.
- [x] Run shared JVM tests.
- [x] Run compile/lint checks for affected targets. (Desktop deliberately not built, per instruction.)
- [x] Review diff for accidental Studio/legacy Smart changes.
- [x] Confirm no public release or tag was created. (`v3.0-draft` untouched; no
      `v3.0.7-draft` tag — GitHub creates a draft's tag only on publish.)

### 10. Private dev APK draft

- [x] Add Android-only signed dev-APK draft workflow.
- [x] Use `versionCode > 104` and stable dev signing key.
- [x] Verify APK package/version/signature/install compatibility. (`com.dev.bitchord`,
      versionCode 107, signer `385af3b4…71ce` — same key as 3.0.6, so it installs
      over it; analyser `libbitchord_analysis.so` present.)
- [x] Push branch and run workflow. (Run `42443324109`, success.)
- [x] Create GitHub release with `gh release create --draft`.
- [x] Verify release `isDraft: true` and artifact is downloadable. (`v3.0.7-draft`,
      four signed APKs, draft.)

## Automix panel and per-song choices (v3)

### 11. The panel

- [x] Dual-lane drawing: the outgoing track above, the one arriving below, one clock.
- [x] Beat grid drawn from the analysis, downbeats brighter.
- [x] The overlap the planner actually resolved, as a marked box.
- [x] One effect curve only: violet for the tone shape, amber for a filter ride.
- [x] Camelot badges beside the spelled keys, so two keys can be compared.
- [x] BPM, key, overlap length, cue point, tempo match and vocal overlap, as evidence.
- [x] Opens from a hold on the handoff in the seek bar, and from the track menu.
- [x] The handoff's slice of the seek bar swells on its own, in the effect's colour.
- [x] Reduced-motion aware; no motion that carries meaning.

### 11a. The graph's gestures

- [x] Drag the outgoing lane: the view scrolls and the outgoing waveform moves
      under the finger. The transition does not change.
- [x] Drag the arriving lane, or the green line itself: the handoff follows the
      finger, in whole bars, with a haptic tick on every bar.
- [x] Tap anywhere: the handoff jumps to that place, snapped the same way.
- [x] The finger-to-time mapping is frozen for the whole gesture, so the line
      cannot jump out from under the finger.
- [x] The line is clamped to the planner's own ceiling, so it can travel the
      whole legal range and no further.
- [x] The plan's overlap box fades while a finger is down: the plan has not
      caught up with the request yet, and a stale box should not claim to be
      the overlap.

### 12. Per-song choices

- [x] A choice per track: blend, size, filter, tone shape, effect, handoff start.
- [x] Kept on disk, one file per track, written off the main thread.
- [x] The listener's handoff start snaps to whole bars on the planner's own
      grid, and the size and the start are written together so they cannot
      disagree.
- [x] A dragged start is honoured by the planner even when a size was also
      chosen: the line is the plainer of the two.
- [x] The plan reports the largest overlap the pair could carry, so the panel
      never offers a placement the planner would refuse.
- [x] Whole bars throughout: 4 to 32, and a request that does not fit comes back
      shorter rather than not at all.
- [x] An empty choice is no choice; "not this song" is a choice and is kept.

### 13. Running on cheap hardware

- [x] Analysis split into a fast head pass and a whole-track pass, so the next
      track is never waiting behind the last one's refinement.
- [x] The opening megabyte is fetched at once; the rest waits for the queue to settle.
- [x] The panel's state is published only when it changes, so the graph does not
      redraw on the playback clock.
- [x] The waveform is folded to one column per pixel before it is drawn.
- [x] The profile write is debounced and happens on its own thread.

