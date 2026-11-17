package com.music.bitchord.ui.replay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.player.rememberArtworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Replay as a record you play, stop and read.
 *
 * ## Why it is a record and not a story
 *
 * The stories that used to open from the Replay card were eight full-screen
 * facts in a row, and each one asked to be read rather than looked at. A record
 * asks for the opposite: one object, one gesture, and the whole year already on
 * it. Stopping the disc is the first thing anybody does with a turntable, and it
 * makes the numbers arrive as a consequence of that gesture instead of as the
 * next card.
 *
 * ## The three things a tap does
 *
 * The first tap drops the needle and stops the platter. After that every tap
 * moves the camera to a different part of the same disc — the month that took
 * the most listening, the artist who led the most bands, the hour the needle
 * spent the longest on, the single deepest day — and the plaque under it says
 * what that part is. The closing card pulls back to the whole record and offers
 * the two things worth doing with it: keep it as a poster, or play the songs it
 * was pressed from.
 *
 * ## What is drawn vs what is measured
 *
 * Nothing here is decoration dressed up as data. A band's thickness is its share
 * of the pressing, an arc's sweep is that hour's share of its month, and a
 * plaque's numbers are read straight off [ReplaySummary] and the month buckets.
 * The only invented things are the ink colours and the light.
 */
@Composable
fun RecordStage(
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    onClose: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    /** Off for people who have asked the system for less movement. */
    reduceMotion: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var press by remember { mutableStateOf<RecordPress?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showPoster by remember { mutableStateOf(false) }
    var poster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var posterBusy by remember { mutableStateOf(false) }

    LaunchedEffect(summary) {
        press = withContext(Dispatchers.IO) { loadRecordPress(summary, holder, memberSince) }
    }

    val loaded = press
    if (loaded == null || loaded.isEmpty) {
        RecordLoading(onClose = onClose)
        return
    }

    val chapters = remember(loaded) { loaded.chapters }
    val chapter = chapters.getOrElse(step) { chapters.last() }
    val ranges = remember(loaded) { bandRange(loaded.bands) }
    val palette = rememberArtworkColors(summary.songs.firstOrNull()?.song?.thumbnailUrl)
    val topSong = summary.songs.firstOrNull()?.song

    // The platter: free-running while the record is playing, a short run-down
    // when it is stopped. One effect owns the angle so the two states cannot
    // both be animating it.
    val angle = remember { Animatable(0f) }
    val spinning = step == 0 && !reduceMotion
    LaunchedEffect(spinning) {
        if (spinning) {
            while (isActive) {
                angle.animateTo(angle.value + 360f, tween(PLATTER_MS, easing = LinearEasing))
            }
        } else if (angle.value % 360f != 0f) {
            angle.animateTo(angle.value + 24f, tween(1_100, easing = FastOutSlowInEasing))
        }
    }

    // Camera: three numbers, each eased to the chapter's target so a tap glides
    // instead of cutting. Kept normalized (zoom, groove fraction, day angle) so
    // the values do not depend on a layout that is only known at draw time.
    val zoom = remember { Animatable(1f) }
    val focusR = remember { Animatable(0.55f) }
    val focusAngle = remember { Animatable(-90f) }
    LaunchedEffect(chapter.id) {
        coroutineScope {
            launch { zoom.animateTo(chapter.zoom, tween(900, easing = FastOutSlowInEasing)) }
            launch { focusR.animateTo(chapter.focusFraction(ranges), tween(900, easing = FastOutSlowInEasing)) }
            launch {
                focusAngle.animateTo(
                    chapter.focusAngleDegrees() ?: focusAngle.value,
                    tween(900, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(RecordInk.Stage)) {
        // A single soft glow in the record's own colours, so the stage belongs
        // to this year's music without a picture of it anywhere on screen.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            (palette.colors.getOrNull(0) ?: RecordInk.Glow).copy(alpha = 0.30f),
                            Color.Transparent,
                        ),
                        center = Offset(0.5f, 0.34f),
                        radius = 1_100f,
                    ),
                ),
        )

        val measurer = rememberTextMeasurer()

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = if (step == 0) {
                        "The record is playing. ${chapter.headline} of listening."
                    } else {
                        "${chapter.kicker}. ${chapter.headline}. ${chapter.caption}"
                    }
                }
                .pointerInput(step, chapters.size) {
                    detectTapGestures {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        if (step < chapters.lastIndex) step++ else onClose()
                    }
                },
        ) {
            drawRecordStage(
                press = loaded,
                ranges = ranges,
                chapter = chapter,
                palette = palette.colors,
                angle = angle.value,
                zoom = zoom.value,
                focusR = focusR.value,
                focusAngle = focusAngle.value,
                measurer = measurer,
            )
        }

        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = RecordInk.Cream)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chapters.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == step) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (i <= step) RecordInk.Cream else RecordInk.Cream.copy(alpha = 0.25f)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp))
        }

        // Plaque
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedContent(
                targetState = chapter,
                transitionSpec = {
                    (fadeIn(tween(420)) + slideInVertically(tween(520)) { it / 8 })
                        .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 12 })
                },
                label = "plaque",
            ) { shown ->
                ChapterPlaque(
                    chapter = shown,
                    step = step,
                    last = chapters.lastIndex,
                    holder = holder,
                    onAdvance = { if (step < chapters.lastIndex) step++ else onClose() },
                    onExport = {
                        if (!posterBusy) {
                            posterBusy = true
                            showPoster = true
                            scope.launch {
                                val bmp = withContext(Dispatchers.Default) {
                                    runCatching { renderRecordPoster(loaded, palette.colors.map { c -> c.toArgb() }) }.getOrNull()
                                }
                                poster = bmp
                                posterBusy = false
                            }
                        }
                    },
                    onPlaylist = { showPlaylist = true },
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showPlaylist) {
        RecordPlaylistSheet(
            songs = summary.songs.sortedByDescending { it.plays },
            onDismiss = { showPlaylist = false },
            onPlaySong = {
                showPlaylist = false
                onPlaySong(it)
            },
            onPlayAll = {
                showPlaylist = false
                onPlayAll(summary.songs.sortedByDescending { it.plays }.map { row -> row.song })
            },
        )
    }

    if (showPoster) {
        RecordPosterSheet(
            bitmap = poster,
            busy = posterBusy,
            onDismiss = { showPoster = false },
        )
    }

}

