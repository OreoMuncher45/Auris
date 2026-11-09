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
- [x] Draft `v3.0.13-draft` (versionCode 113) cut from `feature/automix-core-v2`
      and verified: run `42551054738` succeeded, four signed APKs, versionCode
      113 with the same dev signer (`385af3b4…71ce`), `isDraft: true`, no git
      tag. The title's journey into the mini player, and the play/pause glyph
      handover.

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
- [x] A page that opens on a sleeve settles into it instead of cutting to it:
      a quarter of a second of scale and opacity on the header artwork of an
      album, playlist or artist page (see `ArrivalMotion.kt`). Drawn rather than
      composed, so no frame of it re-measures anything, and skipped outright
      where the listener has asked for no motion or the device has been judged
      too slow to spend frames on it.
- [x] The queue's search rises into place rather than cutting in over the
      player, which is the difference between a screen arriving and the player
      looking as though it had been replaced. The field takes focus as it
      composes, so nothing about the search waits for the animation.
- [ ] Still to come, each one to be looked at on a device before it ships:
      the player's own content sinking as the sheet closes rather than only
      fading; the artwork's slow living zoom; the album and artist pages
      handing their artwork to the player; the queue and lyrics panels easing
      rather than sliding; the settings rows settling in as the sheet opens.

### 17. The analysis overhaul

Reported from the 3.0.13 draft: the panel sat on "Waiting for analysis" for
tracks that were playing, streamed tracks showed no waveform at all, there was
no sign of progress while the bytes were coming, and resizing the transition
window dragged the arriving waveform out from under the finger.

**The panel asks, the engine answers**

- [x] The panel asks for the pair's analysis when it opens. The engine already
      analyses in the background on its own schedule, which is right for work
      nobody is waiting on; but the panel opens on two lanes the listener is
      *looking* at, and on a slow connection those stayed empty long enough
      for the panel to look broken. The demand travels through one shared
      object — `AutomixAnalysisDemand` — the way the preview already did, so
      the panel still knows nothing about the engine that answers it.
- [x] A forced round sets the cache's one-round guard aside, and may run beside
      a round the tick already started. The in-flight count is a counter and
      not a set, so a forced round finishing cannot unguard the live one.
- [x] A forced round pulls the whole track, as far as the 16 MB bound allows,
      and records the stream's length so the whole-track pass has something to
      iterate. A waveform of the first twelve seconds is not a waveform, and
      the transition sits at the end of one track and the beginning of the
      next — neither of which is in the middle of an opening.

**YouTube off does not stop the measurement**

- [x] The analysis copy is fetched under the `automix_opus` marker, which
      playback never sends, and not one of its bytes reaches the speaker. The
      YouTube *source* switch is a promise about what the listener hears, so it
      no longer gates the analysis; the gate is the listener's own Automix
      switch, read where the work is queued.
- [x] `SourceRegistry.youtubeAnalysisEnabled()` answers that gate, and it
      answers about reachability rather than preference: the YouTube row is
      never removable, so it is there whether or not the listener plays
      anything from YouTube.
- [x] A track served by an addon can be analysed at all. The branch that
      fetches bytes for an addon-served track used to refuse anything that was
      not `http`, which excluded every `bitchord://` address — so the one
      branch that existed to fetch those bytes could never fetch any, and
      every such track sat on "Waiting for analysis" for as long as it was
      queued.

**Waiting is not what playing means**

- [x] The playing track is measured whatever else is true of the queue. The
      measurement used to need a next track before it would measure anything,
      so with nothing queued after the song the song itself was never
      measured — even though the panel has a lane for it and draws the
      transition window over its end. The request now happens before the
      next-track check.

**Progress while the bytes come**

- [x] The panel says "Fetching audio… 40%" under the lane that is waiting,
      with a bar that moves. "Waiting" and "fetching" are different situations
      and the listener deserves to know which one they are in: the first means
      the app is not doing anything, the second means it is working and how far
      along it is. The number is read from the cache while the fetch runs,
      every 250 ms.

