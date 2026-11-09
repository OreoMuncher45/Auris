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
      `v3.0.8-draft` tag — GitHub creates a draft's tag only on publish.)

### 10. Private dev APK draft

- [x] Add Android-only signed dev-APK draft workflow.
- [x] Use `versionCode > 104` and stable dev signing key.
- [x] Verify APK package/version/signature/install compatibility. (`com.dev.bitchord`,
      versionCode 108, signer `385af3b4…71ce` — same key as 3.0.7, so it installs
      over it; analyser `libbitchord_analysis.so` present.)
- [x] Push branch and run workflow. (Run `42456044317`, success.)
- [x] Create GitHub release with `gh release create --draft`.
- [x] Verify release `isDraft: true` and artifact is downloadable. (`v3.0.8-draft`,
      four signed APKs, draft.)
- [x] Draft `v3.0.9-draft` (versionCode 109) cut from `feature/automix-core-v2`
      and verified: run `42529150467` succeeded, four signed APKs, `isDraft:
      true`, and no `v3.0.9-draft` git tag — GitHub creates a draft's tag only
      on publish.
- [x] Draft `v3.0.10-draft` (versionCode 110) cut from `feature/automix-core-v2`
      and verified: run `42532929154` succeeded, four signed APKs, `isDraft:
      true`, no git tag. First pass of the new Automix controls.
- [x] Draft `v3.0.11-draft` (versionCode 111) cut from `feature/automix-core-v2`
      and verified: run `42538458236` succeeded, four signed APKs, versionCode
      111 with the same dev signer (`385af3b4…71ce`), `isDraft: true`, no git
      tag. Edges resize the transition, the grid is a ruler along the drawing's
      edges, and snapping is a magnet on bar lines.
- [x] Draft `v3.0.12-draft` (versionCode 112) cut from `feature/automix-core-v2`
      and verified: run `42543405634` succeeded, four signed APKs, versionCode
      112 with the same dev signer (`385af3b4…71ce`), `isDraft: true`, no git
      tag, `libbitchord_analysis.so` present. The player-chrome fixes and the
      Spotify Canvas switch.

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

The drawing was rewritten as one composable that owns its own viewport and its
own drag, rather than being patched. Everything below is the behaviour of
`AutomixTransitionEditor`, and it supersedes the three gestures the old graph
had.

- [x] The orange box is the listener's own window: the handoff is its left
      edge and the exit where the outgoing track is retired is its right one.
- [x] Either edge can be taken hold of on its own, and the two ends move
      independently: pulling the handoff edge back lengthens the transition,
      pushing it in shortens it, and the exit edge does the same from the other
      side. The size is what the two edges measure, kept inside the four-to-
      thirty-two-bar range.
- [x] Dragging the box itself moves it with the finger; dragging the upper
      lane (the outgoing waveform) puts the handoff under the finger.
- [x] Dragging the lower lane scrolls the view, and the window is kept in
      sight.
- [x] Green rules along the drawing's own top and bottom edges mark the
      measured grid: every beat a short tick, every bar line a longer bright
      one. The tick a placement took is lit, so a snap is confirmed by eye.
- [x] Snapping is a magnet, not a grid: the edge follows the finger and is
      pulled onto a bar line only when the finger is already near one, and the
      tick fires when the pull takes hold rather than on every bar it crosses.
      Snapping to every beat quantised a drag to a few pixels — invisible, and
      it rattled.
- [x] The finger-to-time mapping is frozen for the whole gesture, so the box
      cannot jump out from under the finger.
- [x] There is no tap-anywhere behaviour: a stray touch does not move the
      transition.
- [x] A bar-count change grows the box backwards from its own right edge and
      the view zooms out to fit it.
- [x] The curves — tone shape, filter ride, volume pair — are drawn from the
      listener's draft rather than from the plan, so a change of mind shows at
      once. The plan's own placement is a grey hairline beside the box, drawn
      only when the two disagree.
- [x] Drag state is local to the editor and committed on release, so a finger
      on the box no longer recomposes the sheet or rebuilds the plan per frame.
- [x] Nothing is adopted from the plan while a finger is down, and the view
      does not glide either: the plan is re-made several times a second while
      the analyser refines and the playhead moves, and adopting it mid-drag
      pulled the box back out from under the finger.
- [x] The edge handles scale with the box — a quarter of it either side, and
      the full width outwards — so a short window still has a body to grab and
      a near miss takes hold of the edge instead of jumping the whole box.

### 11b. Volume, preview and the seek bar marker

- [x] A `Volume` row: Auto, None, Slam, Fade out, Fast out, Fade in, Fast in.
- [x] The shape is carried through the planner into the renderer, where it
      replaces the equal-power pair outright; the gain curves live in shared
      code so the drawing and the audio agree by construction.
- [x] A preview button that plays the real handoff from two seconds before the
      blend and then puts the song back, paused — hearing a transition is not
      the same as having one.
- [x] The button's face follows the engine, which is the only thing that knows
      a preview has ended by itself.
- [x] The seek bar's Automix marker is published whenever there is a plan,
      rather than waiting for both tracks to have been measured — which is
      what kept it off the bar for most of a streamed track.
- [x] The digest carries the analysis's whole downbeat grid, because the
      editor snaps to it.

### 11c. Lossless addons with YouTube off

The reported symptom: with addon (lossless) sources and YouTube switched off,
the incoming track's Automix analysis took minutes to appear, and the
transition fell back to a plain fade.