/** How far round the platter one revolution takes on screen. */
private const val PLATTER_MS = 7_000

// ── Geometry ────────────────────────────────────────────────────────────────

/**
 * The disc's fixed parts, as a fraction of the drawing box.
 *
 * The centre sits above the middle because the plaque takes the bottom third;
 * pulling back further would leave the record small on a phone, and a record is
 * the one thing on this screen that has to look its size.
 */
private class DiscGeometry(size: Size) {
    val disc = min(size.width, size.height) * 0.46f
    val center = Offset(size.width / 2f, size.height * 0.39f)
    val label = disc * 0.30f
    val outer = disc * 0.975f

    fun radius(fraction: Float): Float = label + fraction.coerceIn(0f, 1f) * (outer - label)

    /** A point in the groove area at a fraction of the way out and an angle in degrees. */
    fun point(fraction: Float, degrees: Float): Offset {
        val r = radius(fraction)
        val rad = Math.toRadians(degrees.toDouble())
        return Offset(center.x + (r * cos(rad)).toFloat(), center.y + (r * sin(rad)).toFloat())
    }
}

/** Where a chapter's camera points, as a groove fraction. */
private fun RecordChapter.focusFraction(ranges: List<ClosedFloatingPointRange<Float>>): Float {
    val band = focusBand ?: return 0.55f
    val range = ranges.getOrNull(band) ?: return 0.55f
    return (range.start + range.endInclusive) / 2f
}

