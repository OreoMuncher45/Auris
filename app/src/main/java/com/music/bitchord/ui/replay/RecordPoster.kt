package com.music.bitchord.ui.replay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The record as a poster: 1800 × 2400, the whole disc and the numbers that made
 * it, in the same ink as the screen.
 *
 * ## Why a bitmap and not a screenshot
 *
 * A screenshot is the screen's framing at the screen's size, which on a phone is
 * a small disc with a plaque over a third of it. A poster is a different
 * composition of the same data — the disc can be large, the type can be set for
 * reading at arm's length, and there is room for the credits that make it look
 * like something pressed rather than captured.
 *
 * ## What it says
 *
 * Everything printed is read off the pressing: the period, the holder, minutes,
 * plays, artists, albums, the months it spans, the heaviest band, the hour that
 * took the most of it, and the label's catalogue number from the plays. The
 * signature line is deliberately the only branding on it that is not the label:
 * a poster that shouts its app's name is an advert, and this is meant to be
 * kept.
 */
fun renderRecordPoster(
    press: RecordPress,
    /** Colours sampled from the year's defining record, as ARGB. */
    palette: List<Int>,
    /** Long edge in pixels; the composer is 3:4 at this width. */
    width: Int = 1800,
): Bitmap {
    val height = (width * 4f / 3f).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val w = width.toFloat()
    val h = height.toFloat()
    val s = width / 1800f

    val ink = EssentialInk(palette)

    // Page: a cold near-black with the record's own colour breathing in one
    // corner, so two posters do not look like the same template twice.
    canvas.drawRect(
        0f, 0f, w, h,
        Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.parseColor("#0B0A0F"), Color.parseColor("#050508")),
                null,
                Shader.TileMode.CLAMP,
            )
        },
    )
    canvas.drawCircle(
        w * 0.5f, h * 0.42f, w * 0.72f,
        Paint().apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.42f, w * 0.72f,
                intArrayOf(withAlpha(ink.glow, 0.22f), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        },
    )

    // ── Header ──────────────────────────────────────────────────────────────
    val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun text(
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        face: Typeface = sans,
        align: Paint.Align = Paint.Align.LEFT,
        spacing: Float = 0f,
    ) {
        canvas.drawText(
            value, x, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size * s
                typeface = face
                textAlign = align
                letterSpacing = spacing
            },
        )
    }

    text("AURIS", w * 0.06f, h * 0.075f, 30f, withAlpha(ink.cream, 0.95f), bold, spacing = 0.22f)
    text("THE RECORD", w * 0.94f, h * 0.075f, 22f, withAlpha(ink.cream, 0.55f), mono, Paint.Align.RIGHT, 0.18f)

    text(press.summary.label, w * 0.06f, h * 0.135f, 68f, ink.cream, serif)
    text(
        press.holder.ifBlank { "this device" },
        w * 0.06f, h * 0.176f, 28f, withAlpha(ink.cream, 0.62f), mono, spacing = 0.06f,
    )

    // ── Disc ────────────────────────────────────────────────────────────────
    val cx = w * 0.5f
    val cy = h * 0.455f
    val disc = w * 0.40f
    val label = disc * 0.30f
    val outer = disc * 0.975f

    canvas.drawCircle(
        cx, cy, disc * 1.18f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy + disc * 0.1f, disc * 1.18f,
                intArrayOf(withAlpha(Color.BLACK, 0.65f), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        },
    )
    canvas.drawCircle(
        cx, cy, disc,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, disc,
                intArrayOf(Color.parseColor("#24222B"), Color.parseColor("#141319"), Color.parseColor("#07070B")),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        },
    )

    // Fine grooves, at the same pitch the screen uses.
    val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    var r = label + disc * 0.02f
    var i = 0
    while (r < outer) {
        groove.color = withAlpha(Color.WHITE, if (i % 3 == 0) 0.045f else 0.022f)
        groove.strokeWidth = 1.1f * s
        canvas.drawCircle(cx, cy, r, groove)
        r += disc * 0.0052f
        i++
    }

    // Bands: the same hour-per-arc model as the screen, one hue for day and one
    // for the small hours.
    val ranges = bandRange(press.bands)
    press.bands.forEachIndexed { index, band ->
        val range = ranges.getOrNull(index) ?: return@forEachIndexed
        val radius = label + (range.start + range.endInclusive) / 2f * (outer - label)
        val thickness = ((range.endInclusive - range.start) * (outer - label)).coerceAtLeast(0.9f * s) * 0.88f
        val maxHour = band.hours.maxOrNull()?.takeIf { it > 0L } ?: return@forEachIndexed
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        band.hours.forEachIndexed { hour, ms ->
            if (ms <= 0L) return@forEachIndexed
            val share = (ms.toFloat() / maxHour).coerceIn(0.05f, 1f)
            val night = hour >= 22 || hour < 4
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = thickness
                strokeCap = Paint.Cap.BUTT
                color = withAlpha(if (night) ink.night else ink.day, 0.30f + 0.55f * share)
            }
            canvas.drawArc(rect, (hour / 24f) * 360f - 90f, 15f * share, false, paint)
        }
    }

    // Sheen.
    val sheen = Path().apply {
        moveTo(cx - disc, cy - disc)
        lineTo(cx + disc * 0.15f, cy - disc)
        lineTo(cx + disc, cy + disc * 0.55f)
        lineTo(cx + disc, cy + disc)
        close()
    }
    canvas.drawPath(
        sheen,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - disc, cy - disc, cx + disc, cy + disc,
                intArrayOf(withAlpha(Color.WHITE, 0.055f), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        },
    )

    // Label.
    canvas.drawCircle(cx, cy, label, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.paper })
    val catalog = "AUR-${press.bands.firstOrNull()?.month?.year ?: ""}-${press.totalPlays}"
    text("AURIS", cx, cy - label * 0.36f, 18f, withAlpha(ink.ink, 0.9f), bold, Paint.Align.CENTER, 0.2f)
    text(press.summary.label.uppercase(), cx, cy - label * 0.02f, 15f, withAlpha(ink.ink, 0.8f), mono, Paint.Align.CENTER, 0.14f)
    text("33⅓ RPM · STEREO", cx, cy + label * 0.26f, 10f, withAlpha(ink.ink, 0.45f), mono, Paint.Align.CENTER, 0.1f)
    text(catalog, cx, cy + label * 0.44f, 9f, withAlpha(ink.ink, 0.38f), mono, Paint.Align.CENTER, 0.08f)
    canvas.drawCircle(cx, cy, label * 0.045f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#07060A") })

    // ── Stats ───────────────────────────────────────────────────────────────
    val statsTop = h * 0.735f
    text(
        "PRESSED FROM ${press.months} MONTH${if (press.months == 1) "" else "S"} OF LISTENING",
        w * 0.06f, statsTop, 15f, withAlpha(ink.cream, 0.5f), mono, spacing = 0.14f,
    )

    val columns = listOf(
        "TIME" to formatAxis(press.totalMs),
        "PLAYS" to grouped(press.totalPlays.toLong()),
        "ARTISTS" to grouped(press.summary.distinctArtists.toLong()),
        "ALBUMS" to grouped(press.summary.distinctAlbums.toLong()),
    )
    val colWidth = (w * 0.88f) / columns.size
    columns.forEachIndexed { index, (labelName, value) ->
        val x = w * 0.06f + colWidth * index
        text(labelName, x, statsTop + 44f * s, 13f, withAlpha(ink.cream, 0.38f), mono, spacing = 0.16f)
        text(value, x, statsTop + 96f * s, 46f, ink.cream, serif)
    }

    canvas.drawLine(
        w * 0.06f, h * 0.845f, w * 0.94f, h * 0.845f,
        Paint().apply { color = withAlpha(ink.cream, 0.12f); strokeWidth = 1.2f * s },
    )

    // The three things worth saying in a line, taken from the pressing.
    val peak = press.peakBand
    val hour = press.peakHour
    val lines = buildList {
        peak?.let { add("Heaviest band — ${it.title} ${it.month.year}, ${formatAxis(it.ms)}") }
        hour?.let { add("Longest hour — ${formatHourLabel(it)}, ${percent(press.bands.sumOf { b -> b.hours[it] }.toFloat() / press.totalMs.coerceAtLeast(1L))} of the press") }
        press.busiestDay?.let { (band, day) -> add("Deepest day — ${band.short} $day, ${formatAxis(band.days[day] ?: 0L)}") }
        press.streak.first.takeIf { it > 1 }?.let { add("Longest run — $it days in a row") }
    }
    lines.take(4).forEachIndexed { index, line ->
        text(line, w * 0.06f, h * 0.885f + index * 26f * s, 17f, withAlpha(ink.cream, 0.6f), mono)
    }

    // ── Footer ──────────────────────────────────────────────────────────────
    text("AURIS · PRIVATE PRESSING", w * 0.06f, h * 0.972f, 12f, withAlpha(ink.cream, 0.35f), mono, spacing = 0.12f)
    text(catalog, w * 0.94f, h * 0.972f, 12f, withAlpha(ink.cream, 0.35f), mono, Paint.Align.RIGHT, 0.12f)

    return bitmap
}

/** The poster's colours, with the sampled ones kept inside a usable range. */
private class EssentialInk(private val palette: List<Int>) {
    private fun pick(index: Int, fallback: Int): Int {
        val c = palette.getOrNull(index) ?: fallback
        val lum = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f
        // A poster's accent has to read against near-black, so a dark sample is
        // lifted rather than printed as mud.
        return if (lum < 0.35f) blend(c, Color.WHITE, 0.35f) else c
    }

    val cream = Color.parseColor("#F2EDE1")
    val paper = Color.parseColor("#EDE6D6")
    val ink = Color.parseColor("#17151A")
    val day = pick(1, Color.parseColor("#E7B15C"))
    val night = pick(2, Color.parseColor("#7C8CD8"))
    val glow = pick(0, Color.parseColor("#9A7BE0"))
}

private fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

private fun blend(from: Int, to: Int, amount: Float): Int {
    val a = amount.coerceIn(0f, 1f)
    fun mix(f: Int, t: Int) = (f + (t - f) * a).toInt()
    return Color.rgb(
        mix(Color.red(from), Color.red(to)),
        mix(Color.green(from), Color.green(to)),
        mix(Color.blue(from), Color.blue(to)),
    )
}