**The arriving lane stays put**

- [x] The lower lane is anchored at the arriving track's *handoff* — the point
      of its own arrangement where the blend ends — and not at its cue. The cue
      is one blend-length before the handoff, so anchoring to the cue tied the
      lane to the length of the overlap: every change of length slid the
      arriving waveform away, which is what was reported. Anchored at the
      handoff, the lane stays where it is while the window is resized, and
      travels when the whole window is moved, because then the arriving track
      really does begin somewhere else.
- [x] The anchor is frozen for the life of the pair. The planner re-plans as
      the analysis refines and as the playhead moves, and a lane that slides
      because something behind it was re-published is a lane the listener
      cannot work with. Nothing is adopted while a finger is down.


### 18. Analysis in seconds, and controls that are not joined together

**The head pass runs even when the whole file is on disk**

- [x] The forced round the panel asks for pulls the track's bytes up front, so
      the cache entry is usually *complete* before the analyser looks at it.
      Completeness used to be the test that sent a track straight down the
      whole-track lane — a full-file decode and two model windows — which
      skipped the head pass entirely and left the panel empty for minutes on
      the screen the listener had opened to watch it. The head pass now runs
      whenever there is no answer yet, complete file or not, and publishes the
      opening's BPM, grid and envelope in seconds; the whole-track pass
      supersedes it on a later tick, from the same bytes, with no fetch left to
      wait for.
- [x] A provisional result is still not a reason to run the head pass again:
      what it wants is bytes, and the escalation to the whole-track pass would
      never be reached if the lane kept re-measuring its own answer.

**Markers and waveforms are separate objects**

- [x] The arriving lane is anchored **once**, from the planner's handoff, and
      from then on only the listener's own drag moves it. It used to be
      re-derived from the window's edge on every frame, which is the second
      report of the same fault: "the lower waveform is still stuck to one of
      the markers". The window now slides over both waveforms.
- [x] A touch means one of three things and nothing else: a **marker** (the two
      full-height edge handles) moves that edge of the window; the **arriving
      lane** moves that waveform, by hand, with no magnet; the **playing lane**
      moves the timeline. The box's body drag lives on the playing lane only,
      so every touch on the arriving lane means "this waveform" and never "the
      window".
- [x] The waveforms are deliberately not magnetic. Lining two tracks up by ear
      is the listener's business, and a magnet that took the decision away is
      the "attached to a marker" complaint wearing a different hat. The window's
      edges keep theirs, because a transition *is* made of whole bars.

### 19. Playing from Downloads plays the file

- [x] A downloaded track plays its file, always — network or not. The old rule
      streamed it whenever a connection was up, and nothing else in the app
      agreed with it: a queued download is skipped by the disk cache
      (`AudioCache.noteSongs`) and by read-ahead (`AudioCache.prefetchQueue`),
      named "Local Storage" by the stats panel, and badged with the file's own
      bitrate. Only the item builder disagreed, quietly opening a
      `bitchord://` stream that nothing had warmed, on a resolve nobody asked
      for. That is the "plays from Downloads but keeps loading" report.
- [x] The rung above the file still holds: a source that can serve a Lossless or
      Hi-res copy outranks it (`SourceResolver.canServeLossless`), and the
      ordinary second look swaps one in over the file — see
      `PlaybackService.adoptFileBackedTrack` and `SwapPoint.upgradeBase`, which
      builds the swapped item from the stream the track *would* have had, since
      a `content://` path never reaches the resolver.
- [x] A file that is already lossless is left alone (the decoder's own mime, not
      the badge the download wrote), and a downloaded addon track is left alone
      too: its file *is* the copy that source served, and the swap machinery is
      keyed on the `v=` a YouTube id carries.
- [x] `restoreStreamingAfterOffline` is gone. It handed queued downloads back to
      the network when a connection returned, which is the same policy in the
      other direction; the file is what plays now, whatever the network is
      doing.
