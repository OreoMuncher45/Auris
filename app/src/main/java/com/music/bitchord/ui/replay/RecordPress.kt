package com.music.bitchord.ui.replay

import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

/**
 * The Replay pressed as a record: one band per month, the arcs inside it shaped
 * by the hours you actually played.
 *
 * ## Why a second shape for the same numbers
 *
 * A ranked list answers "what did I play most". A record answers "what did this
 * year *sound* like" — the same listening, but with time as distance from the
 * label and the hours of the day as the angle round it, so a heavy month is a
 * wide band you can put your finger on and a late-night habit is a run of arcs
 * that all sit in the same quarter of the disc. None of that is expressible as
 * a list, and all of it is already in the month files: nothing here is invented
 * and nothing is sampled.
 *
 * ## What is real and what is drawn
 *
 * Every number on a chapter comes out of [ReplaySummary] or a [StoredBucket] —
 * minutes, plays, distinct counts, the busiest day, the hour histogram. The
 * only constructed values are the colours and the radius a band sits at, and
 * both are a function of those numbers: band thickness is that month's share of
 * the pressing, arc length is that hour's share of the month. There are no
 * individual play timestamps kept on disk, so an arc is an hour rather than one
 * song, which is the honest resolution of what the app records.
 *
 * Months are oldest at the label and newest at the rim, which is how a record is
 * cut: the outside is where the needle starts and where this week lives.
 */
