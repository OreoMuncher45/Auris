package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * The mini player the expanded player docks into, for a host that has one.
 *
 * Opening and closing the player is one movement on screen: the sheet's
 * artwork travels between its own place and the mini player's cover while the
 * rest of the player fades — see the sleeve in [NowPlayingScreen]. The player
 * and the mini player are one panel in the app's own window (see the app's
 * `PlayerPanel`), grown from the pill's rect to the whole window; the two ends
 * of the movement are still each reported by the side that owns it, and met
 * in screen coordinates.
 *
 * The panel half of this — the pill's rect, [panelBounds], [panelCornerPx],
 * [panelFill] — is ported from Zenith (rezapranata089-bit/Zenith, GPL-3.0),
 * a sibling fork of BitChord.
 *
 * Read in layout and draw only. Nothing in it is worth a recomposition: a
 * frame of the sheet's travel moves the artwork and re-tints a layer, and that
 * is all it does.
 */
@Stable
class PlayerDock {
    /**
     * The mini player's cover, as last laid out. Held as coordinates rather than
     * as a rect, and only turned into one when the player asks: the bar moves
     * on every scroll that folds it, and working out a screen position for each
     * of those frames would be paid for by a player that isn't open.
     */
    private var miniArt: LayoutCoordinates? = null
    private var miniArtCorner: Dp = 8.dp

    /**
     * How far the player sheet sits below fully open, in pixels. Set by the
     * host while the sheet is up, null while it is not. `NaN` before the sheet
     * has been laid out.
     */
    var sheetOffset: (() -> Float)? by mutableStateOf(null)

    /** How far the sheet travels from fully open to gone, in pixels. */
    var sheetTravel by mutableFloatStateOf(0f)

    /**
     * Whether the player on screen takes part. Only its portrait shape does —
     * a landscape player has no sleeve that could become a cover, and keeps
     * the plain slide.
     */
    var attached by mutableStateOf(false)
        internal set

    /** Reported by the mini player's cover whenever it is placed. */
    fun reportMiniArt(coordinates: LayoutCoordinates, corner: Dp) {
        miniArt = coordinates
        miniArtCorner = corner
    }

    /** From the cover leaving composition, so a stale rect is never flown to. */
    fun releaseMiniArt(coordinates: LayoutCoordinates) {
        if (miniArt === coordinates) miniArt = null
    }

    internal fun hasMiniArt(): Boolean = miniArt?.isAttached == true

    /**
     * The mini player's title and artist, as last laid out.
     *
     * The other end of the credits' journey. The player's own title leaves
     * the screen when the sheet closes and arrives in the bar, and the bar's
     * text is in another window: the two are met in screen coordinates, the
     * same way the cover is.
     */
    private var miniText: LayoutCoordinates? = null
    private var miniTextTitlePx: Float = 0f

    /**
     * Reported by the mini player's text whenever it is placed.
     *
     * [titleSizePx] is the size of the bar's title in pixels, so the title
     * that flies there can be scaled to meet it rather than to arrive beside
     * it.
     */
    fun reportMiniText(coordinates: LayoutCoordinates, titleSizePx: Float) {
        miniText = coordinates
        miniTextTitlePx = titleSizePx
    }

    /** From the text leaving composition, so a stale rect is never flown to. */
    fun releaseMiniText(coordinates: LayoutCoordinates) {
        if (miniText === coordinates) {
            miniText = null
            miniTextTitlePx = 0f
        }
    }

    internal fun hasMiniText(): Boolean = miniText?.isAttached == true

    /** The bar's title and artist on screen, for the credits to land on. */
    internal fun miniTextOnScreen(): Rect? {
        val coordinates = miniText?.takeIf { it.isAttached } ?: return null
        val size = coordinates.size.toSize()
        return Rect(
            coordinates.localToScreen(Offset.Zero),
            coordinates.localToScreen(Offset(size.width, size.height)),
        )
    }

    /** The bar's title size in pixels — see [reportMiniText]. */
    internal fun miniTextTitlePx(): Float = miniTextTitlePx

    /**
     * Both corners mapped through every transform above the cover, not its
     * position plus its laid-out size: the glass bar draws its player scaled,
     * and the artwork landed on a cover a fifth bigger than the one on screen.
     */
    internal fun miniArtOnScreen(): Rect? {
        val coordinates = miniArt?.takeIf { it.isAttached } ?: return null
        val size = coordinates.size.toSize()
        return Rect(
            coordinates.localToScreen(Offset.Zero),
            coordinates.localToScreen(Offset(size.width, size.height)),
        )
    }

