package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Where a set of live colours came from.
 *
 * Three sources, three answers, and the player asks each of them separately
 * because a picture is not the same thing as the sleeve that replaced it.
 */
internal enum class LiveColorSource {
    /**
     * The still sleeve, read once when it arrives.
     *
     * The sleeve's own answer, and the right one for every surface the sleeve
     * is actually on.
     */
    STATIC_ART,

    /**
     * The album art *as it is being drawn* — a clip playing over it.
     *
     * A moving picture and a still one are different pictures. A clip graded
     * black over a white sleeve is not a white picture with a black window in
     * it; it is a black picture, and the colours have to be read off the
     * clip rather than off the sleeve it is standing in for.
     */
    LIVE_ART,

    /**
     * The Spotify Canvas, read off its own frames as it plays.
     *
     * Its own source because its own rule: the Canvas owns the whole screen
     * when it is on, so nothing of the sleeve is left to be read.
     */
    CANVAS,
}

/**
 * A set of colours read off a picture, and where that picture came from.
 */
@Immutable
internal data class LivePalette(
    /** The ink, the alphas — see [NowPlayingPalette]. */
    val palette: NowPlayingPalette,
    val source: LiveColorSource,
)

/**
 * The colours of a moving picture, read as it moves.
 *
 * The same rules as the sleeve's own palette — a legible tint of the picture's
 * secondary colour, darkened or lightened by how bright the picture is — but
 * read from a frame rather than from a cover, and read *quickly*, because
 * this runs several times a second while a clip is playing.
 *
 * The picture it reads is the one on screen, which is the whole point: the
 * controls must belong to what is behind them, and what is behind them is a
 * video.
 */
internal fun computeLivePalette(frame: ImageBitmap, source: LiveColorSource): LivePalette =
    LivePalette(computeNowPlayingPalette(frame.liveSamples()), source)

/**
 * The colours of a moving picture, kept current as new frames arrive.
 *
 * The sampling happens off the main thread: a frame is read pixel by pixel,
 * and doing that on the thread that draws the screen is a frame of jank every
 * time the picture changes.
 *
 * Read on a clock of its own rather than once per frame, and that is the whole
 * point of this being separate from the backdrop's reads. The ink is a *tint*:
 * its colour changes far more slowly than the picture does, and every new
 * colour starts a tween that the next one interrupts. With live blur on, a
 * frame a second had become thirty, so the tween never finished — which left
 * every text and glyph in the player invalidated and redrawn on every frame the
 * clip produced, for a colour nobody could see moving.
 *
 * The clock is therefore two numbers, not one. With live blur off the read is
 * cheap and rare, and four hundred milliseconds is long enough for each colour
 * to land before the next is asked for. With live blur on the picture is being
 * read thirty times a second, and an ink that trails it by half a second is
 * the lag the listener sees: the controls are tinted with a colour the clip
 * left behind a moment ago. So the read comes at the picture's own rate, and
 * what keeps the cost down is not the clock but the *publish*: a read that
 * produces a colour nobody could see the difference of is not handed on at all
 * (see [inkMoved]), so the ink still only moves when it has really moved, and
 * every text wearing it is redrawn a handful of times a second rather than
 * thirty.
 *
 * The answer is handed back as a [MutableState] rather than as a value, and
 * the frames arrive as a [Flow] rather than as a parameter, for the same
 * reason: the palette changes while the player is open, and a value read in
 * the player's own composition would recompose the whole screen every time
 * the ink moved. The state is read by the ink's own scope instead — see
 * [rememberPlayerInk] — so a change of colour costs the texts and glyphs that
 * wear it and nothing else.
 */
@Composable
internal fun rememberLivePalette(
    frames: Flow<ImageBitmap?>,
    source: LiveColorSource,
): MutableState<LivePalette?> {
    val live = remember(source) { mutableStateOf<LivePalette?>(null) }
    // Live blur on: the ink follows the picture while it moves, which is what
    // the old live blur did and what the switch now promises. Off: the ink is
    // allowed to be a little behind, because the picture itself is only read
    // once a second anyway and there is nothing to follow.
    val liveBlur by PlayerSettings.liveBlur.collectAsStateWithLifecycle()
    LaunchedEffect(frames, source, liveBlur) {
        var lastReadAt = 0L
        val gapMs = if (liveBlur) PALETTE_MIN_GAP_LIVE_MS else PALETTE_MIN_GAP_MS
        frames.collect { current ->
            if (current == null) return@collect
            // The first frame is taken at once, so the ink belongs to the
            // picture from the player's first drawn frame rather than a fifth
            // of a second into it.
            val now = System.nanoTime()
            if (lastReadAt != 0L && now - lastReadAt < gapMs * 1_000_000L) return@collect
            lastReadAt = now
            val next = withContext(Dispatchers.Default) {
                computeLivePalette(current, source)
            }
            com.music.bitchord.data.PerfCounters.bump(
                com.music.bitchord.data.PerfCounters.Counter.INK_READ,
            )
            // Published only when the ink has actually moved. Reading the
            // picture thirty times a second is cheap; *changing* the ink
            // thirty times a second is not — each change starts a tween, and
            // every text and glyph wearing the ink is invalidated and redrawn
            // for its whole duration. A moving picture's average colour
            // drifts slowly, so most of those reads produce a colour nobody
            // could see the difference of, and publishing them is work for
            // nothing.
            val previous = live.value
            if (previous != null && !inkMoved(previous.palette, next.palette)) return@collect
            live.value = next
        }
    }
    return live
}

