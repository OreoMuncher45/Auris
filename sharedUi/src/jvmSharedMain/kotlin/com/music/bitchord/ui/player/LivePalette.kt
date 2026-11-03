package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
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
 * time the picture changes. A new frame cancels the sample of the old one —
 * only the newest picture is worth an answer — so a fast clip cannot pile
 * work up behind itself.
 */
@Composable
internal fun rememberLivePalette(
    frame: ImageBitmap?,
    source: LiveColorSource,
): LivePalette? {
    var live by remember(source) { mutableStateOf<LivePalette?>(null) }
    LaunchedEffect(frame, source) {
        val current = frame ?: return@LaunchedEffect
        val sampled = withContext(Dispatchers.Default) { computeLivePalette(current, source) }
        live = sampled
    }
    return live
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
