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
 * What a moving picture puts behind the chrome, read off its frames, and
 * where that picture came from.
 *
 * The bands only, not a palette of the picture's colours: the ink wears the
 * record's own colour over a clip as it does over the sleeve, and the frames
 * answer only how light it goes and how much grade it needs — see
 * [inkOutcome]. A palette read off the frames re-themed the deck as the clip
 * cut from one colour to another.
 */
@Immutable
internal data class LivePalette(
    val source: LiveColorSource,
    /**
     * What a Canvas shows under the chrome — its frame under the deck's scrim —
     * for the ink to be judged against. Null for every other source: those
     * stand behind the sleeve's own backdrop, which publishes its own.
     */
    val surface: InkSurface? = null,
    /**
     * A Canvas's top rows under the header's scrim, for the "Playing from"
     * header's own ink — see [canvasHeaderInkSurface]. Null for other sources.
     */
    val header: InkSurface? = null,
)

/**
 * What a moving picture puts behind the chrome, read as it moves: the
 * lightest and darkest of each band the controls stand on. One copy of the
 * frame and a pass over its pixels, because this runs several times a second
 * while a clip is playing.
 *
 * The picture it reads is the one on screen, which is the whole point: the
 * controls must hold against what is behind them, and what is behind them is
 * a video.
 */
internal fun computeLivePalette(frame: ImageBitmap, source: LiveColorSource): LivePalette {
    if (frame.width <= 0 || frame.height <= 0) return LivePalette(source)
    val pixels = IntArray(frame.width * frame.height)
    frame.readPixels(pixels, startX = 0, startY = 0, width = frame.width, height = frame.height)
    return computeLivePalette(pixels, frame.width, frame.height, source)
}

/** [computeLivePalette] off a frame's pixels, already read. */
internal fun computeLivePalette(pixels: IntArray, width: Int, height: Int, source: LiveColorSource): LivePalette {
    // What the controls stand on when this picture is behind them: a Canvas
    // under its deck scrim, or a portrait motion cover under its own scrim.
    val surface = when (source) {
        LiveColorSource.CANVAS -> canvasInkSurface(pixels, width, height)
        LiveColorSource.LIVE_ART -> portraitClipInkSurface(bandBounds(pixels, width, height))
        LiveColorSource.STATIC_ART -> null
    }
    // A Canvas also owns the screen behind the header, under its scrim alone.
    val header = if (source == LiveColorSource.CANVAS) {
        canvasHeaderInkSurface(pixels, width, height)
    } else {
        null
    }
    return LivePalette(source, surface, header)
}

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
            if (previous != null && !inkMoved(previous, next)) return@collect
            live.value = next
        }
    }
    return live
}

/**
 * Whether what the chrome stands on has moved far enough to be worth judging
 * the ink again: a few levels of eight-bit colour in any band.
 */
internal fun inkMoved(previous: LivePalette, next: LivePalette): Boolean =
    inkSurfaceMoved(previous.surface, next.surface) ||
        // The header is judged on a band of its own: a bright patch can move
        // into the top of a Canvas while the deck's rows stand still.
        inkSurfaceMoved(previous.header, next.header)

/** Whether a surface's bands moved by more than a few levels of eight-bit colour. */
internal fun inkSurfaceMoved(previous: InkSurface?, next: InkSurface?): Boolean {
    if (previous == null || next == null) return previous !== next
    // Both sides: light ink is judged on [InkSurface.light], dark ink on
    // [InkSurface.dark], and either can move while the other stands still.
    return bandsMoved(previous.light, next.light) ||
        bandsMoved(previous.dark, next.dark) ||
        previous.darkOverlay != next.darkOverlay
}

private fun bandsMoved(previous: IntArray, next: IntArray): Boolean {
    if (previous.size != next.size) return true
    for (i in previous.indices) {
        val a = previous[i]
        val b = next[i]
        for (shift in intArrayOf(16, 8, 0)) {
            if (kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) > SURFACE_MOVE_LEVELS) return true
        }
    }
    return false
}

/** Three levels of eight-bit colour, about a percent: not a difference the eye picks out. */
private const val SURFACE_MOVE_LEVELS = 3

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
 * Four times a second. It used to be the picture's own rate — a read every
 * thirty-three milliseconds, nineteen reads a second, each one a copy of the
 * whole frame and, then, a clustering of a thousand of its pixels. What it bought
 * was an ink that landed on the picture's colour rather than on the colour it
 * had half a second ago, and the picture's colour does not move that fast:
 * a clip's average drifts over seconds, not over frames.
 *
 * A quarter of a second behind a drifting average is a difference nobody can
 * see, and it is a quarter of the reads.
 */
private const val PALETTE_MIN_GAP_LIVE_MS = 250L