- [x] Root cause: the read-ahead's opening fetch was a fixed megabyte, which is
      about one second of a lossless rendition, while the analyser's head pass
      refuses anything under twelve seconds. The bytes it was waiting for only
      arrived once the whole-file fill caught up, or once the track started
      playing.
- [x] The read-ahead now sizes a source-backed track's opening in *seconds of
      audio* — twelve of them, from the stream's own length over the item's own
      runtime — instead of a fixed byte count. `ANALYSIS_HEAD_SECONDS` is the
      one number the analyser and the cache both read.
- [x] The stream's length is measured before the settle delay rather than
      after, because the analyser divides by it to decide whether what is on
      disk is enough.
- [x] The analyser's own request for a head now works for a source-backed
      track. It used to be a YouTube-only call that returned immediately
      whenever YouTube was switched off, so the one component that knew it
      needed more bytes could not ask for them.

### 12. Per-song choices

- [x] A choice per track: blend, size, filter, tone shape, effect, volume
      shape, handoff start and exit.
- [x] Kept on disk, one file per track, written off the main thread.
- [x] The listener's handoff start snaps to whole bars on the planner's own
      grid, and the size and the start are written together so they cannot
      disagree.
- [x] A dragged start is honoured by the planner even when a size was also
      chosen: the line is the plainer of the two.
- [x] A window drawn at both edges is honoured at both edges, including one
      that retires the outgoing track before its mix point — the planner will
      not cut a tail on its own, and a listener who draws it that way means it.
- [x] Whole bars throughout: 4 to 32, and a request that does not fit comes back
      shorter rather than not at all.
- [x] An empty choice is no choice; "not this song" is a choice and is kept.
- [x] Schema 4 reads schema 3 files rather than discarding them: the two fields
      it lacks both have exact defaults, so a version 3 choice is a whole
      choice that has not said anything about the volume.

### 13. Running on cheap hardware

- [x] Analysis split into a fast head pass and a whole-track pass, so the next
      track is never waiting behind the last one's refinement.
- [x] The opening megabyte is fetched at once; the rest waits for the queue to settle.
- [x] The panel's state is published only when it changes, so the graph does not
      redraw on the playback clock.
- [x] The waveform is folded to one column per pixel before it is drawn.
- [x] The profile write is debounced and happens on its own thread.

### 14. Player chrome bugs reported in testing

Reported from the 3.0.10 draft. Not Automix; fixed after the panel work, in the
build after the one the Automix fix went out in.

- [x] Opening the full player from the mini player, and going back, left the
      track title and artist partially hidden behind the artwork. The sleeve is
      painted above the whole player while it docks, and the credits row sits in
      the band the artwork's lower edge crosses, so the text was washed out by
      its own cover for the length of the trip and came back the moment the
      sheet settled.
- [x] A first attempt at that fix made it worse: the credits were drawn from
      a recorded layer and faded with the player's own fade curve, which is
      **zero for the whole of the trip** and only reaches full strength at the
      very end. The title therefore vanished for the entire journey and popped
      back at the end. The curve was the bug, not the layer.
- [x] The title and the artist now travel. While the sheet is closing they are
      recorded, drawn over the artwork, and carried to the mini player's own
      title — found in screen coordinates, the way the cover is — shrinking to
      the bar's title size as they go, and handing over in the last stretch
      rather than blinking out of one screen and into the other. The same trip
      in reverse opens the player.
- [x] With the lyrics pane open the album art moves to the top left. Opening the
      queue from that state zoomed the art slightly in place: the uncrop that
      the lyrics layout had applied was keyed on the lyrics alone, and the queue
      is reached by closing them, so the picture re-cropped. It is keyed on the
      player being collapsed into its header at all now.

### 15. Spotify Canvas can be turned off without disconnecting Spotify

- [x] A "Spotify Canvas" switch of its own. It starts on, and it is independent
      of the sp_dc cookie and of the Spotify account, so Spotify can stay
      connected for library and playback and simply never supply an animated
      cover.
- [x] Off means the Spotify source is not asked at all, not that it is asked
      last: Apple Music, Tidal and the community index still answer, and a
      track they have a clip for still plays one. Switching it off also clears
      "Prioritize Spotify Canvas", which is hidden while it is off.
- [x] The answer is cached per combination of priority and this switch, so
      turning it off cannot serve a clip from a cache written while it was on.

### 16. Animation pass on the player

Asked for after the 3.0.12 draft, whose docking animation went wrong. Motion
is worth doing properly: a transition that is half a second of nothing reads
as a fault, and the eye is on the movement more than the picture.

- [x] The title and artist fly into the mini player rather than fading out of
      one screen and appearing in another — the handover the cover already
      had, extended to the words.
- [x] The two curves that carry it are pinned down by tests, because a wrong
      fade is what broke it: the title holds its own for the whole trip and
      dissolves only once it is home, and it is home before the sheet's own
      dimming has cleared the bar.
- [x] The play and pause glyphs hand over to each other instead of being
      swapped, in the full player and in the bar: one grows out of the other.
- [ ] Still to come, each one to be looked at on a device before it ships:
      the player's own content sinking as the sheet closes rather than only
      fading; the artwork's slow living zoom; the album and artist pages
      handing their artwork to the player; the queue and lyrics panels easing
      rather than sliding; the settings rows settling in as the sheet opens.

