package com.music.bitchord.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A real crossfade: two tracks audible at once, the outgoing one falling as the
 * incoming one rises, the way Spotify and Apple Music do it.
 *
 * ## Why there are two players
 *
 * One ExoPlayer renders one queue item at a time, so at a track boundary there
 * is exactly one source and the gain it can be given is either 1 (no fade) or 0
 * (silence). The previous version of this class was a single-player volume
 * ramp, and that is precisely why it never sounded like a crossfade: it dipped
 * to silence at the join and climbed back out, leaving a hole where the blend
 * should be. Overlap needs a second decoder. There is no way around it.
 *
 * ## Which player plays what
 *
 * The naive second player is a copy of the queue, and that is the design that
 * fell over before — two players both convinced they own the playlist, two
 * audio focus requests, and a MediaSession whose player keeps changing under
 * it. So the split here is deliberately lopsided:
 *
 *  - **[player]** — the one ExoPlayer that owns the queue, backs the
 *    MediaSession and holds audio focus, exactly as it did before this class
 *    existed. It is the only player the rest of the app ever sees.
 *  - **[ghost]** — a single-item, throwaway player that renders *the tail of
 *    the track being left behind* and nothing else. It has no queue, receives
 *    no user commands, is nobody's source of truth, and can be stopped dead at
 *    any instant without anything needing to be unwound.
 *
 * At the crossfade point the session player **jumps to the next track
 * immediately** and fades up, while the ghost carries the old track's last
 * seconds down to silence. That ordering is the point: the queue index, the
 * metadata, the notification and the UI all flip to the incoming song the
 * moment it becomes audible, instead of trailing the song that is on its way
 * out.
 *
 * ## The handoff
 *
 * The one seam in this design is the instant the outgoing track stops being
 * rendered by [player] and starts being rendered by [ghost]. Two ExoPlayers
 * cannot be started sample-accurately against each other, so the ghost is
 * armed early and left free-running *silently* alongside the session player
 * while it is walked onto the session player's playhead. Those corrections are
 * free: nobody can hear a muted player being moved. Only once the two agree
 * does [Phase.LAPPING] hand the outgoing track over across [LAP_MS].
 *
 * ## Why the walk is two-stage
 *
 * Seeking alone cannot close the gap. A seek lands on a decoded frame boundary
 * — 20-26ms depending on the codec — and where in that frame the target fell is
 * not knowable in advance, so every correction reintroduces up to a frame of
 * error however many times it is repeated. That is why this used to settle for
 * agreeing to within 20ms, and 20ms is precisely the wrong number: it is the
 * classic slapback threshold, the point at which a delayed copy stops colouring
 * a sound and starts being heard as a second copy of it. Two players rendering
 * the same audio 20ms apart for 90ms is a flam, and it was audible at the head
 * of every single transition.
 *
 * So the seek is only the coarse stage, used while the ghost is more than
 * [COARSE_SYNC_MS] out. The fine stage is a *timed rate trim*: the ghost is run
 * [RATE_TRIM] fast or slow for exactly as long as it takes to absorb the
 * measured drift, then put back. That is open-loop on purpose — the audible
 * effect of a rate change lags by the audio track's own buffer, but the lag
 * applies equally to switching it on and to switching it off, so the shift the
 * ghost actually accumulates is the trim times its duration regardless. It has
 * no quantum, so it closes what a seek structurally cannot.
 *
 * ## Why the lap is not equal-power
 *
 * The [LAP_MS] handoff is the one place in this class where both players carry
 * *the same* audio, and correlated signals do not add in power, they add in
 * amplitude: `sin + cos` peaks at √2, so an equal-power lap opened every
 * transition with a +3 dB level bump on the track being left. The lap uses a
 * complementary pair summing to exactly 1 instead — see [lapRise]. The
 * equal-power law is still right for [Phase.FADING], where the two players hold
 * two genuinely different tracks.
 *
 * ## Curve
 *
 * `sin`/`cos` rather than the old `sqrt`: `sin²+cos²=1` exactly, so two tracks
 * fading past each other hold constant *power* the whole way through and the
 * transition has no dip in the middle. That is the standard crossfade law, and
 * it is what makes a long crossfade sound like a blend instead of a dip.
 */
