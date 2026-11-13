/*
 * Copyright (C) 2026 BitChord
 */

package com.music.bitchord.ui.components

/**
 * How long after the sheet's last moving frame its glass stays frozen.
 *
 * This has to bridge the one stretch of "in motion, but with no frame to
 * stamp": an open from a tap stamps once and then waits up to 300ms for the
 * sheet to be laid out before its slide stamps every frame — see
 * [com.music.bitchord.ui.player.PlayerSheetMotion]'s layout wait. Past
 * that it is pure margin, and it doubles as the upper bound on how long a
 * freeze whose every clear was missed can last: half a second of held glass
 * reads as the sheet settling; a permanent one would read as glass broken.
 */
private const val GlassMotionFreezeWindowNs = 500_000_000L

/**
 * The "no motion yet" sentinel. Not 0: [System.nanoTime] is under no
 * contract to be nonzero, and a stamp taken at exactly zero must still read
 * as motion, not as nothing. [Long.MIN_VALUE] is a value no clock reaches
 * in practice — and even if one did, the loss would be one freeze that
 * reads as already settled, never one that sticks.
 */
private const val GlassMotionUnstamped = Long.MIN_VALUE

/**
 * The app-wide motion the glass freezes for, and the one place any part of
 * the UI can read it from without being handed a reference.
 *
 * The report this exists for: on low-end devices the glass janked through
 * the whole Now Playing sheet swipe. The swipe is exactly when the content
 * behind the glass changes every frame — the docked artwork flies between
 * the mini player and the player, in the app window, however the sheet is
 * being moved — so every glass surface was paying a full backdrop
 * re-record per frame of the spring, several surfaces deep. The vendored
 * Convx engine has always had the machinery to avoid that: every
 * [Modifier.liquidGlass] takes a `frozen` flag that holds the last capture
 * while set (fed, by default, from [LocalAppBackdropFrozen] — declared,
 * and before this file, driven by nothing). SpotiFLAC's glass makes the
 * same move by another door: their lens takes `interaction: false` for the
 * duration of a gesture. [sheet] is this app's driver, stamped from every
 * point where the player sheet actually moves — see
 * [com.music.bitchord.ui.player.PlayerSheetMotion], which owns all of
 * them, and the [Modifier.liquidGlass] delegate in `LiquidGlass.kt`, which
 * is the one reader.
 *
 * The trade this makes is intended, and must not be "fixed": while frozen,
 * glass shows the capture it already holds — a static snapshot, without
 * live refraction — so during the swipe the lens does not track what is
 * passing underneath it. A swipe that stays smooth is worth far more than
 * a lens that tracks the finger at the price of the swipe itself. The
 * freeze ends within [GlassMotionFreezeWindowNs] of the last moving frame,
 * and the engine's own thaw poll redraws the surface the moment it lifts
 * (see `scheduleThawRedraw` in `DrawBackdropModifier.kt`), so the glass
 * goes live again with nothing left over.
 */
object GlassMotion {

    /**
     * The Now Playing sheet's motion: an open from a tap, the pull up from
     * the mini player, every close, and the sheet's own drag under M3's
     * hand. True only while that motion is actually in flight or within
     * its settle margin — never merely because the player is open.
     */
    val sheet: GlassMotionClock = GlassMotionClock()

    /**
     * The navigation's motion: the folding bottom bar collapsing onto the
     * mini player, the search morph, and the slide between two pages.
     *
     * The same argument as the sheet's, on a smaller stage: a fold moves the
     * very content the bar's glass samples, so every frame of it was paying
     * a full backdrop re-record for a bar that is, for the length of the
     * fold, moving anyway. Holding the capture costs the lens its live
     * refraction for the length of the fold and buys the fold its frames.
     */
    val nav: GlassMotionClock = GlassMotionClock()

    /**
     * The page's own scroll: the listener's thumb moving a feed up or down.
     *
     * The same argument as the sheet's and the nav's, on the largest stage
     * there is. A scroll moves the very content every glass surface samples —
     * the nav bar's lens, the mini player's — so every frame of it was paying
     * a full backdrop re-record for surfaces nobody is reading mid-scroll.
     * The vendored engine measured that cost on a screen of its own: 47ms of
     * `layer.record` per scroll frame with the freeze off, 1.9ms with it on.
     *
     * Its own clock rather than [nav]'s, because [nav] is settled by the
     * fold's animation — and the fold *is* driven by the scroll, so settling
     * it at the end of the fold would lift the freeze in the middle of the
     * very scroll the freeze exists for.
     *
     * The trade is the sheet's: while frozen, the lens shows the capture it
     * already holds rather than tracking what passes underneath it. A scroll
     * that keeps its frames is worth more than a lens that tracks the thumb.
     */
    val scroll: GlassMotionClock = GlassMotionClock()
}

/**
 * A nanosecond clock that answers "did motion happen within the last
 * [freezeWindowNs]?" — glass freezes while it does.
 *
 * Why a plain clock, and neither `mutableStateOf` nor a `MutableStateFlow`:
 * `frozen` is consulted in the *draw* phase, where a snapshot read
 * registers a draw dependency and every write re-invalidates every frame —
 * the trap documented at the top of the vendored
 * [com.music.bitchord.ui.components.backdrop.backdrops.BackdropFreeze],
 * whose scroll freeze this mirrors on purpose. Nothing collects this
 * state; the backdrop node polls it and polls its way back out, so
 * observable state would only have added invalidations to a mechanism
 * that wants none.
 *
 * Why a window rather than a flag that is set and cleared: a flag sticks
 * true the first time some path forgets to clear it, and the clears live
 * in coroutines that get cancelled. This construction cannot stick: being
 * frozen *is* "recently stamped", so it is false at most
 * [freezeWindowNs] after the last moving frame whatever any code failed
 * to call — while [moved] is stamped once per frame of motion, so the
 * freeze still covers a gesture however long it runs. Explicit [settled]
 * calls end it early; the window is the safety net, not the mechanism.
 *
 * [lastMotionAtNs] is `@Volatile` although every stamp and every poll runs
 * on the main thread in today's app — the cost of the guarantee is one
 * word, and the class is the shared state of a whole UI, not a private of
 * the thread that happened to write it first.
 */
class GlassMotionClock(
    private val freezeWindowNs: Long = GlassMotionFreezeWindowNs,
    private val now: () -> Long = System::nanoTime,
) {

    /** The instant of the last moving frame; [GlassMotionUnstamped] until one. */
    @Volatile
    private var lastMotionAtNs = GlassMotionUnstamped

    /**
     * A frame of motion happened: freeze glass from now until
     * [freezeWindowNs] after the next one.
     */
    fun moved() {
        lastMotionAtNs = now()
    }

    /** Motion is over — thaw now, rather than waiting out the window. */
    fun settled() {
        lastMotionAtNs = GlassMotionUnstamped
    }

    /**
     * Whether glass should hold its last capture: motion ran recently
     * enough that the content behind it is still moving.
     */
    fun frozen(): Boolean {
        val at = lastMotionAtNs
        return at != GlassMotionUnstamped && now() - at < freezeWindowNs
    }
}