data class RecordBand(
    val month: YearMonth,
    val ms: Long,
    val plays: Int,
    val topArtist: String?,
    val topTracks: List<TrackEntry>,
    val topAlbum: NameEntry?,
    /** Milliseconds per hour of day, 0..23, exactly as stored. */
    val hours: List<Long>,
    /** Milliseconds per day of the month, exactly as stored. */
    val days: Map<Int, Long>,
) {
    val minutes: Long get() = ms / 60_000

    val title: String get() = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    val short: String get() = month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    /** The hour with the most time in it, or null for a silent band. */
    val peakHour: Int? get() = hours.withIndex()
        .filter { it.value > 0 }
        .maxByOrNull { it.value }
        ?.index

    /** The day with the most time in it, or null. */
    val busiestDay: Map.Entry<Int, Long>? get() = days.maxByOrNull { it.value }

    fun shareOf(total: Long): Float =
        if (total <= 0L) 0f else (ms.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * One stop in the tap-through: where the camera goes and what the plaque says.
 *
 * [focusBand] and [focusHour] are both optional because the closing card looks
 * at the whole disc rather than a detail of it, and [spread] is how much of the
 * day the camera keeps in frame — a single hour for the late-night card, the
 * whole turn for the artist card whose arcs are all over the record.
 */
data class RecordChapter(
    val id: String,
    /** Small caps above the headline: side, month, or what is being looked at. */
    val kicker: String,
    val headline: String,
    /** One sentence, the only place this feature is allowed to editorialise. */
    val caption: String,
    /** Up to four label/value pairs, all of them counted from the data. */
    val stats: List<Pair<String, String>> = emptyList(),
    /** "Top track" line, when the chapter has one thing to name. */
    val named: String? = null,
    val focusBand: Int? = null,
    /** Centre of the visible stretch of the day, in minutes after midnight. */
    val focusHour: Float? = null,
    /** Minutes of the day the camera keeps in frame. */
    val spread: Float = 1_440f,
    val zoom: Float = 1f,
)

/**
 * Everything the disc and its chapters are drawn from.
 *
 * Built once per opening; the chapters are precomputed for the same reason the
 * Replay's charts are — a tap must move the camera, never compute a ranking.
 */
data class RecordPress(
    val summary: ReplaySummary,
    val bands: List<RecordBand>,
    val holder: String = "",
    val memberSince: String? = null,
) {
    val totalMs: Long get() = bands.sumOf { it.ms }.takeIf { it > 0 } ?: summary.totalMs
    val totalPlays: Int get() = bands.sumOf { it.plays }.takeIf { it > 0 } ?: summary.totalPlays
    val isEmpty: Boolean get() = bands.isEmpty()

    /** The month that took the most listening. */
    val peakBand: RecordBand? get() = bands.maxByOrNull { it.ms }
    val peakBandIndex: Int get() = bands.indexOf(peakBand)

    /** Time played between 10pm and 4am, and what share of the pressing that is. */
    val nightMs: Long get() = bands.sumOf { band ->
        val h = band.hours
        (22..23).sumOf { h[it] } + (0..3).sumOf { h[it] }
    }

    val nightShare: Float
        get() = if (totalMs <= 0L) 0f else (nightMs.toFloat() / totalMs).coerceIn(0f, 1f)

    /** The busiest clock hour across the pressing, or null when nothing played. */
    val peakHour: Int? get() {
        val sum = LongArray(24)
        bands.forEach { band -> band.hours.forEachIndexed { i, v -> sum[i] += v } }
        return sum.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
    }

    /** The top artist of the pressing, and every band that artist led. */
    val topArtist: String? get() = summary.artists.firstOrNull()?.title ?: bands.firstNotNullOfOrNull { it.topArtist }
    val artistBands: List<Int> get() = topArtist?.let { name ->
        bands.indices.filter { bands[it].topArtist.equals(name, ignoreCase = true) }
    } ?: emptyList()

    /** The day with the most listening, paired with the band it belongs to. */
    val busiestDay: Pair<RecordBand, Int>? get() {
        val iso = summary.busiestDay ?: return null
        val day = iso.takeLast(2).toIntOrNull() ?: return null
        val band = bands.firstOrNull { it.days.containsKey(day) && iso.startsWith(it.month.toString()) }
            ?: bands.firstOrNull { it.days.containsKey(day) }
            ?: return null
        return band to day
    }

    /** Longest unbroken run of days with anything played, and the band it ended in. */
    val streak: Pair<Int, RecordBand?> get() {
        var best = 0
        var run = 0
        var bestBand: RecordBand? = null
        bands.forEach { band ->
            for (day in 1..band.month.lengthOfMonth()) {
                if ((band.days[day] ?: 0L) > 0L) {
                    run++
                    if (run > best) {
                        best = run
                        bestBand = band
                    }
                } else {
                    run = 0
                }
            }
        }
        return best to bestBand
    }

    /** Distinct artists met in the pressing, summed per month rather than merged. */
    val months: Int get() = bands.size

    val chapters: List<RecordChapter> get() = buildList {
        add(intro())
        add(stopped())
        peakBand?.let { band -> add(peakMonth(band, bands.indexOf(band))) }
        if (artistBands.isNotEmpty()) add(artistChapter())
        if (peakHour != null) add(nightChapter())
        busiestDay?.let { (band, day) -> add(dayChapter(band, day)) }
        add(finale())
    }

    private fun intro() = RecordChapter(
        id = "intro",
        kicker = "Side A · ${summary.label}",
        headline = formatAxis(totalMs),
        caption = "Every month since ${bands.firstOrNull()?.short ?: "—"} pressed into one record. Tap to stop it and take a look.",
        stats = listOf(
            "Plays" to grouped(totalPlays.toLong()),
            "Months" to months.toString(),
            "Artists" to grouped(summary.distinctArtists.toLong()),
            "Songs" to grouped(summary.distinctSongs.toLong()),
        ),
    )

    /**
     * The card the first tap lands on: the disc has stopped and nothing is in
     * focus yet.
     *
     * It exists because stopping is a gesture worth its own frame. Zooming on
     * the same tap that drops the needle hides the one thing the first tap is
     * for — the record coming to rest — and makes the second tap feel like a
     * correction rather than a choice.
     */
    private fun stopped() = RecordChapter(
        id = "stopped",
        kicker = "Needle down · side A",
        headline = formatAxis(totalMs),
        caption = "The platter has stopped. Tap the disc to put the light on one part of it at a time.",
        stats = listOf(
            "Plays" to grouped(totalPlays.toLong()),
            "Months" to months.toString(),
            if (artistBands.isNotEmpty()) "Most bands" to (topArtist ?: "—") else "Artists" to grouped(summary.distinctArtists.toLong()),
            "Since" to (bands.firstOrNull()?.let { "${it.short} ${it.month.year}" } ?: "—"),
        ),
        named = peakBand?.let { "Thickest groove — ${it.title} ${it.month.year}" },
    )

    private fun peakMonth(band: RecordBand, index: Int): RecordChapter {
        val total = totalMs
        val top = band.topTracks.firstOrNull()
        return RecordChapter(
            id = "peak-$index",
            kicker = "Groove ${index + 1} · ${band.title} ${band.month.year}",
            headline = formatAxis(band.ms),
            caption = "Your heaviest month: ${percent(band.shareOf(total))} of the whole pressing lives on this band.",
            stats = listOf(
                "Plays" to grouped(band.plays.toLong()),
                "Top artist" to (band.topArtist ?: "—"),
                "Album" to (band.topAlbum?.name ?: "—"),
                "Loudest hour" to (band.peakHour?.let { formatHourLabel(it) } ?: "—"),
            ),
            named = top?.let { "${it.title} — ${it.plays} ${if (it.plays == 1) "play" else "plays"}" },
            focusBand = index,
            focusHour = band.peakHour?.let { it * 60f + 30f } ?: 720f,
            spread = 420f,
            zoom = 2.15f,
        )
    }

    private fun artistChapter(): RecordChapter {
        val name = topArtist ?: return finale()
        val artist = summary.artists.firstOrNull { it.title.equals(name, true) }
        val led = artistBands
        val ledMs = led.sumOf { bands[it].ms }
        return RecordChapter(
            id = "artist",
            kicker = "The thread · ${led.size} band${if (led.size == 1) "" else "s"}",
            headline = name,
            caption = "You played them most in ${led.size} of $months months. The arcs in their colour are theirs.",
            stats = listOf(
                "Plays" to grouped((artist?.plays ?: 0).toLong()),
                "Time" to formatAxis(artist?.ms ?: ledMs),
                "Bands led" to led.size.toString(),
                "Of the pressing" to percent(if (totalMs > 0) (artist?.ms ?: ledMs).toFloat() / totalMs else 0f),
            ),
            named = artist?.subtitle?.takeIf { it.isNotBlank() }?.let { "Also filed under $it" },
            focusBand = led.maxByOrNull { bands[it].ms } ?: led.first(),
            spread = 1_440f,
            zoom = 1.55f,
        )
    }

    private fun nightChapter(): RecordChapter {
        val hour = peakHour ?: return finale()
        val late = nightMs
        val topLate = bands.maxByOrNull { band ->
            val h = band.hours
            (22..23).sumOf { h[it] } + (0..3).sumOf { h[it] }
        }
        val lateTrack = topLate?.topTracks?.firstOrNull()
        return RecordChapter(
            id = "night",
            kicker = "The small hours",
            headline = formatHourLabel(hour),
            caption = "This is the hour the needle spent the most time on — ${percent(nightShare)} of everything landed between ten and four.",
            stats = listOf(
                "After dark" to formatAxis(late),
                "Share" to percent(nightShare),
                "Late band" to (topLate?.let { "${it.short} ${it.month.year}" } ?: "—"),
                "Late months" to "$lateBands/${bands.size}",
            ),
            named = lateTrack?.let { "Most played after dark — ${it.title}" },
            focusBand = topLate?.let { bands.indexOf(it) },
            focusHour = hour * 60f + 30f,
            spread = 300f,
            zoom = 2.5f,
        )
    }

    private fun dayChapter(band: RecordBand, day: Int): RecordChapter {
        val ms = band.days[day] ?: 0L
        val top = band.topTracks.firstOrNull()
        return RecordChapter(
            id = "day",
            kicker = "One day · ${band.short} $day, ${band.month.year}",
            headline = formatAxis(ms),
            caption = "The single busiest day on the record, cut deeper than any other.",
            stats = listOf(
                "Plays" to grouped(band.plays.toLong()),
                "Of the month" to percent(if (band.ms > 0) ms.toFloat() / band.ms else 0f),
                "Peak hour" to (band.peakHour?.let { formatHourLabel(it) } ?: "—"),
                "Streak" to "${streak.first} days",
            ),
            named = top?.let { "That month's record — ${it.title}" },
            focusBand = bands.indexOf(band),
            focusHour = band.peakHour?.let { it * 60f } ?: 720f,
            spread = 240f,
            zoom = 2.75f,
        )
    }

    private fun finale() = RecordChapter(
        id = "finale",
        kicker = "Run-out groove",
        headline = if (holder.isBlank()) "This is your record" else "$holder's record",
        caption = "Pressed from $months month${if (months == 1) "" else "s"} of listening" +
            (memberSince?.let { ", on Auris since $it" } ?: "") + ".",
        stats = listOf(
            "Pressed" to formatAxis(totalMs),
            "Plays" to grouped(totalPlays.toLong()),
            "Artists" to grouped(summary.distinctArtists.toLong()),
            "Albums" to grouped(summary.distinctAlbums.toLong()),
        ),
        named = peakBand?.let { "Where it is thickest — ${it.short} ${it.month.year}" },
    )

    /** How many months had at least a third of their listening after ten. */
    private val lateBands: Int
        get() = bands.count { band ->
            val h = band.hours
            val late = (22..23).sumOf { h[it] } + (0..3).sumOf { h[it] }
            band.ms > 0L && late * 3 >= band.ms
        }
}

// ── Building ────────────────────────────────────────────────────────────────

/**
 * Reads every month file the current period covers and presses them into bands.
 *
 * Off the composition's thread, because it is a directory listing and up to
 * [com.music.bitchord.data.stats.ListeningStats]'s three years of JSON. Months
 * outside the period are skipped rather than trimmed, so switching the chips
 * re-presses rather than re-filters.
 */
suspend fun loadRecordPress(
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
): RecordPress {
    val today = java.time.LocalDate.now()
    val buckets = runCatching { ListeningStats.exportAll() }.getOrDefault(emptyList())
    val bands = buckets
        .mapNotNull { bucket -> runCatching { YearMonth.parse(bucket.month) }.getOrNull()?.let { it to bucket } }
        .filter { (month, _) -> summary.period.covers(month, today) }
        .sortedBy { (month, _) -> month }
        .map { (_, bucket) -> bucket.toBand() }
        .filter { it.ms > 0L }
    return RecordPress(summary = summary, bands = bands, holder = holder, memberSince = memberSince)
}

private fun StoredBucket.toBand(): RecordBand {
    val parsed = runCatching { YearMonth.parse(month) }.getOrNull() ?: YearMonth.now()
    val sorted = tracks.sortedWith(compareByDescending<TrackEntry> { it.plays }.thenByDescending { it.ms })
    val topArtist = artists.maxByOrNull { it.ms }?.name
        ?: sorted.firstOrNull()?.artist
    val topAlbum = albums.maxByOrNull { it.ms }
    val hourMs = hours.sum()
    return RecordBand(
        month = parsed,
        ms = if (hourMs > 0L) hourMs else tracks.sumOf { it.ms },
        plays = tracks.sumOf { it.plays },
        topArtist = topArtist?.takeIf { it.isNotBlank() },
        topTracks = sorted.take(8),
        topAlbum = topAlbum,
        hours = hours,
        days = days,
    )
}

// ── Formatting ──────────────────────────────────────────────────────────────

/** Listening time as a headline: minutes, hours, or days past a thousand hours. */
internal fun formatAxis(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 1_440 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "$h hr" else "$h hr $m min"
        }
        else -> {
            val days = minutes / 1_440
            val h = (minutes % 1_440) / 60
            "$days d $h hr"
        }
    }
}

internal fun percent(share: Float): String {
    val pct = (share * 100f)
    return when {
        pct >= 10f -> "${pct.toInt()}%"
        pct >= 1f -> String.format(Locale.US, "%.1f%%", pct)
        else -> "<1%"
    }
}

/** "11pm", "3am", "midnight" — an hour of the day as people say it. */
internal fun formatHourLabel(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    return when (h) {
        0 -> "midnight"
        12 -> "noon"
        in 1..11 -> "${h}am"
        else -> "${h - 12}pm"
    }
}

/**
 * Where a band sits, as a fraction of the groove area: oldest at 0 (the label
 * edge) and newest at 1 (the rim).
 *
 * Shared by the on-screen disc and the poster so the two cannot drift; the
 * fractions are quarter-band steps because a band is drawn from the middle of
 * its own ring rather than from its edge.
 */
internal fun bandRange(bands: List<RecordBand>): List<ClosedFloatingPointRange<Float>> {
    val weights = bands.map { max(it.ms.toFloat(), 1f) }
    val total = weights.sum().takeIf { it > 0f } ?: 1f
    var at = 0f
    return weights.map { w ->
        val next = at + w / total
        val range = at..next
        at = next
        range
    }
}