/** Where a chapter's camera points, as a screen angle; null keeps the current one. */
private fun RecordChapter.focusAngleDegrees(): Float? {
    val hour = focusHour ?: return null
    return (hour / 1_440f) * 360f - 90f
}

// ── Drawing ─────────────────────────────────────────────────────────────────

private fun DrawScope.drawRecordStage(
    press: RecordPress,
    ranges: List<ClosedFloatingPointRange<Float>>,
    chapter: RecordChapter,
    palette: List<Color>,
    angle: Float,
    zoom: Float,
    focusR: Float,
    focusAngle: Float,
    measurer: TextMeasurer,
) {
    val g = DiscGeometry(size)
    val focus = g.point(focusR, focusAngle)
    val anchor = Offset(size.width / 2f, size.height * 0.39f)
    val tx = anchor.x - focus.x * zoom
    val ty = anchor.y - focus.y * zoom

    // The shadow the record casts on the page, in screen space so it does not
    // swim when the camera moves.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(g.center.x, g.center.y + g.disc * 0.08f),
            radius = g.disc * 1.25f,
        ),
        radius = g.disc * 1.25f,
        center = Offset(g.center.x, g.center.y + g.disc * 0.08f),
    )

    withTransform({
        translate(tx, ty)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        rotate(angle, pivot = g.center) {
            drawDiscBody(g, palette)
            drawBands(g, press, ranges, chapter, palette)
            drawGuides(g, press, ranges, measurer)
            drawLabel(g, press, measurer, angle)
        }
    }

    drawSheen(g, zoom, tx, ty)
    drawTonearm(g, focus, zoom, tx, ty, chapter, press, ranges)
}

/** Vinyl: a body with a coloured edge, fine grooves, and the run-out before the label. */
private fun DrawScope.drawDiscBody(g: DiscGeometry, palette: List<Color>) {
    val edge = palette.getOrNull(0) ?: RecordInk.Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF23212A), Color(0xFF14131A), Color(0xFF07070B)),
            center = g.center,
            radius = g.disc,
        ),
        radius = g.disc,
        center = g.center,
    )
    drawCircle(
        brush = Brush.sweepGradient(
            listOf(
                edge.copy(alpha = 0.22f),
                Color.Transparent,
                (palette.getOrNull(1) ?: RecordInk.Glow).copy(alpha = 0.18f),
                Color.Transparent,
                edge.copy(alpha = 0.22f),
            ),
            center = g.center,
        ),
        radius = g.disc,
        center = g.center,
        style = Stroke(width = g.disc * 0.012f),
    )

    // Texture: hairlines at a fixed pitch, slightly irregular like a pressed
    // disc rather than a generated one.
    var r = g.label + g.disc * 0.02f
    var i = 0
    while (r < g.outer) {
        val jitter = 1f + ((i * 37) % 11) * 0.004f
        drawCircle(
            color = Color.White.copy(alpha = if (i % 3 == 0) 0.035f else 0.018f),
            radius = r * jitter,
            center = g.center,
            style = Stroke(width = 0.7f),
        )
        r += g.disc * 0.0052f
        i++
    }
    // The run-out: a smooth dark band before the label.
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = g.label * 1.06f,
        center = g.center,
        style = Stroke(width = g.disc * 0.012f),
    )
}

/**
 * The data: for each band, one arc per hour that has listening in it.
 *
 * The angle is the time of day and nothing else, which is what makes the shape
 * readable — every month's late nights line up, and a habit shows as a bright
 * wedge in the same place on every band. Sweep is the hour's share of that
 * month, so a quiet hour is a stub and the loudest one fills its fifteenth of
 * the circle.
 */