    internal fun miniArtCorner(): Dp = miniArtCorner

    // ---- The one panel: the mini player's pill and the full player ----
    // The pill's rect (at rest, without the pull lift) is where the player
    // starts and ends; the panel is a rounded rect that grows from that pill
    // to the whole window — see [panelBounds].
    private var miniPill: LayoutCoordinates? = null
    private var originW = -1
    private var originH = -1

    /** Where the panel's window starts on screen. */
    private var windowOrigin: Offset = Offset.Zero

    // Dozens of layout, graphics-layer and draw blocks ask for the pill every
    // frame of an open or close, and each ask walks the coordinate chain twice.
    // The pill does not move during the trip, so one answer serves the frame.
    private var pillCache: Rect? = null
    private var pillCacheNanos = 0L

    /** Reported by the mini player's pill whenever it is placed. */
    fun reportMiniPill(coordinates: LayoutCoordinates) {
        miniPill = coordinates
        pillCache = null
    }

    /** From the pill leaving composition, so a stale rect is never grown from. */
    fun releaseMiniPill(coordinates: LayoutCoordinates) {
        if (miniPill === coordinates) {
            miniPill = null
            pillCache = null
        }
    }

    /** The pill in the panel window's own pixels, or null with no pill on screen. */
    fun miniPillInWindow(): Rect? {
        val c = miniPill?.takeIf { it.isAttached } ?: return null
        val now = System.nanoTime()
        pillCache?.let { if (now - pillCacheNanos < PILL_CACHE_NANOS) return it }
        val size = c.size.toSize()
        return Rect(
            c.localToScreen(Offset.Zero) - windowOrigin,
            c.localToScreen(Offset(size.width, size.height)) - windowOrigin,
        ).also {
            pillCache = it
            pillCacheNanos = now
        }
    }

    /**
     * How far the player travels between fully open and docked on the pill:
     * the pill's top edge. Falls back to the whole window with no pill.
     */
    fun travel(): Float {
        val top = miniPillInWindow()?.top
        return if (top != null && top > 1f) top else sheetTravel
    }

    /**
     * True while the single panel stands where the mini player's pill is, so
     * the pill itself stands aside instead of showing as a second panel.
     */
    var panelShown by mutableStateOf(false)
        private set

    /**
     * The panel's plain fill while it grows out of the pill: taken from the
     * track's cover by the host, a near-black before that is known.
     */
    var panelColor by mutableStateOf(PANEL_FALLBACK_COLOR)

    /** From the panel's root, once placed: where its window starts on screen. */
    fun panelPlaced(coordinates: LayoutCoordinates) {
        val origin = coordinates.localToScreen(Offset.Zero)
        if (origin != windowOrigin) pillCache = null
        windowOrigin = origin
        originW = coordinates.size.width
        originH = coordinates.size.height
        panelShown = true
    }

    /** The pill comes back. Called as the panel goes, not after it has. */
    fun releasePanel() {
        panelShown = false
    }

    private fun panelProgress(pill: Rect): Float =
        (1f - (offset() ?: 0f) / pill.top).coerceIn(0f, 1f)

    /**
     * The panel's rect this frame, in the window's own pixels. At the bottom
     * of its travel it is the pill's rect; fully open, the whole window. In
     * between its left and right close in on the pill, its top follows the
     * player's offset, and its bottom rises to meet the pill: one shape
     * changing size. With no pill to grow from it is the whole window,
     * sliding as one.
     */
    fun panelBounds(window: Size): Rect {
        val offset = offset() ?: 0f
        val pill = miniPillInWindow()
        if (pill == null || pill.top <= 1f) {
            return Rect(0f, offset, window.width, window.height + offset)
        }
        val progress = panelProgress(pill)
        // The width catches up faster than the rise, so the screen's corners
        // are covered well before halfway.
        val wide = panelWideEase(progress)
        val left = pill.left * (1f - wide)
        val right = window.width - (window.width - pill.right) * (1f - wide)
        val bottom = pill.bottom + (window.height - pill.bottom) * progress
        return Rect(left, offset, right.coerceAtLeast(left + 1f), bottom.coerceAtLeast(offset + 1f))
    }

    /** 0 on the pill (all glass), 1 once the plain panel has fully covered the glass. */
    fun panelFill(until: Float = PANEL_FILL_UNTIL): Float = (openFraction() / until).coerceIn(0f, 1f)