@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    /** Builds the tail player. Called at most once; the instance is kept warm. */
    private val newGhost: () -> ExoPlayer,
    /**
     * Stored Smart Fade analysis for a media item, or an empty [TrackAnalysis]
     * when there is none yet. This is the seam Phase 1's DSP analyzer plugs
     * into: until analysis finishes, a track reads as "no evidence", which
     * [planTransition] answers with the same fixed-length crossfade this
     * class always ran before Smart Fade existed.
     */
    private val analysisFor: (MediaItem) -> TrackAnalysis = { TrackAnalysis() },
    /**
     * Queues background analysis for a media item that will soon need it.
     * Cheap to call on every tick: a track already analysed, already in
     * flight, or not yet fully cached is a no-op.
     *
     * Takes the item's duration in milliseconds, or 0 when Media3 hasn't loaded
     * that far ahead yet. The analyzer needs it to tell one rendition of a
     * recording from a differently-cut one before reusing an analysis across
     * them, and this class is the only place that already knows it.
     */
    private val requestAnalysis: (MediaItem, Long) -> Unit = { _, _ -> },
    /**
     * The low-pass and high-pass riding each side of a transition. This is what
     * makes a plan's
     * [com.music.bitchord.playback.smart.TransitionPlan.transitionStyle] audible
     * rather than advisory: see [rideFilters]. Defaults to
     * [TransitionFilters.None], which renders every style as the plain
     * equal-power blend this class ran before.
     */
    private val filters: TransitionFilters = TransitionFilters.None,
    /**
     * Whether a decode and inference for a media item is running right now.
     * Only feeds the stats line — nothing about a transition waits on it.
     */
    private val analysisRunningFor: (MediaItem) -> Boolean = { false },
) {

    private enum class Phase {
        /** Nothing in flight; watching for the next transition. */
        IDLE,

        /** Ghost is spinning up on the outgoing track and syncing to the session player. */
        ARMING,

        /** Outgoing track being handed from the session player to the ghost. */
        LAPPING,

        /** Session player rising on the new track, ghost falling on the old one. */
        FADING,

        /** Something interrupted the fade; the ghost is being ramped out of the way. */
        BAILING,
    }

    private var phase = Phase.IDLE
    private var ghost: ExoPlayer? = null

    /** Length of the transition in flight, in ms. Fixed when it begins. */
    private var fadeMs = 0L

    /**
     * Where the fade window ends, in the session player's position ms.
     * Standard mode sets this to the track's own duration, which is what
     * [driveArming] always compared against before Smart Fade existed; a
     * Smart Fade plan can set it earlier, at an analyzed mix-out anchor, so
     * [driveArming] watches this field rather than re-deriving the fade point
     * from [ExoPlayer.getDuration] on every tick.
     */
    private var fadeEndMs = 0L

    /**
     * Which setting armed the fade in flight, so [driveFade] knows which one
     * being switched off mid-blend means "stop now" rather than misreading the
     * other mode's control as the fade having been turned off. Smart Fade
     * doesn't need [AppSettings.crossfadeSeconds] to be above zero at all —
     * see [considerSmartTransition] — so treating that as still-zero as a
     * reason to cut a Smart Fade short would end every one of them on its
     * first tick.
     */
    private var smartFadeActive = false

    /**
     * Where the incoming track is cued when the lap hands the queue over, in
     * its own timeline ms. Standard fades always leave this at 0 — a plain
     * track change starts from the top — and only a Smart Fade plan sets it
     * to an analyzed mix-in point instead.
     */
    private var incomingCueTimeMs: Long = 0L

    /**
     * The tempo-stretch ratio applied to the incoming track for the
     * transition, stacked on top of whatever [AppSettings.playbackSpeed] the
     * listener already has set — 1.0 is a no-op. This is what actually
     * beatmatches a BEATMATCHED-tier plan: without it, the two tracks blend
     * at their own unrelated tempi and the result is a crossfade with
     * smarter timing, not a beatmatch.
     */
    private var incomingPlaybackRate: Double = 1.0

    /**
     * The style-specific half of the plan in flight — everything [rideFilters]
     * needs and nothing else. Fixed when the transition begins, because a plan
     * is recomputed every tick and a bass swap that moved to a different beat
     * halfway through the blend would be heard as the low end flapping.
     */
    private var render = Render()

    /**
     * The style fields of a [com.music.bitchord.playback.smart.TransitionPlan],
     * separated out so the standard (non-Smart) path can pass defaults without
     * constructing a plan it never made.
     */
    private data class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        val bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val vocalOverlap: Double = 0.0,
    )

    private var lapStartedAt = 0L
    private var bailStartedAt = 0L
    private var armDeadline = 0L

    /**
     * Gain the ghost was at when the fade was interrupted. The ramp out is
     * scaled by it, because a fade abandoned during [Phase.ARMING] is one where
     * the ghost is still silent — ramping "down from 1" there would put the
     * outgoing track on at full volume purely in order to fade it out again.
     */
    private var bailFromGain = 0f

    /**
     * When the rate trim currently running on the ghost should be lifted, in
     * elapsed-realtime ms; 0 when none is running.
     *
     * The whole of the fine sync stage is this one deadline. Drift is measured
     * once, converted into how long [RATE_TRIM] needs to be applied to absorb
     * it, and then simply timed out — no closed loop, because the audio buffer
     * puts a couple of hundred milliseconds of dead time between a rate change
     * and its effect on the reported position, which is more than enough for a
     * proportional controller ticking every [ARM_STEP_MS] to chase its own tail.
     */
    private var trimUntil = 0L

    /**
     * Elapsed-realtime before which a drift reading is still settling and not
     * worth acting on — set after each seek and after each trim is lifted.
     * Reading a position that has not caught up yet is how a correction ends up
     * being applied twice.
     */
    private var syncSettleUntil = 0L

    /**
     * Whether the ghost has been seen playing during this arm, so the first
     * reading taken off it can be given the same settling time as one taken
     * after a seek. A position sampled in the first instants of an audio track
     * is extrapolated rather than measured, and acting on it wastes the coarse
     * stage's first correction on noise.
     */
    private var ghostRunning = false

    /** Dedupes the per-tick plan log down to one line per distinct verdict. */
    private var lastPlanVerdict = ""

    /**
     * How far ahead of the session player the ghost is seeked, to cover the time
     * a seek itself takes to come back. Learned rather than assumed — it varies
     * with the device and with whether the track is on disk yet.
     */
    private var seekLeadMs = 60L

    /**
     * How long our own `seekToNextMediaItem` gets to be recognised as ours, so
     * the lap isn't mistaken for the listener reaching for the scrubber.
     *
     * A window rather than a count of expected callbacks: Media3 reports the
     * queue moving as both a discontinuity and an item transition, a seek that
     * turns out to be a no-op reports neither, and a counter that guesses wrong
     * either swallows the listener's next seek or bails on our own. A window
     * clears itself however many callbacks turn up.
     */
    private var selfMoveUntil = 0L

    /**
     * Whether the transition now arriving is this class advancing the queue on
     * the listener's behalf — the crossfade equivalent of a track ending.
     * Consumed by [PlaybackService], which otherwise has no way to tell our
     * seek apart from a manual skip and would stop honouring "sleep after this
     * song".
     */
    private var autoAdvance = false

    fun consumeAutoAdvance(): Boolean = autoAdvance.also { autoAdvance = false }

    /**
     * True while a transition is armed or running on [player] and [ghost].
     *
     * For callers about to do something that would otherwise fight this
     * class for the session player mid-blend — [PlaybackService]'s quality
     * upgrade is the one that does, since `replaceMediaItem` tears the
     * current source down and rebuilds it. Interrupting the source
     * [driveArming] is syncing against, or the one [driveFade] is ramping,
     * breaks the blend rather than merely delaying it, so such a caller
     * should wait for this to clear rather than proceed anyway.
     */
    fun isTransitioning(): Boolean = phase != Phase.IDLE

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The listener moving the playhead is something no half-finished
            // crossfade should survive; the lap's own seek is not.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !ourOwnMove()) bail()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                // Something replaced the queue out from under the fade — a new
                // album, a new search result — so the tail still playing is a
                // leftover of a session that no longer exists. Note that this
                // does *not* fire when AutoPlay appends to the end, since the
                // playing item doesn't change: extending the queue mid-fade is
                // harmless and shouldn't cost the listener the blend.
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> if (!ourOwnMove()) bail()
            }
        }

        override fun onPlayerError(error: PlaybackException) = bail()
    }

    private fun ourOwnMove(): Boolean = SystemClock.elapsedRealtime() < selfMoveUntil

    fun start() {
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.LAPPING -> LAP_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                    },
                )
            }
        }
    }

    fun release() {
        player.removeListener(listener)
        player.volume = 1f
        AppSettings.smartMixInProgress.value = false
        filters.open()
        ghost?.release()
        ghost = null
    }

    // ---- Entry points -------------------------------------------------------

    /**
     * A skip the listener asked for: drop any blend in flight and get out of
     * the way.
     *
     * Crossfade is deliberately a property of tracks *running out*, not of
     * being changed. Blending a manual skip means the song just left behind
     * stays audible over the one that was asked for, which reads as the app
     * ignoring the button rather than as a transition — the point of pressing
     * next is usually to stop hearing the current track.
     *
     * Called before the skip is carried out, so the tail is already on its way
     * down as the new track starts. The listener would catch the resulting seek
     * anyway, but only outside [SELF_MOVE_WINDOW_MS]; a press landing inside
     * that window would be mistaken for the lap's own move and leave the ghost
     * running over the new track. Saying so explicitly closes that gap.
     */
    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    // ---- Ticker -------------------------------------------------------------

    private fun tick() {
        // A pause has to take the tail with it, or the track being faded out
        // carries on alone over a stopped player. Mirrored every tick rather
        // than handled as an event, so audio focus loss, the sleep timer and
        // the pause button all get the same treatment for free.
        if (phase == Phase.ARMING || phase == Phase.LAPPING || phase == Phase.FADING) {
            ghost?.playWhenReady = player.playWhenReady
        }

        // Every tick, not only when a transition can be planned. This used to
        // live inside [considerSmartTransition], which needs an idle phase, a
        // playing player and a known duration — none of which hold during a
        // transition or during the re-buffer after a quality upgrade. The line
        // simply froze on the previous pair, so a track that had not been
        // analysed kept showing the *departing* track's "analysed" until
        // ticking resumed.
        publishAnalysisState()

        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.LAPPING -> driveLap()
            Phase.FADING -> driveFade()
            Phase.BAILING -> driveBail()
        }
    }

    /** Arms a crossfade as the playing track runs out. */
    private fun considerAutoTransition() {
        if (!player.isPlaying) return
        // Repeating one track would crossfade it into itself.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        // Nothing to transition *into*, so any analysis state left over from the
        // previous pair is stale — the last track of a queue should not still be
        // claiming both songs are measured.
        if (!player.hasNextMediaItem()) {
            AppSettings.smartTransitionWindow.value = null
            return
        }

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        // Smart Fade is its own on/off, independent of the manual crossfade
        // length: it decides its own duration from each pair of tracks (beats,
        // tempo, structure), so requiring a nonzero [AppSettings.crossfadeSeconds]
        // first would tie an automatic feature to a manual one it doesn't use.
        if (AppSettings.smartFadeEnabled.value) {
            considerSmartTransition(duration)
            return
        }

        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return

        val remaining = duration - player.currentPosition
        // Arm early: the ghost needs time to spin up and settle into sync
        // before it is any use, and that work has to be finished by the time
        // the fade is due rather than started then.
        if (remaining > fade + ARM_LEAD_MS) return

        begin(fade, endMs = duration, smart = false)
    }

    /**
     * Arms a Smart Fade transition once its plan says the playhead is close
     * enough to start arming for it.
     *
     * Reads the plan's timing (where the fade starts and how long it runs),
     * where the incoming track should be cued
     * ([com.music.bitchord.playback.smart.TransitionPlan.incomingCueTime]),
     * and the tempo-stretch to align it with the outgoing track
     * ([com.music.bitchord.playback.smart.TransitionPlan.incomingPlaybackRate])
     * — see [driveLap], which applies both at the handoff — and the style the
     * blend is rendered in
     * ([com.music.bitchord.playback.smart.TransitionPlan.transitionStyle]),
     * which [rideFilters] turns into a filter ride or a bass swap over the same
     * equal-power gain curve.
     */
    private fun considerSmartTransition(duration: Long) {
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        val nextDuration = nextItemDurationMs(nextIndex, nextItem)

        // Cheap no-ops once a track is analysed or already in flight; called
        // every tick so a track that finishes caching mid-song is picked up
        // without a separate trigger.
        requestAnalysis(currentItem, duration)
        requestAnalysis(nextItem, nextDuration)

        // Only used before analysis lands, or when the evidence is too weak
        // for more than a plain fade (see [TransitionTier.PLAIN_CROSSFADE]):
        // once real analysis is available, [planTransition] sizes the overlap
        // itself from tempo and structure and ignores this entirely. Honours
        // the manual slider if the listener also set one, so the two settings
        // don't fight; falls back to a fixed length when it's at "Off".
        val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }
            ?.div(1000.0)
            ?: DEFAULT_SMART_FALLBACK_SECONDS

        // Resolved once and reused: [analysisFor] was being called five separate
        // times per tick below, and the answer cannot change mid-tick.
        val currentAnalysis = analysisFor(currentItem)
        val nextAnalysis = analysisFor(nextItem)
        val analysisState = AppSettings.smartAnalysis.value

        val plan = planTransition(
            analysis = currentAnalysis,
            nextAnalysis = nextAnalysis,
            currentTrack = currentItem.toTransitionInfo(duration),
            nextTrack = nextItem.toTransitionInfo(nextDuration),
            currentTime = player.currentPosition / 1000.0,
            duration = duration / 1000.0,
            fadeSeconds = fallbackSeconds,
            mode = CrossfadeMode.SMART,
        )
        // One line per distinct verdict rather than one per 250ms tick, so the
        // log says what the planner decided for this pair without burying it.
        val verdict = "${plan.reason}|${plan.transitionStyle}|fade=${plan.fadeMs}" +
            "|cue=${plan.incomingCueTime}|rate=${plan.incomingPlaybackRate}" +
            "|vocalOverlap=${"%.2f".format(plan.vocalOverlap)}" +
            "|blocked=${plan.blocked}|policy=${plan.policyReasons.joinToString(",")}"
        if (verdict != lastPlanVerdict) {
            lastPlanVerdict = verdict
            Log.d(
                TAG,
                "plan ${currentItem.mediaId}->${nextItem.mediaId}: $verdict " +
                    "bpm=${currentAnalysis.bpm}/${nextAnalysis.bpm} " +
                    "conf=${currentAnalysis.beatConfidence}/${nextAnalysis.beatConfidence}",
            )
        }

        // Gated on *both* tracks being measured, not on the plan alone. Until
        // then the planner is still sizing the overlap from a fallback that
        // moves as evidence lands, and a marker that slides along the bar while
        // you watch it is worse than none. Cleared during the transition itself
        // by [driveLap], because from that moment these fractions describe a
        // track the session player has already left.
        //
        // Asymmetric on purpose, because the two sides are read for different
        // things and a head-only result covers one of them completely.
        //
        // Where the window *sits* comes almost entirely from the outgoing track:
        // its content end, its outro, its mix-out anchors. A provisional result
        // has none of those — [analyzeHead] drops them deliberately rather than
        // answering confidently about a track it has only seen the opening of —
        // so the plan falls back to a plain end-of-track window, and the marker
        // would sit there and then jump backwards when the whole-track pass
        // lands. That is the sliding marker this guard exists for, so the
        // outgoing side still has to be finished.
        //
        // The incoming side is the opposite case. All the planner asks of it is
        // tempo, confidence and where it is safe to cue in — which are exactly
        // the fields a head pass measures, and it measures them over the same
        // opening window the whole-track pass would. Refining will sharpen those
        // numbers but not move them, so holding the marker back for it hid a
        // window that was already correct. Since the incoming track is now
        // routinely analysed from its opening long before it plays, that was
        // most of the time the marker was missing.
        val markable = !plan.blocked &&
            plan.markerVisible &&
            duration > 0L &&
            analysisState.current == TrackAnalysisState.ANALYSED &&
            analysisState.next in MEASURED_ENOUGH_TO_ENTER_ON
        AppSettings.smartTransitionWindow.value = if (markable) {
            TransitionWindow(
                start = (plan.transitionStart * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
                end = (plan.transitionEnd * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
            )
        } else {
            null
        }

        if (plan.blocked) return

        val fade = plan.fadeMs
        if (fade <= 0L) return

        val transitionStartMs = (plan.transitionStart * 1000).roundToLong()
        val remaining = transitionStartMs - player.currentPosition
        // Same arm-ahead margin as the standard path, just measured against
        // the plan's own start rather than a fixed offset from track end —
        // an analyzed mix-out anchor can place that start well before the
        // file actually ends.
        if (remaining > ARM_LEAD_MS) return

        begin(
            fade,
            endMs = (plan.transitionEnd * 1000).roundToLong(),
            smart = true,
            cueTimeMs = (plan.incomingCueTime * 1000).roundToLong(),
            playbackRate = plan.incomingPlaybackRate,
            renderStyle = Render(
                style = plan.transitionStyle,
                bassSwap = plan.bassSwap,
                bassSwapFraction = plan.bassSwapFraction,
                filterSweep = plan.filterSweep,
                vocalOverlap = plan.vocalOverlap,
            ),
        )
    }

    /**
     * Keeps the stats line describing the pair that is actually playing.
     *
     * Cheap enough to run unconditionally — two concurrent-map lookups and a
     * set membership test — and running it unconditionally is the point: any
     * gating reintroduces the staleness this exists to remove.
     */
    private fun publishAnalysisState() {
        val currentItem = player.currentMediaItem
        val nextIndex = player.nextMediaItemIndex
        val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        AppSettings.smartAnalysis.value = SmartAnalysis(
            current = currentItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
            next = nextItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
        )
    }

    /**
     * Where one track stands, for the stats line. "Analysing" is asked for
     * first because a track can be in flight while a superseded provisional
     * result is already on record, and the work in progress is the more useful
     * thing to say about it.
     */
    private fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = when {
        // Usable first, and a pass in flight *second*. The other order was
        // right up to the point a head-only result started arriving before the
        // whole-track one: a track measured off its opening reads as analysed,
        // then finishes caching, then has the full pass run over it to replace
        // the provisional numbers — and reported "analysing" again throughout.
        // Going backwards from analysed reads as something having broken, when
        // what is happening is a better answer being computed. Confidence on one
        // such track went 0.39 to 0.94 and its cue moved from 0.1s to 9.5s.
        analysis.isUsable ->
            if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        analysisRunningFor(item) -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyzer's way of saying it
        // tried and got nothing, and that it will not try again — it writes a
        // ready-but-empty entry precisely so the track stops being retried. A
        // track nothing has looked at yet has no status at all, which is the
        // only case that is still merely waiting.
        analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /**
     * The next queue item's own duration.
     *
     * Media3 fills a timeline window's duration in when the item is *prepared*,
     * which for the track after this one happens a few seconds before it starts
     * playing. So for almost the whole of the current track this answered zero —
     * and zero is not a harmless "don't know" downstream. It reaches
     * [com.music.bitchord.playback.smart.TrackAnalyzer.request] as the next
     * track's duration, and with no duration to check a sibling copy against the
     * analyzer will only read the rendition the cache key resolves to *right
     * now*, which with source substitution on is the `#alt` entry — while the
     * copy actually on disk is the plain one its own head fetch just pulled
     * down. Nothing matches, the pass returns silently, and it does that on every
     * tick for the rest of the track. Measured: a fully cached next track sat
     * unread for three minutes and was analysed eight seconds before the fade it
     * was meant to inform, having been analysable the whole time.
     *
     * The runtime is on the item already — queued from a row that knew it, and
     * carried on the playback URI as `d=` because a cross-source match is made on
     * it (see `Song.matchQuery`). Reading it here costs nothing and is available
     * from the moment the queue is set.
     */
    private fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long {
        val timeline = player.currentTimeline
        if (!timeline.isEmpty) {
            timeline.getWindow(nextIndex, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { return it }
        }
        return queuedDurationMs(item)
    }

    /**
     * The runtime the queue row carried, in milliseconds, or 0 when the item
     * doesn't state one — a local file, or a track queued without a duration.
     *
     * Deliberately forgiving: [Uri.getQueryParameter] throws on an opaque URI,
     * and a missing or unparsable value is simply an absent duration rather than
     * anything worth failing a tick over.
     */
    private fun queuedDurationMs(item: MediaItem): Long {
        val uri = item.localConfiguration?.uri ?: return 0L
        val seconds = runCatching { uri.getQueryParameter("d") }.getOrNull()?.toLongOrNull() ?: return 0L
        return if (seconds > 0) seconds * 1000L else 0L
    }

    /** BitChord doesn't carry album metadata on [MediaMetadata] yet, so [TransitionTrackInfo.album] stays blank. */
    private fun MediaItem.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = mediaId,
        durationMs = durationMs,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
    )

    /**
     * Spins the ghost up on the outgoing track and walks it into sync with the
     * session player.
     */
    private fun begin(
        fade: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        renderStyle: Render = Render(),
    ): Boolean {
        val outgoing = player.currentMediaItem ?: return false
        val ghost = warmGhost() ?: return false

        fadeMs = fade
        fadeEndMs = endMs
        smartFadeActive = smart
        incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
        incomingPlaybackRate = playbackRate
        render = renderStyle
        armDeadline = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS
        syncSettleUntil = 0L
        trimUntil = 0L
        ghostRunning = false

        // Taken from the session player rather than from settings: these two
        // change how fast a position advances against the wall clock, and the
        // whole handoff rests on the pair agreeing about where they are. Also
        // clears any rate trim left on the ghost by the previous transition.
        ghost.skipSilenceEnabled = player.skipSilenceEnabled
        ghost.playbackParameters = player.playbackParameters

        Log.d(
            TAG,
            "arm ${if (smart) "smart" else "standard"} fade=${fade}ms end=${endMs}ms " +
                "cue=${incomingCueTimeMs}ms rate=$incomingPlaybackRate at=${player.currentPosition}ms " +
                "style=${render.style} bassSwap=${render.bassSwap}@${render.bassSwapFraction} " +
                "sweep=${render.filterSweep}",
        )

        ghost.setMediaItem(outgoing)
        ghost.seekTo(player.currentPosition + seekLeadMs)
        ghost.volume = 0f
        ghost.playWhenReady = true
        ghost.prepare()

        phase = Phase.ARMING
        return true
    }

    private fun driveArming() {
        val ghost = ghost ?: return bail()
        if (!stillWorthFading()) return bail()

        val now = SystemClock.elapsedRealtime()
        val expired = now > armDeadline
        val running = ghost.isPlaying

        // A ghost that never got going has no tail to hand the track to, and
        // lapping onto silence would be the very hole this class exists to get
        // rid of. Give up instead and let the track change plainly.
        if (expired && !running) return bail()

        // A position read off an audio track that has only just opened is
        // extrapolated, not measured, so the first reading gets the same grace
        // as one taken straight after a seek.
        if (running && !ghostRunning) {
            ghostRunning = true
            syncSettleUntil = now + SEEK_SETTLE_MS
            Log.d(TAG, "ghost up ${now - armDeadline + ARM_TIMEOUT_MS}ms after arm")
        }

        val aligned = running && walkIntoSync(ghost, now)

        // Wait for the track to actually reach the fade point. [fadeEndMs] is
        // the track's own duration in standard mode, or a Smart Fade plan's
        // analyzed mix-out anchor when it ends before the file does.
        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - player.currentPosition <= fadeMs

        if (!atFadePoint) return
        // Out of time to keep tidying up: a slightly ragged handoff still beats
        // no crossfade at all.
        if (aligned || expired) startLap()
    }

    /**
     * One step of walking the silent ghost onto the session player's playhead.
     * Returns true once the two are close enough that handing the track over is
     * inaudible.
     *
     * Coarse stage first — a seek, for anything further out than
     * [COARSE_SYNC_MS], since a rate trim would take seconds to cover that much
     * ground. Fine stage second, because a seek cannot finish the job: it lands
     * on a frame boundary and the rounding is fresh noise on every attempt.
     */
    private fun walkIntoSync(ghost: ExoPlayer, now: Long): Boolean {
        // A trim in flight is a correction that has already been decided; the
        // ghost is deliberately running at the wrong rate until it expires, so
        // its position means nothing to anyone until then.
        if (trimUntil != 0L) {
            if (now < trimUntil) return false
            clearTrim()
            syncSettleUntil = now + TRIM_SETTLE_MS
            return false
        }
        if (now < syncSettleUntil) return false

        val drift = ghost.currentPosition - player.currentPosition
        if (abs(drift) <= SYNC_TOLERANCE_MS) return true

        if (abs(drift) > COARSE_SYNC_MS) {
            // Whatever the last seek overshot or undershot by is exactly what
            // the next one should compensate for, so the lead tunes itself to
            // this device rather than to a guessed constant.
            seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
            ghost.seekTo(player.currentPosition + seekLeadMs)
            syncSettleUntil = now + SEEK_SETTLE_MS
            Log.d(TAG, "walk seek drift=${drift}ms lead=${seekLeadMs}ms")
            return false
        }

        // A ghost that is behind has to run fast to catch up, and vice versa.
        // The duration is what does the work: [RATE_TRIM] for `drift / RATE_TRIM`
        // milliseconds shifts the ghost by exactly `drift`.
        applyTrim(ghost, faster = drift < 0L)
        trimUntil = now + (abs(drift) / RATE_TRIM).roundToLong().coerceAtMost(MAX_TRIM_MS)
        Log.d(TAG, "walk trim drift=${drift}ms for ${trimUntil - now}ms")
        return false
    }

    /**
     * Runs the ghost [RATE_TRIM] off the session player's rate. Inaudible by
     * construction: this is only ever applied during [Phase.ARMING], where the
     * ghost's volume is zero.
     */
    private fun applyTrim(ghost: ExoPlayer, faster: Boolean) {
        val base = player.playbackParameters
        ghost.playbackParameters = base.withSpeed(base.speed * (if (faster) 1f + RATE_TRIM else 1f - RATE_TRIM))
    }

    /** Puts the ghost back on the session player's exact rate. Idempotent. */
    private fun clearTrim() {
        trimUntil = 0L
        ghost?.playbackParameters = player.playbackParameters
    }

    private fun startLap() {
        val ghost = ghost ?: return bail()
        // The single number that says whether a transition will be heard
        // starting or just heard: how far apart the two copies of the outgoing
        // track are at the instant one hands over to the other. Anything up to
        // [SYNC_TOLERANCE_MS] fuses; a slapback becomes audible around 20ms.
        val late = SystemClock.elapsedRealtime() > armDeadline
        Log.d(
            TAG,
            "lap drift=${ghost.currentPosition - player.currentPosition}ms lead=${seekLeadMs}ms" +
                if (late) " (TIMED OUT — walk never converged)" else "",
        )
        // Whatever the walk was still doing, the two players have to be running
        // at the same rate through a handoff and for the tail afterwards.
        clearTrim()
        lapStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.LAPPING
    }

    /**
     * Hands the outgoing track from the session player to the ghost.
     *
     * Both are rendering the same audio at the same position here, so this is
     * kept as short as it can be while still being a ramp rather than a cut —
     * long enough to swallow any residual misalignment, too short for the two
     * copies to comb against each other audibly. It uses [lapRise] rather than
     * the equal-power pair for the same reason: the two signals are the same
     * signal, so their gains have to sum to one, not to one in power.
     */
    private fun driveLap() {
        val ghost = ghost ?: return bail()
        // Pausing in the few tens of milliseconds it takes to hand the track
        // over means nothing has been handed over yet: the session player still
        // has the track, so give it back rather than advancing a queue the
        // listener has just stopped.
        if (!player.playWhenReady) return bail()

        val progress = (SystemClock.elapsedRealtime() - lapStartedAt).toFloat() / LAP_MS

        if (progress < 1f) {
            player.volume = lapFall(progress)
            ghost.volume = lapRise(progress)
            return
        }

        ghost.volume = 1f
        player.volume = 0f
        // The ghost has the old track. The session player is free to become the
        // new one — and everything hanging off it (queue index, metadata, the
        // notification, the UI) moves to the incoming song right here, while
        // its first note is still fading up.
        // Only a track running out ever gets this far, so the queue moving
        // here is always the crossfade standing in for a track ending.
        autoAdvance = true
        selfMoveUntil = SystemClock.elapsedRealtime() + SELF_MOVE_WINDOW_MS
        // A Smart Fade plan cues the incoming track to its own analyzed mix-in
        // point rather than 0 — landing on the beat grid, not the file's cold
        // open — so this seeks straight to that position in the same call
        // that moves the queue forward, instead of using
        // [Player.seekToNextMediaItem] (which always lands on 0) and then
        // correcting with a second seek that would itself be visible as a
        // discontinuity.
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex != C.INDEX_UNSET && incomingCueTimeMs > 0L) {
            player.seekTo(nextIndex, incomingCueTimeMs)
        } else {
            player.seekToNextMediaItem()
        }
        // Stacks on top of the listener's own speed control rather than
        // replacing it, so a beatmatched transition and "play everything at
        // 1.25x" don't fight each other. Restored in [finish].
        if (incomingPlaybackRate != 1.0) {
            player.setPlaybackSpeed((AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat())
        }
        // Raised here rather than at [begin], because ARMING is silent: nothing
        // is mixing until the two tracks are actually audible over each other,
        // which is what the next line starts.
        AppSettings.smartMixInProgress.value = isRealMix()
        // The queue has just moved on, so the marker's fractions now refer to a
        // track the session player is no longer showing a position for.
        AppSettings.smartTransitionWindow.value = null
        phase = Phase.FADING
    }

    /**
     * The crossfade proper.
     *
     * Driven off the *incoming* track's position rather than off a clock, so a
     * pause parks the transition where it stands and resuming picks it back up
     * — no timer to reconcile, and no ghost left hanging at half volume while
     * the session player waits.
     */
    private fun driveFade() {
        val ghost = ghost ?: return bail()
        // The incoming track gets the same say over the length as the outgoing
        // one did, so a long crossfade into a short track tightens rather than
        // swallowing it. Its duration is often still unknown when the fade
        // starts — the stream is only being opened — so this is read every tick
        // and simply narrows the span once the answer arrives. Capped only by
        // the incoming track's own length, not by [configuredFadeMs] — a Smart
        // Fade plan already sized itself independently of that setting, and
        // may be running with it at zero.
        // Measured from where the incoming track was *cued*, not from zero. A
        // Smart Fade plan can drop it in mid-arrangement, and reading its raw
        // position as elapsed-fade would put a cue at 0:45 instantly past the
        // end of an 8-second fade — finishing the blend on its first tick and
        // landing as an abrupt cut, which is precisely the failure a cued
        // transition is supposed to avoid.
        val remainingIncoming = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(incomingCueTimeMs)
            ?.coerceAtLeast(0L)
        val incomingCap = remainingIncoming?.div(3) ?: Long.MAX_VALUE
        val span = (minOf(fadeMs, incomingCap) - LAP_MS).coerceAtLeast(1L)
        val elapsed = (player.currentPosition - incomingCueTimeMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / span).coerceIn(0f, 1f)

        player.volume = riseGain(progress)
        ghost.volume = fallGain(progress)
        // Only here, never during ARMING or LAPPING: those two phases have both
        // players rendering the *same* audio at the same position, and filtering
        // one copy and not the other would comb them against each other. From
        // FADING onwards the session player is the incoming track and the ghost
        // is the outgoing one, which is exactly the split [filters] describes.
        rideFilters(progress)

        // Whichever comes first: the fade running its course, the old track
        // genuinely ending, the tail failing outright, or whichever setting
        // armed this fade being switched off mid-blend. Checked against the
        // setting that actually started it — a Smart Fade normally runs with
        // [configuredFadeMs] at zero, and reading that as "turned off" would
        // end every Smart Fade on its first tick.
        val settingSwitchedOff = if (smartFadeActive) {
            !AppSettings.smartFadeEnabled.value
        } else {
            configuredFadeMs() <= 0L
        }
        val done = progress >= 1f ||
            ghost.playbackState == Player.STATE_ENDED ||
            ghost.playbackState == Player.STATE_IDLE ||
            settingSwitchedOff
        if (done) finish()
    }

    /** Ramps the ghost out rather than cutting it, so an interruption has no click in it. */
    private fun driveBail() {
        val ghost = ghost
        if (ghost == null) {
            finish()
            return
        }
        val progress = (SystemClock.elapsedRealtime() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            ghost.volume = bailFromGain * fallGain(progress)
            return
        }
        finish()
    }

    // ---- Lifecycle of a transition -----------------------------------------

    /**
     * Abandons whatever is in flight. Safe to call from anywhere, at any phase:
     * the session player is always the one holding the queue, so there is never
     * a half-applied state to put back — only a ghost to fade out and a volume
     * to restore.
     */
    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        Log.d(TAG, "bail from $phase")
        player.volume = 1f
        AppSettings.smartMixInProgress.value = false
        // Glided open rather than snapped: the session player is still audible
        // here, and if the bail caught a bass swap mid-handover its low end is
        // currently lifted out. Dropping a 24 dB/octave filter in one buffer is
        // the click this ramp exists to avoid.
        filters.open()
        autoAdvance = false
        bailFromGain = ghost?.volume ?: 0f
        bailStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.BAILING
    }

    private fun finish() {
        if (phase != Phase.IDLE) Log.d(TAG, "finish from $phase")
        player.volume = 1f
        AppSettings.smartMixInProgress.value = false
        // Unconditional and idempotent, like the speed reset below: correct
        // whether or not this transition ever filtered anything.
        filters.open()
        render = Render()
        // Undoes whatever [driveLap] stacked on for a beatmatched handoff —
        // unconditional and idempotent, so this is correct whether or not a
        // stretch was ever actually applied (a standard fade, or a Smart Fade
        // that never reached FADING, both leave the listener's own speed
        // control untouched anyway).
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        incomingPlaybackRate = 1.0
        // After the speed reset above, so the ghost is handed the rate the
        // listener actually asked for rather than a beatmatch's stretch.
        clearTrim()
        ghost?.let {
            it.volume = 0f
            it.stop()
            it.clearMediaItems()
        }
        selfMoveUntil = 0L
        phase = Phase.IDLE
    }

    /** Still a next track, still playing, still switched on — by whichever setting armed this one. */
    private fun stillWorthFading(): Boolean {
        val stillOn = if (smartFadeActive) AppSettings.smartFadeEnabled.value else configuredFadeMs() > 0L
        return stillOn && player.hasNextMediaItem()
    }

    private fun warmGhost(): ExoPlayer? {
        ghost?.let { return it }
        return runCatching { newGhost() }.getOrNull()?.also { ghost = it }
    }

    // ---- Numbers ------------------------------------------------------------

    private fun configuredFadeMs(): Long = AppSettings.crossfadeSeconds.value * 1000L

    /**
     * The configured length, kept off tracks too short to spend it on. A fade
     * that swallows a third of a song stops being a transition and starts being
     * the arrangement.
     */
    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    /**
     * Renders the plan's [TransitionStyle] as filtering across the blend.
     *
     * The gain curve is the same equal-power pair for every style — this is
     * what makes them sound different from each other, and it is the whole of
     * Phase 4. Driven off the same `progress` as the gains so the two stay
     * locked: a pause parks the filter exactly where it parks the fade.
     */
    private fun rideFilters(progress: Float) {
        when (render.style) {
            TransitionStyle.DJ_FILTER -> rideFilterSweep(progress)
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            // GAPLESS is an album being played through, where any filtering would
            // be an edit the record didn't ask for — so it stays open whatever
            // the material does.
            TransitionStyle.GAPLESS -> filters.open()
            // EQUAL_POWER used to be defined the same way: the bottom tier,
            // reached because the evidence was too weak to justify anything more
            // opinionated, therefore don't touch the spectrum.
            //
            // That conflated two different kinds of evidence. The tier is decided
            // by tempo and beat confidence; whether both tracks are singing is
            // measured by a separate model that doesn't depend on either. A pair
            // can have useless tempo evidence — dropping it to this tier — and a
            // perfectly good vocal mask on both sides saying they collide. Every
            // one of those transitions was rendered as a plain crossfade with two
            // full vocals over each other, because the weak half of the evidence
            // was silencing the strong half.
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
    }

    /**
     * The minimum intervention: pull two colliding vocals apart, and otherwise
     * leave the spectrum alone.
     *
     * Not a filter ride. [rideFilterSweep] is a *style* — a gesture chosen for a
     * pair that cannot be blended flat, driving to [FILTER_FLOOR_HZ] and taking
     * the outgoing track somewhere distant. This is damage control on a pair that
     * was going to be crossfaded plainly, and it has to stay subtle enough that a
     * listener notices the absence of the clash rather than the presence of a
     * filter. So it works the same way — complementary bands, outgoing losing its
     * top while the incoming enters with its body lifted — over a much shorter
     * distance, and only as far as the measured collision justifies.
     *
     * Zero overlap leaves both sides open, which is exactly what these styles did
     * before, so nothing changes for a pair that doesn't collide or for either
     * track lacking a vocal mask.
     */
    private fun rideVocalSeparation(progress: Float) {
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        // Both endpoints scaled by the collision, so a marginal clash is nudged
        // and a full one is properly separated, rather than everything getting
        // the same treatment at different speeds.
        val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        filters.outgoing(
            glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(),
            TransitionFilterProcessor.OFF_HZ,
        )
        filters.incoming(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    /**
     * Pulls the outgoing track behind a closing low-pass while the incoming one
     * arrives with its body lifted out, for a pair too far apart in tempo to
     * blend flat.
     *
     * ## Why both sides are filtered
     *
     * The first version filtered only the outgoing track, and squared the
     * progress so that the sweep was spent almost entirely in the second half.
     * Both halves of that were wrong for the same reason: at the midpoint the
     * outgoing cutoff was still at 6.9kHz — wide open across the whole vocal
     * range — and the incoming track was explicitly set to no filtering at all.
     * So for the entire first half of every transition, two complete vocals
     * played over each other at comparable level, and the only thing
     * distinguishing them was gain. That is what a plain crossfade sounds like,
     * which is the one thing this is meant not to be.
     *
     * What a DJ does instead is hand the midrange over rather than double it:
     * the outgoing track starts losing its top the moment the blend begins, and
     * the incoming one enters high-passed — hats and presence only, no vocal
     * body — opening out as the outgoing track darkens. The two occupy
     * complementary bands through the middle of the blend and never compete for
     * the range a voice lives in.
     *
     * [FILTER_SWEEP_SHAPE] is what replaces the squaring: front-loaded now, so
     * the outgoing track's top is gone within the first tenth of the blend
     * rather than somewhere past the midpoint. What keeps that from gutting the
     * track being left is [FILTER_FLOOR_HZ] — the ride settles onto a 300Hz bed
     * and stays there — not restraint in the early travel, which is the part the
     * listener reads as the transition happening at all.
     */
    private fun rideFilterSweep(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        // Both ends scaled by [filterSweep], so a partial sweep engages less
        // sharply *and* stops short of the floor rather than crawling the same
        // distance more slowly.
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        filters.outgoing(cutoff.toFloat(), TransitionFilterProcessor.OFF_HZ)
        filters.incoming(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, sweep, ENTRY_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    /**
     * Where the incoming track's high-pass sits at [progress].
     *
     * Rides from [topHz] down to nothing by [openBy] of the fade, so the track
     * is whole well before it is alone — the filter is there to keep it out of
     * the outgoing vocal's way during the overlap, not to colour the track the
     * listener is left with. [amount] scales the whole gesture, so a partial
     * sweep lifts proportionally less out.
     *
     * [ENTRY_SHAPE] is why the descent isn't linear. A geometric glide runs from
     * [TransitionFilterProcessor.OFF_HZ] to [topHz], and the bottom half of that
     * range is sub-bass nobody hears a filter in: measured, a plain ride was
     * down to 118Hz by a third of the way through, which is to say doing nothing
     * at all for two thirds of the overlap. The exponent spends the travel where
     * a voice actually is — 455Hz at a sixth of the way in, 270Hz at a third —
     * and still arrives at fully open on time.
     */
    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(TransitionFilterProcessor.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
            .toFloat()
    }

    /**
     * Geometric interpolation between two cutoffs: [amount] 0 gives [from], 1
     * gives [to].
     *
     * Geometric rather than linear because pitch is logarithmic — a cutoff
     * moving in equal Hz steps sounds like it lurches through the bottom of its
     * range and crawls through the top.
     */
    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    /**
     * Hands the low end from one track to the other, once, at the beat the
     * planner chose.
     *
     * Below [BASS_SWAP_HZ] exactly one track is present at any instant: the
     * incoming track arrives with its low end lifted out, and takes it over as
     * the outgoing track's is lifted in turn. Ramped over [BASS_SWAP_WIDTH] of
     * the fade rather than switched, because a 24 dB/octave filter appearing in
     * one buffer is a transient of its own.
     *
     * The midrange is handled far more lightly than in [rideFilterSweep] but is
     * no longer left alone, which it was. This style is chosen for pairs that
     * are beat-matched and close in tempo, so the two tracks are *meant* to
     * sound simultaneous — but "simultaneous" and "two lead vocals at once" are
     * not the same thing, and only the bass was ever being separated. So the
     * incoming track still enters with its body lifted, over a shorter window
     * and from a lower corner, and the outgoing track loses its top in the last
     * half, where it is already quiet enough that the change reads as it
     * receding rather than as an effect.
     */
    private fun rideBassSwap(progress: Float) {
        val swapAt = render.bassSwapFraction.coerceIn(0.05, 0.95)
        // 0 before the swap window, 1 after it: how much of the low end has
        // changed hands.
        val handover = ((progress - swapAt) / BASS_SWAP_WIDTH * 0.5 + 0.5).coerceIn(0.0, 1.0)
        // The incoming track's own low end is already being held out by the
        // swap, so whichever corner sits higher is the one doing the work.
        // Scaled up by however much the two are actually singing over each other.
        // A blend is chosen for pairs on a shared grid, which is the case where
        // nothing about the arrangement separates two lead vocals — they sit in
        // the same bar and the same range for the whole overlap — so the fixed
        // corner that was here handled a marginal collision and a head-on one
        // identically. At full collision the entry corner reaches
        // [BLEND_ENTRY_CLASH_HIGH_PASS_HZ] and holds longer.
        val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
        val entry = maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(
                progress,
                1.0,
                glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash,
            ),
        )
        filters.incoming(TransitionFilterProcessor.OPEN_HZ, entry)
        filters.outgoing(blendExitLowPass(progress, clash), bassCutoff(handover))
    }

    /**
     * The outgoing track's low-pass through a beat-matched blend: open until
     * [BLEND_EXIT_FROM], then closing to [BLEND_EXIT_LOW_PASS_HZ] by the end.
     *
     * Deliberately shallow. Enough to take the air and the sibilance off a voice
     * that is on its way out, so it stops competing with the one arriving;
     * nowhere near the [FILTER_FLOOR_HZ] that [rideFilterSweep] drives to, which
     * would contradict the reason this style was chosen.
     *
     * [clash] both starts it earlier and takes it further, because "shallow" is
     * the right default and the wrong answer for two choruses landing together.
     */
    private fun blendExitLowPass(progress: Float, clash: Double): Float {
        val from = BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    /** [amount] 0 leaves the low end alone; 1 lifts it out entirely. */
    private fun bassCutoff(amount: Double): Float =
        glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

    /**
     * Whether the transition in flight is doing something a plain crossfade
     * could not — which is what [AppSettings.smartMixInProgress] promises the
     * listener when it lights the scrubber up.
     *
     * Any one of three things qualifies, because they are the three things
     * analysis buys: a style that filters or swaps bass, an incoming track cued
     * into its arrangement instead of its first frame, or a tempo stretch. The
     * case this exists to exclude is the fallback — an unanalysed pair, cued at
     * 0:00, fading equal-power — which is indistinguishable from what the app
     * did before Smart Fade existed and would be a lie to advertise.
     */
    private fun isRealMix(): Boolean = smartFadeActive && (
        render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.DJ_FILTER ||
            incomingCueTimeMs > 0L ||
            incomingPlaybackRate != 1.0
        )

    /** Equal-power pair: [riseGain]² + [fallGain]² = 1, so the blend never dips. */
    private fun riseGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /**
     * Equal-*gain* pair for the lap: [lapRise] + [lapFall] = 1 exactly.
     *
     * The equal-power law above is the right one for two different tracks,
     * whose sum is a power sum. It is the wrong one here. During [Phase.LAPPING]
     * both players hold the same audio at the same position, so the two gains
     * add as amplitudes and `sin + cos` reaches √2 at the midpoint — a +3 dB
     * swell on the outgoing track at the head of every transition, which is a
     * thing that plainly does not happen when a song is left to play on its own.
     *
     * Raised cosine rather than a straight line so the ramp has no corner at
     * either end: over [LAP_MS] a linear pair's kinks are a modulation sharp
     * enough to hear as a tick, and shaping the pair costs nothing since it
     * still sums to one everywhere.
     */
    private fun lapRise(progress: Float): Float =
        (1f - cos(progress.coerceIn(0f, 1f) * PI.toFloat())) / 2f

    private fun lapFall(progress: Float): Float = 1f - lapRise(progress)

    private companion object {
        const val TAG = "BitChordCrossfade"

        /**
         * Used only before a pair has been analysed, or when the evidence is
         * too weak for more than a plain fade — see [considerSmartTransition].
         * Once real analysis lands, the overlap is sized from tempo and
         * structure instead and this is never read.
         */
        const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0

        /**
         * Handoff of the outgoing track between the two players.
         *
         * Was 90ms, which is long enough for a listener to resolve the two
         * copies as two copies rather than as one slightly thickened one. It is
         * the one window in a transition where doubled audio exists at all, so
         * it wants to be as short as a gain ramp can be without becoming a step:
         * 45ms is several times longer than the few milliseconds a raised-cosine
         * ramp needs to be click-free, and still gives [LAP_STEP_MS] enough ticks
         * to draw the curve rather than a staircase.
         */
        const val LAP_MS = 45L

        /** Ramp used when a fade is interrupted. */
        const val BAIL_MS = 120L

        /**
         * Head start the ghost gets to spin up and settle into sync.
         *
         * Was 2s, sized for the coarse stage alone. The fine stage spends real
         * time by design — absorbing 20ms of drift at [RATE_TRIM] takes half a
         * second, and it may need a second pass — and running out of head start
         * means lapping on the [ARM_TIMEOUT_MS] escape hatch with whatever drift
         * happened to be left, which is the behaviour being fixed. The extra
         * second is silent decoding of a track already in the cache.
         */
        const val ARM_LEAD_MS = 3_000L

        /**
         * States in which a track is measured well enough to be *entered* on.
         *
         * [TrackAnalysisState.REFINING] belongs here because the entry fields —
         * tempo, beat confidence, the cue point — are all measured over the
         * track's opening, which is precisely what a head-only pass reads. The
         * whole-track pass it is waiting on adds the *exit* half: content end,
         * outro, mix-out anchors, the energy curve. Those matter when this track
         * is later the one being left, and not at all for the transition into it.
         */
        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )

        /**
         * How closely the two players must agree on position before the lap.
         *
         * Was 20ms, which is the seek stage's floor and also, unhelpfully,
         * roughly where a delayed copy of a sound stops being heard as
         * colouration and starts being heard as a second copy. Below about
         * 10ms the two copies fuse; 8ms leaves margin for the couple of
         * milliseconds of jitter in a reported audio position without asking
         * the walk to chase noise it cannot remove.
         */
        const val SYNC_TOLERANCE_MS = 8L

        /**
         * Drift above which the walk seeks rather than trims. Comfortably clear
         * of a decoded frame — the seek stage's own resolution — so the coarse
         * stage is never asked to make a correction finer than it can land, and
         * a trim is never asked to cover a distance that would take it most of
         * a second.
         */
        const val COARSE_SYNC_MS = 40L

        /**
         * How far off the session player's rate the ghost is run to close the
         * last few milliseconds. Small enough that the time stretch never has to
         * do anything drastic, large enough to absorb a [COARSE_SYNC_MS] drift
         * inside the head start.
         */
        const val RATE_TRIM = 0.04f

        /** Ceiling on a single trim, so one bad reading cannot park the ghost off-rate. */
        const val MAX_TRIM_MS = 1_200L

        /** Time a seek is given to land before the resulting position is judged. */
        const val SEEK_SETTLE_MS = 250L

        /**
         * Time a lifted trim is given before the resulting position is judged.
         * Longer than a seek's, because what has to drain here is the audio
         * track's own buffer: the ghost keeps moving at the old rate for as long
         * as there is audio in it written at that rate.
         */
        const val TRIM_SETTLE_MS = 300L

        const val MAX_SEEK_LEAD_MS = 500L

        /**
         * Longest the lap will wait on a ghost that won't sync. Kept just past
         * [ARM_LEAD_MS] so a ghost that cannot be walked into place delays the
         * transition by half a second rather than by two.
         */
        const val ARM_TIMEOUT_MS = 3_500L

        /** How long the lap's own seek stays recognisable as ours. */
        const val SELF_MOVE_WINDOW_MS = 150L

        /**
         * Where the outgoing low-pass sits the instant a filter ride begins.
         *
         * The ride used to start from [TransitionFilterProcessor.OPEN_HZ] and
         * travel down, which meant the first stretch of every transition was
         * spent crossing a range nobody can hear a filter in: a tenth of the way
         * through the fade the cutoff was still at 17.5kHz, indistinguishable
         * from no filter at all, and the ride only became audible around the
         * midpoint. Engaging here instead — above the fundamentals of everything
         * but cymbals, so what goes first is air and shimmer — is what makes the
         * gesture read as a hand landing on the filter the moment the blend
         * starts, rather than something remembered late.
         *
         * 9kHz was the first attempt at that and still read as late by ear: it
         * is above everything but cymbals, so engaging there takes the air off
         * and nothing else, and the outgoing vocal — the thing actually clashing
         * — was untouched until the sweep had travelled most of the way down.
         * 7kHz is inside the presence range, so the gesture is audible on the
         * voice itself from the first instant.
         */
        const val FILTER_ENTRY_HZ = 7_000.0

        /**
         * The bottom of a filter ride. Below a few hundred hertz a track stops
         * reading as "further away" and starts reading as "broken", which is not
         * the impression a transition should leave of the song being left.
         */
        const val FILTER_FLOOR_HZ = 300.0

        /**
         * Where the low end is considered to end. Around the fundamental of a
         * bass guitar's upper register, and the usual corner on a mixer's bass
         * kill — high enough to clear the kick and the sub, low enough to leave
         * the body of the vocal alone.
         */
        const val BASS_SWAP_HZ = 200.0

        /** How much of the fade the low end takes to change hands. */
        const val BASS_SWAP_WIDTH = 0.10

        /**
         * Shape of the outgoing low-pass against fade progress, between
         * [FILTER_ENTRY_HZ] and [FILTER_FLOOR_HZ].
         *
         * Was 2.0 — squared — which left the cutoff at 6.9kHz at the midpoint,
         * so the outgoing vocal went untouched through the whole first half of
         * every transition. Then 1.3, which was still back-loaded: the exponent
         * held the cutoff near its entry point through the opening of the fade,
         * which is precisely where the two vocals overlap at comparable level.
         *
         * Below 1 now, so the ride is front-loaded — steepest at the start,
         * flattening as it approaches the floor. That is the shape of the gesture
         * being imitated: a hand moves a filter knob fast and then eases it in,
         * not the reverse. The old worry that a fast cutoff takes the outgoing
         * track out prematurely is answered by [FILTER_FLOOR_HZ] rather than by
         * the exponent — the ride bottoms out at 300Hz, which is still a present
         * bed under the incoming track, not silence.
         *
         * Crosses 5kHz — about where a low-pass becomes plainly audible on a
         * full-range mix — a twentieth of the way into the fade, against a
         * quarter of the way at 1.3. Lands at 3.8kHz a tenth of the way in,
         * 2.6kHz at a fifth, 1.0kHz at the midpoint.
         */
        const val FILTER_SWEEP_SHAPE = 0.75

        /**
         * Where the incoming track's high-pass starts on a filter ride.
         *
         * Above the fundamental range of most voices and the body of a snare, so
         * what arrives first is presence and percussion — enough to hear a track
         * coming and lock onto its groove, not enough for a second lead vocal.
         */
        const val ENTRY_HIGH_PASS_HZ = 700.0

        /**
         * How far into the fade the incoming track is fully open again.
         *
         * Comfortably before the end: past this point the outgoing track is deep
         * into its own sweep and quiet with it, so there is nothing left to keep
         * out of the way of, and anything still filtered here would just be the
         * new track arriving wrong.
         */
        const val ENTRY_OPEN_BY = 0.6

        /**
         * Shape of the incoming high-pass's descent; see [entryHighPass].
         *
         * Below 1 so the corner lingers in the range a voice occupies instead of
         * dropping straight through it into sub-bass, where a high-pass is
         * inaudible and the clash this exists to prevent is already back.
         */
        const val ENTRY_SHAPE = 0.45

        /**
         * How far [rideVocalSeparation] closes the outgoing track's top at a
         * full collision.
         *
         * Well above [FILTER_FLOOR_HZ]'s 300Hz, because this fires on pairs that
         * were going to be crossfaded plainly and the intent is to stop two
         * voices competing, not to send one of them into another room. 1.6kHz is
         * below the presence and sibilance a lead vocal is picked out by, and
         * above enough of its body that the track still reads as itself.
         */
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0

        /**
         * Where the incoming track's high-pass starts in [rideVocalSeparation].
         *
         * Lower than [ENTRY_HIGH_PASS_HZ]'s 700Hz, for the same reason the floor
         * is higher: on a plain crossfade the arriving track has no filter
         * gesture to explain itself with, so it has to sound like it fades in
         * normally. 450Hz clears the body of a voice while leaving its lower
         * harmonics, which is enough to stop it fighting the outgoing lead.
         */
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 450.0

        /** [ENTRY_HIGH_PASS_HZ]'s counterpart for a beat-matched blend: lower, and briefer. */
        const val BLEND_ENTRY_HIGH_PASS_HZ = 320.0

        /** [ENTRY_OPEN_BY]'s counterpart for a beat-matched blend. */
        const val BLEND_ENTRY_OPEN_BY = 0.45

        /**
         * Where [BLEND_ENTRY_HIGH_PASS_HZ] and [BLEND_ENTRY_OPEN_BY] go at a full
         * vocal collision: a corner high enough to hold the arriving voice's body
         * out, held for most of the blend rather than a third of it.
         *
         * Still short of [ENTRY_HIGH_PASS_HZ]'s filter-ride treatment. The two
         * tracks are on a shared grid and meant to sound simultaneous; the aim is
         * to stop the two leads occupying one band, not to hide either of them.
         */
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 620.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7

        /** Where [BLEND_EXIT_FROM] and [BLEND_EXIT_LOW_PASS_HZ] go at a full collision. */
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0

        /**
         * Where the outgoing track starts losing its top on a beat-matched
         * blend.
         *
         * Was 0.5, which left the outgoing track completely unfiltered for the
         * whole first half — the same "remembered late" complaint that
         * [FILTER_ENTRY_HZ] answers on a filter ride, in the one style where
         * both tracks are at their most similar and so most likely to clash.
         * Brought forward rather than to zero: a beat-matched blend is chosen
         * because the two tracks are meant to sound simultaneous, and opening
         * with the outgoing one already darkened would defeat that.
         */
        const val BLEND_EXIT_FROM = 0.3

        /**
         * Where that low-pass lands by the end of the blend. High enough that the
         * track is still plainly itself — this style is chosen for pairs meant to
         * sound simultaneous — and low enough to take the sibilance off a voice
         * that is leaving.
         */
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0

        const val IDLE_STEP_MS = 250L
        const val ARM_STEP_MS = 40L

        /** Halved with [LAP_MS], so the handoff still gets ~9 steps to ramp over. */
        const val LAP_STEP_MS = 5L
        const val FADE_STEP_MS = 30L
        const val BAIL_STEP_MS = 15L
    }
}