private fun DrawScope.drawBands(
    g: DiscGeometry,
    press: RecordPress,
    ranges: List<ClosedFloatingPointRange<Float>>,
    chapter: RecordChapter,
    palette: List<Color>,
) {
    val focusBand = chapter.focusBand
    val artistLed = if (chapter.id == "artist") press.artistBands.toSet() else emptySet()

    press.bands.forEachIndexed { bandIndex, band ->
        val range = ranges.getOrNull(bandIndex) ?: return@forEachIndexed
        val r = g.radius((range.start + range.endInclusive) / 2f)
        val thickness = ((range.endInclusive - range.start) * (g.outer - g.label)).coerceAtLeast(1.2f) * 0.86f
        val maxHour = band.hours.maxOrNull()?.takeIf { it > 0L } ?: return@forEachIndexed
        val inFocus = focusBand == null || focusBand == bandIndex || bandIndex in artistLed

        band.hours.forEachIndexed { hour, ms ->
            if (ms <= 0L) return@forEachIndexed
            val share = (ms.toFloat() / maxHour).coerceIn(0.05f, 1f)
            val start = (hour / 24f) * 360f - 90f
            val sweep = 15f * share
            val night = hour >= 22 || hour < 4
            val ink = when {
                bandIndex in artistLed -> RecordInk.Cream
                night -> palette.getOrNull(2) ?: RecordInk.Night
                else -> palette.getOrNull(1) ?: RecordInk.Day
            }
            val baseAlpha = if (inFocus) 0.34f + 0.55f * share else 0.10f
            drawArc(
                color = ink.copy(alpha = baseAlpha),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(g.center.x - r, g.center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = thickness, cap = StrokeCap.Butt),
            )
            // A hairline on the leading edge, so a dense band still reads as
            // grooved rather than as a solid ring.
            drawArc(
                color = Color.White.copy(alpha = if (inFocus) 0.20f else 0.06f),
                startAngle = start,
                sweepAngle = 0.7f,
                useCenter = false,
                topLeft = Offset(g.center.x - r, g.center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = thickness * 0.7f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Year boundaries, ringed and named on the quiet side of the disc. */
private fun DrawScope.drawGuides(
    g: DiscGeometry,
    press: RecordPress,
    ranges: List<ClosedFloatingPointRange<Float>>,
    measurer: TextMeasurer,
) {
    var lastYear = press.bands.firstOrNull()?.month?.year
    press.bands.forEachIndexed { index, band ->
        if (band.month.year == lastYear) return@forEachIndexed
        lastYear = band.month.year
        val range = ranges.getOrNull(index) ?: return@forEachIndexed
        val r = g.radius(range.start)
        drawCircle(
            color = Color.White.copy(alpha = 0.10f),
            radius = r,
            center = g.center,
            style = Stroke(width = 0.8f),
        )
        val label = measurer.measure(
            text = band.month.year.toString(),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 8.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        // Sits on the ring itself, at the top, turning with the disc.
        drawText(label, topLeft = Offset(g.center.x - label.size.width / 2f, g.center.y - r - 14f))
    }
}

/** The pressed label: catalogue number, side, and whose record it is. */
private fun DrawScope.drawLabel(
    g: DiscGeometry,
    press: RecordPress,
    measurer: TextMeasurer,
    angle: Float,
) {
    drawCircle(color = RecordInk.Paper, radius = g.label, center = g.center)
    drawCircle(
        color = Color.Black.copy(alpha = 0.06f),
        radius = g.label,
        center = g.center,
        style = Stroke(width = 1.5f),
    )

    val width = g.label * 1.5f
    fun line(text: String, size: Float, tracking: Float, weight: FontWeight, alpha: Float, y: Float) {
        val measured = measurer.measure(
            text = text,
            style = TextStyle(
                color = RecordInk.Ink.copy(alpha = alpha),
                fontSize = size.sp,
                letterSpacing = tracking.sp,
                fontWeight = weight,
                textAlign = TextAlign.Center,
            ),
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.toInt()),
        )
        drawText(
            measured,
            topLeft = Offset(g.center.x - measured.size.width / 2f, g.center.y + y),
        )
    }

    val catalog = "AUR-${press.bands.firstOrNull()?.month?.year ?: ""}-${press.totalPlays}"
    line("AURIS", 11f, 4f, FontWeight.Bold, 0.92f, -g.label * 0.54f)
    line("THE RECORD", 7f, 2.5f, FontWeight.Medium, 0.55f, -g.label * 0.36f)
    line(press.summary.label.uppercase(), 8f, 1.6f, FontWeight.SemiBold, 0.85f, -g.label * 0.06f)
    line(press.holder.uppercase().take(24), 7f, 1.2f, FontWeight.Normal, 0.55f, g.label * 0.12f)
    line("33⅓ RPM · STEREO · $catalog", 5.5f, 0.8f, FontWeight.Normal, 0.42f, g.label * 0.46f)

    // The spindle hole.
    drawCircle(color = RecordInk.Stage, radius = g.label * 0.045f, center = g.center)
}

/**
 * The light: two soft wedges fixed in screen space, so the disc turns under a
 * still lamp. Drawn after the disc and never rotated with it.
 */
private fun DrawScope.drawSheen(g: DiscGeometry, zoom: Float, tx: Float, ty: Float) {
    val center = Offset(g.center.x * zoom + tx, g.center.y * zoom + ty)
    val radius = g.disc * zoom
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.055f),
                Color.Transparent,
                Color.Transparent,
                Color.White.copy(alpha = 0.035f),
            ),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
        radius = radius,
        center = center,
    )
}

/**
 * The arm. Pivot outside the top-right of the disc, headshell on the groove the
 * chapter is about, counterweight behind the pivot — the same three parts a real
 * one has, so it lands in the right place without a legend.
 */
private fun DrawScope.drawTonearm(
    g: DiscGeometry,
    focus: Offset,
    zoom: Float,
    tx: Float,
    ty: Float,
    chapter: RecordChapter,
    press: RecordPress,
    ranges: List<ClosedFloatingPointRange<Float>>,
) {
    val pivot = Offset(g.center.x + g.disc * 1.02f, g.center.y - g.disc * 0.94f)
    val tip = Offset(focus.x * zoom + tx, focus.y * zoom + ty)
    val bone = Color(0xFFD9D4C7)

    // Counterweight, behind the pivot on the same line.
    val away = (pivot - tip)
    val len = kotlin.math.hypot(away.x, away.y).takeIf { it > 0.001f } ?: 1f
    val back = Offset(pivot.x + away.x / len * g.disc * 0.16f, pivot.y + away.y / len * g.disc * 0.16f)
    drawLine(
        color = bone.copy(alpha = 0.55f),
        start = pivot,
        end = back,
        strokeWidth = 5f * zoom.coerceAtLeast(0.6f),
        cap = StrokeCap.Round,
    )
    drawCircle(color = Color(0xFF4A4850), radius = g.disc * 0.035f, center = back)
    drawLine(
        color = bone.copy(alpha = 0.85f),
        start = pivot,
        end = tip,
        strokeWidth = 3.2f * zoom.coerceAtLeast(0.6f),
        cap = StrokeCap.Round,
    )
    drawCircle(color = Color(0xFF2B2A31), radius = g.disc * 0.045f, center = pivot)
    drawCircle(color = bone.copy(alpha = 0.9f), radius = g.disc * 0.012f, center = pivot)
    // Headshell
    drawCircle(color = bone, radius = g.disc * 0.022f, center = tip)
    drawCircle(color = RecordInk.Accent, radius = g.disc * 0.008f, center = tip)
}

// ── Plaque ──────────────────────────────────────────────────────────────────

@Composable
private fun ChapterPlaque(
    chapter: RecordChapter,
    step: Int,
    last: Int,
    holder: String,
    onAdvance: () -> Unit,
    onExport: () -> Unit,
    onPlaylist: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(RecordInk.Plaque)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(26.dp))
            .clickable { onAdvance() }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            chapter.kicker.uppercase(),
            color = RecordInk.Accent,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            chapter.headline,
            color = RecordInk.Cream,
            fontSize = if (step == 0) 40.sp else 30.sp,
            lineHeight = if (step == 0) 42.sp else 33.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            chapter.caption,
            color = RecordInk.Cream.copy(alpha = 0.68f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )

        if (chapter.stats.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                chapter.stats.take(4).forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            label.uppercase(),
                            color = RecordInk.Cream.copy(alpha = 0.40f),
                            fontSize = 8.sp,
                            letterSpacing = 1.2.sp,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            value,
                            color = RecordInk.Cream.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        chapter.named?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                it,
                color = RecordInk.Cream.copy(alpha = 0.56f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (step == last) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaqueButton("Export poster", RecordInk.Accent, onExport)
                PlaqueButton("Most replayed", RecordInk.Cream.copy(alpha = 0.12f), onPlaylist)
            }
        } else {
            Spacer(Modifier.height(14.dp))
            Text(
                if (step == 0) "Tap to stop the record" else "Tap for the next groove",
                color = RecordInk.Cream.copy(alpha = 0.38f),
                fontSize = 11.sp,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

@Composable
private fun PlaqueButton(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tint)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            label,
            color = if (tint == RecordInk.Accent) RecordInk.Stage else RecordInk.Cream,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Sheets ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordPlaylistSheet(
    songs: List<com.music.bitchord.data.stats.RankedSong>,
    onDismiss: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RecordInk.Plaque,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "MOST REPLAYED",
                color = RecordInk.Accent,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${songs.size} songs, in the order you wore them out",
                color = RecordInk.Cream.copy(alpha = 0.65f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            PlaqueButton("Play all", RecordInk.Accent, onPlayAll)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth()) {
                songs.take(50).forEachIndexed { index, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPlaySong(row.song) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = RecordInk.Cream.copy(alpha = 0.35f),
                            fontSize = 11.sp,
                            modifier = Modifier.width(24.dp),
                        )
                        AsyncImage(
                            model = row.song.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.song.title,
                                color = RecordInk.Cream,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                row.song.artist,
                                color = RecordInk.Cream.copy(alpha = 0.55f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "${row.plays}×",
                            color = RecordInk.Cream.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordPosterSheet(
    bitmap: android.graphics.Bitmap?,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RecordInk.Plaque,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "YOUR RECORD, ON PAPER",
                color = RecordInk.Accent,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            if (busy || bitmap == null) {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RecordInk.Cream.copy(alpha = 0.7f))
                }
            } else {
                AsyncImage(
                    model = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlaqueButton(stringResource(R.string.save), RecordInk.Cream.copy(alpha = 0.14f)) {
                        scope.launch {
                            saveToGallery(context, bitmap, "auris-record-${System.currentTimeMillis()}.png")
                        }
                    }
                    PlaqueButton(stringResource(R.string.share), RecordInk.Accent) {
                        scope.launch {
                            val uri = cacheForSharing(context, bitmap, "auris-record.png")
                            if (uri != null) sendIntent(uri).let { intent ->
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, null),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun RecordLoading(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(RecordInk.Stage), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = RecordInk.Cream.copy(alpha = 0.6f))
        // A spinner is not a place to be trapped. The month files are a disk
        // read and this is the one screen where that read is on the way in, so
        // the way out stays on screen for the whole of it.
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding(),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = RecordInk.Cream)
        }
    }
}

/** The stage's inks: warm paper against a cold, almost-black room. */
internal object RecordInk {
    val Stage = Color(0xFF070609)
    val Plaque = Color(0xFF14131A)
    val Cream = Color(0xFFF2EDE1)
    val Paper = Color(0xFFEDE6D6)
    val Ink = Color(0xFF1A181C)
    val Accent = Color(0xFFE7B15C)
    val Day = Color(0xFFE7B15C)
    val Night = Color(0xFF7C8CD8)
    val Glow = Color(0xFF9A7BE0)
}