/**
 * Whether the ink has moved far enough to be worth a redraw.
 *
 * A difference of less than a percent of the range is under three levels of
 * eight-bit colour: not a difference the eye picks out of a tint, and not one
 * it would notice arriving a moment later either. The alphas are held to a
 * tighter tolerance, because they are legibility rather than colour — a
 * half-percent of opacity is the difference between a label that reads and
 * one that does not.
 */
private fun inkMoved(previous: NowPlayingPalette, next: NowPlayingPalette): Boolean {
    val a = previous.fg
    val b = next.fg
    if (
        kotlin.math.abs(a.red - b.red) > INK_MOVE_EPSILON ||
        kotlin.math.abs(a.green - b.green) > INK_MOVE_EPSILON ||
        kotlin.math.abs(a.blue - b.blue) > INK_MOVE_EPSILON ||
        kotlin.math.abs(a.alpha - b.alpha) > INK_MOVE_EPSILON
    ) {
        return true
    }
    val pa = previous.alphas
    val na = next.alphas
    return kotlin.math.abs(pa.primary - na.primary) > ALPHA_MOVE_EPSILON ||
        kotlin.math.abs(pa.secondary - na.secondary) > ALPHA_MOVE_EPSILON ||
        kotlin.math.abs(pa.tertiary - na.tertiary) > ALPHA_MOVE_EPSILON ||
        kotlin.math.abs(pa.track - na.track) > ALPHA_MOVE_EPSILON ||
        kotlin.math.abs(pa.buttonFill - na.buttonFill) > ALPHA_MOVE_EPSILON
}

/**
 * A bounded sample of a picture's pixels, in reading order.
 *
 * The copy is the only part that costs anything, and the picture it is copied
 * from is already small — a frame read off a clip is a few dozen pixels
 * across, not the screen. The sample is taken off that copy on a stride, so
 * the number of colours read is fixed however big the picture was, and the
 * order is kept: the last samples are the lower half of the picture, which is
 * where the controls stand.
 */
internal fun ImageBitmap.liveSamples(limit: Int = LIVE_SAMPLES): List<Int> {
    if (width <= 0 || height <= 0) return emptyList()
    val pixels = IntArray(width * height)
    readPixels(pixels, startX = 0, startY = 0, width = width, height = height)
    val step = (pixels.size / limit).coerceAtLeast(1)
    return buildList(minOf(pixels.size, limit)) {
        var index = 0
        while (index < pixels.size) {
            add(pixels[index])
            index += step
        }
    }
}

/**
 * How many pixels are read off a moving picture.
 *
 * Enough to tell a black clip from a white one and to find the colour that
 * belongs to it; few enough that the clustering behind it stays cheap.
 */
private const val LIVE_SAMPLES = 1_024

/**
 * How long between reads of a moving picture for the ink, with live blur off.
 *
 * Four hundred milliseconds, against the thirty frames a second the backdrop
 * may be reading at. The ink is a wash over the artwork, not the artwork: it
 * has to belong to the picture, and it has to stop moving so the controls
 * wearing it can stop being redrawn. This is the number that keeps live blur
 * from costing every text on the screen a redraw per frame.
 */
private const val PALETTE_MIN_GAP_MS = 400L

/**
 * The same read, with live blur on.
 *
 * The picture's own rate: a frame is read every thirty-three milliseconds, so
 * the ink is read at the same rate and lands on the picture's colour rather
 * than on the colour it had half a second ago. What stops this from costing
 * the same as the old per-frame ink is not this clock but the publish — see
 * [inkMoved] — so the texts and glyphs wearing the ink are still only redrawn
 * when the tint has really changed.
 */
private const val PALETTE_MIN_GAP_LIVE_MS = 33L

/** How far the ink has to move before it is published. See [inkMoved]. */
private const val INK_MOVE_EPSILON = 0.01f

/** How far an opacity has to move before it is published. See [inkMoved]. */
private const val ALPHA_MOVE_EPSILON = 0.005f
