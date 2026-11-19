package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Thick-stroke, round-capped icons in the spirit of Telegram's modern icon set.
 * Drawn as strokes so the 2.2px weight + round joins read as a single polished
 * family. Tint is applied by [androidx.compose.material3.Icon].
 *
 * [Home] is the exception and says why it is one.
 */
object BitChordIcons {

    private const val STROKE = 2.2f
    private val stroke = SolidColor(Color.Black)

    /** Compact list and quotation-bubble shapes used by the player controls. */
    val Queue: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_queue", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(8f, y)
                    lineTo(21f, y)
                }
            }
            path(fill = stroke) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(4.35f, y)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, -2.5f, 0f)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, 2.5f, 0f)
                    close()
                }
            }
        }.build()
    }

    val LyricsQuote: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lyrics_quote", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 3.5f)
                lineTo(18f, 3.5f)
                quadTo(21f, 3.5f, 21f, 6.5f)
                lineTo(21f, 15f)
                quadTo(21f, 18f, 18f, 18f)
                lineTo(10f, 18f)
                lineTo(6f, 21f)
                lineTo(6f, 18f)
                quadTo(3f, 18f, 3f, 15f)
                lineTo(3f, 6.5f)
                quadTo(3f, 3.5f, 6f, 3.5f)
                close()
            }
            path(fill = stroke) {
                for (x in listOf(7.6f, 12.8f)) {
                    moveTo(x, 8f)
                    lineTo(x + 3f, 8f)
                    lineTo(x + 3f, 11.1f)
                    quadTo(x + 3f, 13.2f, x + 0.5f, 13.7f)
                    lineTo(x + 0.5f, 12.3f)
                    quadTo(x + 1.7f, 12f, x + 1.7f, 11f)
                    lineTo(x, 11f)
                    close()
                }
            }
        }.build()
    }

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_play",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = stroke,
            ) {
                moveTo(6.8f, 4.8f)
                lineTo(19.2f, 12f)
                lineTo(6.8f, 19.2f)
                close()
            }
        }.build()
    }

    /**
     * The one solid icon in the set, and the tab bar is why.
     *
     * Selection here is carried by tint alone — see `GlassNavBar`, where the
     * unselected tabs are the same glyph at 65% — so the home tab is the anchor
     * of the bar rather than a state of it, and a filled silhouette reads as
     * that anchor at 24dp where a four-stroke outline reads as detail.
     *
     * Filled *and* stroked, with the same path. The outline of a house is all
     * corners — an apex, two shoulders, two door jambs — and stroking the fill
     * with a round-joined pen is what rounds every one of them at once, for a
     * geometry that stays nine straight lines. The numbers below are therefore
     * inset by half the stroke: the pen puts back what they give up, and the
     * door notch is drawn oversized for the same reason, since the pen closes
     * in on it from both jambs.
     */
    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_home",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Roof, flush with the walls rather than overhanging them: an
                // eave narrower than the pen it is drawn with comes out as a
                // blob on the corner instead of a ledge.
                moveTo(12f, 4.05f)
                lineTo(19.75f, 11.35f)
                // Right wall, down to the floor
                lineTo(19.75f, 19.65f)
                // Door, cut up out of the bottom edge
                lineTo(14.95f, 19.65f)
                lineTo(14.95f, 15.6f)
                lineTo(9.05f, 15.6f)
                lineTo(9.05f, 19.65f)
                // Left wall, back up to the apex
                lineTo(4.25f, 19.65f)
                lineTo(4.25f, 11.35f)
                close()
            }
        }.build()
    }

    /**
     * The phone tab bar's Explore, Library and Search glyphs: solid-weight
     * iOS shapes rather than this file's stroked family, taken from the
     * outlines of Flutter's cupertino_icons font (MIT) — square_grid_2x2,
     * square_stack_fill and search.
     *
     * Each is the glyph laid out the way the font sets it: the 512-unit em
     * box is the viewport, the baseline sits at the font's ascent share of
     * it, and the advance is centred. So they keep the font's own optical
     * size and weight against one another at any icon size.
     */
    val TabExplore: ImageVector by lazy {
        fontGlyph(
            "bc_tab_explore",
            "M99.5 230.24Q58.5 230.24 58.5 188.24V93.24Q58.5 51.24 99.5 51.24H197.5Q238.5 51.24 238.5 93.24V188.24Q238.5 230.24 197.5 230.24Z" +
                "M314.5 230.24Q273.5 230.24 273.5 188.24V93.24Q273.5 51.24 314.5 51.24H412.5Q453.5 51.24 453.5 93.24V188.24Q453.5 230.24 412.5 230.24Z" +
                "M99.5 200.24H196.5Q207.5 200.24 207.5 188.24V93.24Q207.5 81.24 196.5 81.24H99.5Q88.5 81.24 88.5 93.24V188.24Q88.5 200.24 99.5 200.24Z" +
                "M314.5 200.24H411.5Q422.5 200.24 422.5 188.24V93.24Q422.5 81.24 411.5 81.24H314.5Q303.5 81.24 303.5 93.24V188.24Q303.5 200.24 314.5 200.24Z" +
                "M99.5 445.24Q58.5 445.24 58.5 403.24V307.24Q58.5 266.24 99.5 266.24H197.5Q238.5 266.24 238.5 307.24V403.24Q238.5 445.24 197.5 445.24Z" +
                "M314.5 445.24Q273.5 445.24 273.5 403.24V307.24Q273.5 266.24 314.5 266.24H412.5Q453.5 266.24 453.5 307.24V403.24Q453.5 445.24 412.5 445.24Z" +
                "M99.5 415.24H196.5Q207.5 415.24 207.5 403.24V308.24Q207.5 296.24 196.5 296.24H99.5Q88.5 296.24 88.5 308.24V403.24Q88.5 415.24 99.5 415.24Z" +
                "M314.5 415.24H411.5Q422.5 415.24 422.5 403.24V308.24Q422.5 296.24 411.5 296.24H314.5Q303.5 296.24 303.5 308.24V403.24Q303.5 415.24 314.5 415.24Z",
        )
    }

    /**
     * Two units lower than the font sets it: the back card's top edge rises
     * just past the em box, and the viewport would clip it flat.
     */
    val TabLibrary: ImageVector by lazy {
        fontGlyph(
            "bc_tab_library",
            "M154 27.24Q156 0.24 185 0.24H322Q352 0.24 353 27.24Z" +
                "M116 87.24Q121 56.24 150 56.24H356Q385 56.24 390 87.24Z" +
                "M133 500.24Q66 500.24 66 434.24V187.24Q66 121.24 133 121.24H378Q446 121.24 446 187.24V434.24Q446 500.24 385 500.24Z",
        )
    }

    val TabSearch: ImageVector by lazy {
        fontGlyph(
            "bc_tab_search",
            "M219 374.24Q151 374.24 102.5 325.74Q54 277.24 54 209.24Q54 141.24 102.5 92.74Q151 44.24 219 44.24Q287 44.24 335.5 92.74Q384 141.24 384 209.24Q384 264.24 350 309.24L451 410.24Q458 417.24 458 427.24Q458 438.24 451 445.24Q444 452.24 434 452.24Q423 452.24 416 445.24L315 343.24Q271 374.24 219 374.24Z" +
                "M128 300.24Q166 338.24 219 338.24Q272 338.24 310 300.24Q348 262.24 348 209.24Q348 156.24 310 118.24Q272 80.24 219 80.24Q166 80.24 128 118.24Q90 156.24 90 209.24Q90 262.24 128 300.24Z",
        )
    }

    /** A filled glyph outline in the 512-unit em box — see [TabExplore]. */
    private fun fontGlyph(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 512f, viewportHeight = 512f,
        ).addPath(pathData = addPathNodes(pathData), fill = stroke).build()

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_search",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Lens (full circle from two arcs)
                moveTo(4.6f, 11f)
                arcToRelative(6.4f, 6.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.8f, 0f)
                arcToRelative(6.4f, 6.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -12.8f, 0f)
                // Handle
                moveTo(15.9f, 15.9f)
                lineTo(20.4f, 20.4f)
            }
        }.build()
    }

    val Explore: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_explore",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Compass dial
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -17.2f, 0f)
                // Needle
                moveTo(15.4f, 8.6f)
                lineTo(13.6f, 13.6f)
                lineTo(8.6f, 15.4f)
                lineTo(10.4f, 10.4f)
                close()
            }
        }.build()
    }

    val Shuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_shuffle",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Strand that crosses downwards, with its arrow head
                moveTo(3.4f, 7.4f); lineTo(7f, 7.4f); lineTo(16.6f, 16.6f); lineTo(20.6f, 16.6f)
                moveTo(18.1f, 14.1f); lineTo(20.6f, 16.6f); lineTo(18.1f, 19.1f)
                // Strand that crosses upwards, broken around the intersection
                moveTo(3.4f, 16.6f); lineTo(7f, 16.6f); lineTo(9.8f, 13.9f)
                moveTo(13.9f, 10.1f); lineTo(16.6f, 7.4f); lineTo(20.6f, 7.4f)
                moveTo(18.1f, 4.9f); lineTo(20.6f, 7.4f); lineTo(18.1f, 9.9f)
            }
        }.build()
    }

    val Repeat: ImageVector by lazy { repeatLoop("bc_repeat") }

    /**
     * Two straight runs joined by semicircles, with the arrow heads lying flat
     * at the ends of the straights. Putting them on the curves instead — as a
     * first pass did — makes the glyph read as a refresh/sync symbol.
     */
    private fun repeatLoop(name: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.6f, 7.6f)
                lineTo(15.4f, 7.6f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8.8f)
                lineTo(8.6f, 16.4f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -8.8f)
                close()
                // Direction of travel: right along the top, left along the bottom.
                moveTo(13.5f, 5.7f); lineTo(15.4f, 7.6f); lineTo(13.5f, 9.5f)
                moveTo(10.5f, 14.5f); lineTo(8.6f, 16.4f); lineTo(10.5f, 18.3f)
            }
        }.build()

    /** AutoPlay's lemniscate. */
    val Infinity: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_infinity",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                curveTo(10.1f, 9.1f, 8.7f, 8f, 7.1f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 8f)
                curveTo(8.7f, 16f, 10.1f, 14.9f, 12f, 12f)
                curveTo(13.9f, 9.1f, 15.3f, 8f, 16.9f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8f)
                curveTo(15.3f, 16f, 13.9f, 14.9f, 12f, 12f)
            }
        }.build()
    }

    /** Beamed pair of notes, for instrumental stretches in the lyrics. */
    val MusicNote: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_music_note",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            // Heads are solid; stems and beam keep the family's stroke weight.
            path(fill = stroke) {
                moveTo(4.2f, 17.7f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
                moveTo(14.2f, 15.9f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
            }
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 17.7f); lineTo(10f, 6.7f)
                moveTo(20f, 15.9f); lineTo(20f, 4.9f)
                moveTo(10f, 6.7f); lineTo(20f, 4.9f)
            }
        }.build()
    }

    /** Speech bubble with two lines of words. */
    val Lyrics: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lyrics",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.2f, 4.6f)
                lineTo(17.8f, 4.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, 2.8f)
                lineTo(20.6f, 13.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, 2.8f)
                lineTo(10.6f, 16.4f)
                lineTo(6.8f, 19.6f)
                lineTo(6.8f, 16.4f)
                lineTo(6.2f, 16.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, -2.8f)
                lineTo(3.4f, 7.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, -2.8f)
                close()
                moveTo(7.6f, 9f); lineTo(16.4f, 9f)
                moveTo(7.6f, 12.1f); lineTo(13.2f, 12.1f)
            }
        }.build()
    }

    /** Plain chevron — a disclosure hint, not a directional arrow. */
    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_chevron_right",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 6.2f)
                lineTo(15.3f, 12f)
                lineTo(9.5f, 17.8f)
            }
        }.build()
    }

    /**
     * The player's like control, in two weights.
     *
     * Filled rather than merely tinted when set: the player draws every glyph
     * white on artwork, where a colour change alone is the one signal the
     * backdrop can swallow. A shape change survives any album cover.
     */
    val Heart: ImageVector by lazy { heart("bc_heart", filled = false) }

    val HeartFilled: ImageVector by lazy { heart("bc_heart_filled", filled = true) }

    private fun heart(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = if (filled) stroke else null,
            ) {
                // Two lobes meeting at the top notch, falling to a single point.
                moveTo(12f, 20f)
                curveTo(12f, 20f, 3.2f, 14.6f, 3.2f, 8.9f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, -1.5f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, 1.5f)
                curveTo(20.8f, 14.6f, 12f, 20f, 12f, 20f)
                close()
            }
        }.build()

    /** Adding something — a new playlist, on the library shelf. */
    val Plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_plus",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }.build()
    }

    /**
     * A tick — the other half of [Plus]. Saving something to the library swaps
     * one for the other in place, so the two are drawn on the same 14-unit span
     * and at the same weight; a tick sized to its own bounding box would jump.
     */
    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_check",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 12.8f); lineTo(9.6f, 17.4f); lineTo(19f, 6.9f)
            }
        }.build()
    }

    /** Arrow pointing down into a tray — offline download. */
    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_download",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Vertical stem
                moveTo(12f, 4.5f); lineTo(12f, 15.5f)
                // Arrow head
                moveTo(7.5f, 11f); lineTo(12f, 15.5f); lineTo(16.5f, 11f)
                // Tray base
                moveTo(4.5f, 18f); lineTo(19.5f, 18f)
            }
        }.build()
    }

    /**
     * Clock face with two hands — a download asked for but not yet on disk.
     *
     * The dial is the same 8.6 radius as [Explore]'s, so the two sit at the same
     * optical weight when they appear in the same row of header buttons. The
     * hands are one polyline through the centre rather than two strokes: the
     * round join is then the pivot, which is what stops the middle reading as a
     * pair of lines that happen to cross.
     */
    val Clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_clock",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Dial (full circle from two arcs)
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -17.2f, 0f)
                // Minute hand up, hour hand down to the right
                moveTo(12f, 7.4f); lineTo(12f, 12f); lineTo(15.4f, 13.8f)
            }
        }.build()
    }

    /**
     * A pushpin, not a map marker: a round head with a straight needle
     * kicked out to one side, the way it sits once it's stuck into something.
     * A teardrop reads as "location" the moment it's this small — the two
     * disjoint strokes here are what keep it read as "pinned" instead.
     */
    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_pin",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Round head.
                moveTo(9.6f, 8.4f)
                arcToRelative(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 0f)
                arcToRelative(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -6.8f, 0f)
                // Needle, kicked out from the underside of the head to a point.
                moveTo(10.8f, 10.8f)
                lineTo(5.6f, 19.6f)
            }
        }.build()
    }

    /** Speed gauge used by high-performance display settings. */
    val Performance: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_performance",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Open gauge, leaving room for the base line.
                moveTo(4.1f, 16.8f)
                arcToRelative(8.4f, 8.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.8f, 0f)
                // Needle and hub.
                moveTo(12f, 15.8f)
                lineTo(16.7f, 9.3f)
                moveTo(10.9f, 16.9f)
                arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 0f)
                arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, -2.2f, 0f)
                // Stable baseline.
                moveTo(5.4f, 19.5f)
                lineTo(18.6f, 19.5f)
            }
        }.build()
    }

    /** Four frame-time bars, used by refresh-rate selection. */
    val FrameRate: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_frame_rate",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5f, 15.5f); lineTo(5f, 8.5f)
                moveTo(9.7f, 18.5f); lineTo(9.7f, 5.5f)
                moveTo(14.3f, 16.5f); lineTo(14.3f, 7.5f)
                moveTo(19f, 14.5f); lineTo(19f, 9.5f)
            }
        }.build()
    }

    val Library: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_library",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Three upright spines + one leaning — Apple Music's library glyph, thickened
                moveTo(4.6f, 4.8f); lineTo(4.6f, 19.2f)
                moveTo(9.2f, 4.8f); lineTo(9.2f, 19.2f)
                moveTo(13.8f, 4.8f); lineTo(13.8f, 19.2f)
                moveTo(17.2f, 5.6f); lineTo(20.6f, 18.9f)
            }
        }.build()
    }

    /** 2x2 grid icon (4 rounded squares) for switching layout to grid view. */
    val GridView: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_grid_view",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Top-left
                moveTo(6.5f, 4.5f)
                lineTo(8.5f, 4.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(10.5f, 8.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(6.5f, 10.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4.5f, 6.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Top-right
                moveTo(15.5f, 4.5f)
                lineTo(17.5f, 4.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19.5f, 8.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(15.5f, 10.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(13.5f, 6.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Bottom-left
                moveTo(6.5f, 13.5f)
                lineTo(8.5f, 13.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(10.5f, 17.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(6.5f, 19.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4.5f, 15.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Bottom-right
                moveTo(15.5f, 13.5f)
                lineTo(17.5f, 13.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19.5f, 17.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(15.5f, 19.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(13.5f, 15.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()
            }
        }.build()
    }

    /** List view icon (horizontal rows) for switching layout to list view. */
    val ListView: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_list_view",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Three horizontal lines with bullet markers
                moveTo(4.5f, 6.5f); lineTo(5.5f, 6.5f)
                moveTo(9.5f, 6.5f); lineTo(19.5f, 6.5f)

                moveTo(4.5f, 12f); lineTo(5.5f, 12f)
                moveTo(9.5f, 12f); lineTo(19.5f, 12f)

                moveTo(4.5f, 17.5f); lineTo(5.5f, 17.5f)
                moveTo(9.5f, 17.5f); lineTo(19.5f, 17.5f)
            }
        }.build()
    }

    // ---- Player -----------------------------------------------------------
    //
    // Hand-built lookalikes for the Now Playing screen: one optical family for
    // the transport, bottom row, volume, like and menu controls. These are
    // vectors rather than vendored drawables so their weights, corners and
    // proportions can move together. They deliberately are not SF Symbols.

    /** A filled play triangle, with rounded points. */
    val PlayerPlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_play",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 2.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.2f, 2.0f)
                lineTo(20.6f, 12f)
                lineTo(5.2f, 22.0f)
                close()
            }
        }.build()
    }

    /** Two wide, rounded pause bars, matched to [PlayerPlay]. */
    val PlayerPause: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_pause",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.0f, 0.4f)
                lineTo(9.6f, 0.4f)
                lineTo(9.6f, 23.6f)
                lineTo(3.0f, 23.6f)
                close()
                moveTo(14.4f, 0.4f)
                lineTo(21.0f, 0.4f)
                lineTo(21.0f, 23.6f)
                lineTo(14.4f, 23.6f)
                close()
            }
        }.build()
    }

    /** Three rows of a dot and a line — the queue mark, plain. */
    val PlayerQueue: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_queue",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
            ) {
                for (y in listOf(8f, 12f, 16f)) {
                    moveTo(7.8f, y)
                    lineTo(19.5f, y)
                }
            }
            path(fill = stroke) {
                for (y in listOf(8f, 12f, 16f)) {
                    circle(4.5f, y, 1.05f)
                }
            }
        }.build()
    }

    /**
     * The queue mark with repeat arrows in place of the top line's tail.
     *
     * The badge is the whole point: the queue's button says both what it opens
     * and which playback mode is on, so the state does not need a second row of
     * controls over the artwork.
     */
    val PlayerQueueRepeat: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_queue_repeat",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            queueRowsWithBadge()
            path(
                stroke = stroke,
                strokeLineWidth = 1.15f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Upper arrow: right, along the top of the badge.
                moveTo(15.85f, 6.20f)
                verticalLineTo(5.65f)
                curveTo(15.85f, 5.18f, 16.22f, 4.82f, 16.68f, 4.82f)
                horizontalLineTo(19.65f)
                moveTo(18.95f, 4.02f)
                lineTo(19.95f, 4.82f)
                lineTo(18.95f, 5.62f)
                // Lower arrow: left, along the bottom of the badge.
                moveTo(20.55f, 6.90f)
                verticalLineTo(7.45f)
                curveTo(20.55f, 7.92f, 20.18f, 8.28f, 19.72f, 8.28f)
                horizontalLineTo(16.75f)
                moveTo(17.45f, 7.48f)
                lineTo(16.45f, 8.28f)
                lineTo(17.45f, 9.08f)
            }
        }.build()
    }

    /** The queue mark, its badge a pair of crossing shuffle arrows. */
    val PlayerQueueShuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_queue_shuffle",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            queueRowsWithBadge()
            path(
                stroke = stroke,
                strokeLineWidth = 1.15f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Two lines, crossed, each running out to an arrowhead.
                moveTo(15.00f, 4.90f)
                horizontalLineTo(16.90f)
                lineTo(19.40f, 8.60f)
                horizontalLineTo(20.55f)
                moveTo(15.00f, 8.60f)
                horizontalLineTo(16.90f)
                lineTo(19.40f, 4.90f)
                horizontalLineTo(20.55f)
                moveTo(19.60f, 3.95f)
                lineTo(20.70f, 4.90f)
                lineTo(19.60f, 5.85f)
                moveTo(19.60f, 7.65f)
                lineTo(20.70f, 8.60f)
                lineTo(19.60f, 9.55f)
            }
        }.build()
    }

    /** The queue mark, its badge autoplay's lemniscate. */
    val PlayerQueueAutoplay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_queue_autoplay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            queueRowsWithBadge()
            path(
                stroke = stroke,
                strokeLineWidth = 1.15f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(18f, 6.75f)
                curveTo(17.4f, 5.60f, 16.9f, 5.05f, 16.1f, 5.05f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 3.4f)
                curveTo(16.9f, 8.45f, 17.4f, 7.90f, 18f, 6.75f)
                curveTo(18.6f, 5.60f, 19.1f, 5.05f, 19.9f, 5.05f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 3.4f)
                curveTo(19.1f, 8.45f, 18.6f, 7.90f, 18f, 6.75f)
            }
        }.build()
    }

    /** A rounded quote bubble with two marks: the lyrics button. */
    val PlayerLyrics: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_lyrics",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.55f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.15f, 4.35f)
                horizontalLineTo(17.85f)
                arcTo(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20.65f, 7.15f)
                verticalLineTo(14.85f)
                arcTo(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.85f, 17.65f)
                horizontalLineTo(10.75f)
                lineTo(7.25f, 20.75f)
                verticalLineTo(17.65f)
                horizontalLineTo(6.15f)
                arcTo(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.35f, 14.85f)
                verticalLineTo(7.15f)
                arcTo(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.15f, 4.35f)
                close()
            }
            path(fill = stroke) {
                quoteMark(9.65f)
                quoteMark(14.35f)
            }
        }.build()
    }

    /** A wide, thin chevron pointing down: the player's dismiss control. */
    val PlayerChevronDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_chevron_down",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.2f, 9.2f)
                lineTo(12f, 15.5f)
                lineTo(18.8f, 9.2f)
            }
        }.build()
    }

    /** Three dots, with no disc of their own — [CircleGlyph] paints that. */
    val PlayerEllipsis: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_ellipsis",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = stroke) {
                circle(5.9f, 12f, 1.75f)
                circle(12f, 12f, 1.75f)
                circle(18.1f, 12f, 1.75f)
            }
        }.build()
    }

    /** Apple's sharp, five-point outline star. */
    val PlayerStar: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_star",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                star()
            }
        }.build()
    }

    /** The filled state of [PlayerStar]. */
    val PlayerStarFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_star_filled",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                star()
            }
        }.build()
    }

    /**
     * AirPlay: three concentric arcs with a filled receiver triangle at the
     * base.
     *
     * The arcs are struck rather than full circles — the opening at the base is
     * where the triangle sits, so the mark reads as a broadcast, not as a
     * target.
     */
    val PlayerAirPlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_airplay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.38f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(9.45f, 13.70f)
                arcTo(3.15f, 3.15f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.55f, 13.70f)
            }
            path(
                stroke = stroke,
                strokeLineWidth = 1.38f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(7.90f, 15.81f)
                arcTo(5.70f, 5.70f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.10f, 15.81f)
            }
            path(
                stroke = stroke,
                strokeLineWidth = 1.38f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.37f, 17.88f)
                arcTo(8.25f, 8.25f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.63f, 17.88f)
            }
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.0f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(11.60f, 14.35f)
                lineTo(6.82f, 19.95f)
                curveTo(6.55f, 20.27f, 6.78f, 20.75f, 7.20f, 20.75f)
                horizontalLineTo(16.80f)
                curveTo(17.22f, 20.75f, 17.45f, 20.27f, 17.18f, 19.95f)
                lineTo(12.40f, 14.35f)
                curveTo(12.19f, 14.10f, 11.81f, 14.10f, 11.60f, 14.35f)
                close()
            }
        }.build()
    }

    /** A small filled speaker cone, the body shared by the volume glyphs. */
    val PlayerVolumeLow: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_volume_low",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                speakerBody()
            }
        }.build()
    }

    /** The low speaker plus two sound-wave arcs. */
    val PlayerVolumeHigh: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_volume_high",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                speakerBody()
            }
            path(
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(14.4f, 8.9f)
                quadTo(16.5f, 12f, 14.4f, 15.1f)
                moveTo(17.5f, 6.6f)
                quadTo(20.8f, 12f, 17.5f, 17.4f)
            }
        }.build()
    }

    /** The low speaker with a small cross where the waves would be. */
    val PlayerVolumeMuted: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_volume_muted",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                speakerBody()
            }
            path(
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(14.9f, 9.8f)
                lineTo(19.1f, 14.2f)
                moveTo(19.1f, 9.8f)
                lineTo(14.9f, 14.2f)
            }
        }.build()
    }

    /** Two crossing arrows, matching the wider, lighter player row. */
    val PlayerShuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_shuffle",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.3f, 7.2f)
                lineTo(6.7f, 7.2f)
                lineTo(17.3f, 16.8f)
                lineTo(19.2f, 16.8f)
                moveTo(3.3f, 16.8f)
                lineTo(6.7f, 16.8f)
                lineTo(9.4f, 14.4f)
                moveTo(13.9f, 9.6f)
                lineTo(17.3f, 7.2f)
                lineTo(19.2f, 7.2f)
            }
            path(fill = stroke) {
                moveTo(17.7f, 5.0f); lineTo(21.0f, 7.2f); lineTo(17.7f, 9.4f); close()
                moveTo(17.7f, 14.6f); lineTo(21.0f, 16.8f); lineTo(17.7f, 19.0f); close()
            }
        }.build()
    }

    /** A repeat loop with arrowheads on its straight runs. */
    val PlayerRepeat: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_repeat",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.2f, 7.2f)
                lineTo(16.0f, 7.2f)
                arcToRelative(4.8f, 4.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 9.6f)
                lineTo(8.2f, 16.8f)
                arcToRelative(4.8f, 4.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -9.6f)
                close()
            }
            path(fill = stroke) {
                moveTo(14.0f, 5.0f); lineTo(17.4f, 7.2f); lineTo(14.0f, 9.4f); close()
                moveTo(10.4f, 14.6f); lineTo(7.0f, 16.8f); lineTo(10.4f, 19.0f); close()
            }
        }.build()
    }

    /** Autoplay's lemniscate, widened to sit with [PlayerRepeat]. */
    val PlayerAutoplay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_autoplay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                curveTo(10.12f, 9.1f, 8.7f, 8f, 7.1f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 8f)
                curveTo(8.7f, 16f, 10.12f, 14.9f, 12f, 12f)
                curveTo(13.88f, 9.1f, 15.3f, 8f, 16.9f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8f)
                curveTo(15.3f, 16f, 13.88f, 14.9f, 12f, 12f)
            }
        }.build()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
        moveTo(cx - radius, cy)
        arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, radius * 2f, 0f)
        arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, -radius * 2f, 0f)
        close()
    }

    private fun PathBuilder.speakerBody() {
        moveTo(3.3f, 9.2f)
        lineTo(5.9f, 9.2f)
        lineTo(10.4f, 5.5f)
        lineTo(10.4f, 18.5f)
        lineTo(5.9f, 14.8f)
        lineTo(3.3f, 14.8f)
        close()
    }

    private fun PathBuilder.quoteMark(x: Float) {
        // The reference mark, drawn on its own bounds: a beak curling down out
        // of a round head. [x] is where that bound starts, so the right-hand
        // mark is the same shape shifted by the width of the pair's gap.
        val dx = x - 9.65f
        moveTo(9.65f + dx, 8.55f)
        curveTo(10.57f + dx, 8.55f, 11.25f + dx, 9.23f, 11.25f + dx, 10.20f)
        curveTo(11.25f + dx, 11.60f, 10.43f + dx, 12.90f, 9.30f + dx, 13.67f)
        curveTo(9.00f + dx, 13.87f, 8.63f + dx, 13.55f, 8.85f + dx, 13.23f)
        curveTo(9.47f + dx, 12.40f, 9.77f + dx, 11.80f, 9.79f + dx, 11.33f)
        curveTo(9.63f + dx, 11.39f, 9.47f + dx, 11.43f, 9.30f + dx, 11.43f)
        curveTo(8.57f + dx, 11.43f, 8.05f + dx, 10.85f, 8.05f + dx, 10.00f)
        curveTo(8.05f + dx, 9.17f, 8.73f + dx, 8.55f, 9.65f + dx, 8.55f)
        close()
    }

    /**
     * The queue's three rows, shortened at the top so a badge can sit in the
     * corner without touching them.
     *
     * Shared by every badged variant, so repeat, shuffle and autoplay all draw
     * the same queue under them.
     */
    private fun ImageVector.Builder.queueRowsWithBadge() {
        path(
            stroke = stroke,
            strokeLineWidth = 1.35f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6.2f, 10f)
            horizontalLineTo(14.6f)
            moveTo(6.2f, 14f)
            horizontalLineTo(16.9f)
            moveTo(6.2f, 18f)
            horizontalLineTo(16.9f)
        }
        path(fill = stroke) {
            for (y in listOf(10f, 14f, 18f)) {
                circle(3.2f, y, 1.0f)
            }
        }
    }

    private fun PathBuilder.star() {
        val cx = 12f
        val cy = 12.3f
        val outer = 10.3f
        val inner = 4.35f
        moveTo(cx, cy - outer)
        for (index in 1..10) {
            val radius = if (index % 2 == 1) inner else outer
            val angle = -PI / 2 + index * PI / 5
            lineTo(
                (cx + radius * cos(angle)).toFloat(),
                (cy + radius * sin(angle)).toFloat(),
            )
        }
        close()
    }

}