    /** The panel's corner radius this frame: the pill's, rounding off to square. */
    fun panelCornerPx(sheetCornerPx: Float = 0f): Float {
        val pill = miniPillInWindow() ?: return 0f
        if (pill.top <= 1f) return 0f
        val progress = panelProgress(pill)
        val pillCorner = pill.height / 2f * (1f - progress)
        // The top corners stay rounded while the panel rises, as a sheet's
        // do, and square off only as it covers the whole screen.
        val sheetCorner = sheetCornerPx *
            (progress / PANEL_WIDE_AT).coerceIn(0f, 1f) *
            (1f - ((progress - 0.9f) / 0.1f).coerceIn(0f, 1f))
        return maxOf(pillCorner, sheetCorner)
    }

    /** The sheet's offset this frame, or null with no sheet laid out. */
    internal fun offset(): Float? = sheetOffset?.invoke()?.takeUnless { it.isNaN() }

    /**
     * 1 with the player fully open, 0 down on the mini player. 0 too while
     * there is no sheet yet, which is where an opening player starts.
     */
    fun openFraction(): Float {
        val travel = travel()
        val offset = offset() ?: return 0f
        if (travel <= 0f) return 0f
        return (1f - offset / travel).coerceIn(0f, 1f)
    }

    /**
     * Whether the player is fully open **and has stopped moving**.
     *
     * The answer work needs before it may touch the screen: the player's own
     * heavy layouts — the lyrics list, the queue — must not be laid out in the
     * middle of the sheet's slide up, because a list laid out mid-slide is a
     * dropped frame in the middle of the slide.
     *
     * Derived, so a reader hears about it once — as the sheet arrives — rather
     * than on every frame of the travel. It reads the fraction rather than the
     * app's own motion, so it answers for the sheet's own animation too (the
     * one M3 drives when "reduce animation" is on) and not only for the one
     * the app drives.
     */
    val settled: Boolean get() = settledState.value

    private val settledState = derivedStateOf { openFraction() >= 1f }

    /**
     * Whether the mini player's own cover should stand aside, because the
     * player's is on its way to or from it. Not before the sheet has moved at
     * all: the sheet's window can land a frame after the page's, and a cover
     * hidden in that frame is a hole in the bar.
     */
    val coversMiniArt: Boolean
        get() = coversMiniArtState.value

    // Derived, so a reader hears about it twice a transition — when it flips
    // — rather than on every frame of the sheet's travel. Read straight off
    // the fraction, the cover's layer re-ran every frame of an open or a close,
    // and every one of those invalidated the page's whole window behind the
    // player: a full redraw, liquid glass and all, for a cover that hadn't
    // changed.
    //
    // Also while the panel stands on the pill, even fully down: the panel's own
    // row shows a cover of its own, and at the end of a close — the player at
    // the bottom but not yet gone — it was a second cover beside the one
    // flying in.
    private val coversMiniArtState = derivedStateOf { attached && (openFraction() > 0f || panelShown) }
}

/** How long one measurement of the mini pill stands, in nanoseconds: under a frame. */
private const val PILL_CACHE_NANOS = 8_000_000L

/** The share of the opening over which the glass gives way to the plain panel. */
private const val PANEL_FILL_UNTIL = 0.2f

/** How far open the panel is once it is the window's full width. */
private const val PANEL_WIDE_AT = 0.8f

/** The panel's fill before the cover's colour is known. */
val PANEL_FALLBACK_COLOR = Color(0xFF151517)

/** The panel's width curve: 0 on the pill, 1 once it is the window's width. */
internal fun panelWideEase(t: Float): Float {
    val x = (t / PANEL_WIDE_AT).coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x)
}

/**
 * Leaves the page undrawn while the portrait player fully covers it: its own
 * background is opaque, so everything drawn under it (glass bars, blur, lists)
 * would be paid for every frame for nobody to see. Layout, composition and
 * input are untouched; only drawing stops.
 *
 * Put on the page, never on a parent of the player panel, or the panel would
 * be skipped with it. The flag is derived, so the draw is invalidated twice
 * per open or close — when it flips — and not on every frame of the travel.
 */
@Composable
fun Modifier.skipDrawWhenPlayerCovers(dock: PlayerDock?): Modifier {
    val covered = remember(dock) {
        derivedStateOf { dock != null && dock.attached && dock.openFraction() >= 1f }
    }
    return drawWithContent { if (!covered.value) drawContent() }
}

/** The [PlayerDock] around the player, if its host has a mini player to dock into. */
val LocalPlayerDock = staticCompositionLocalOf<PlayerDock?> { null }
