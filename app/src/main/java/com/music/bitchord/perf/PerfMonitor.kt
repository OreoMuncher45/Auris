package com.music.bitchord.perf

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.music.bitchord.data.PerfCounters
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What this process is spending, sampled rather than narrated.
 *
 * The whole idea is that the expensive code stays untouched: the player bumps
 * a counter per frame read, mesh read, blur pass and so on — one atomic add
 * each — and this object reads those counters on a clock, next to the
 * process's own CPU time, and writes the difference down. Nothing here runs
 * inside a frame.
 *
 * The log is a handful of numbers every two seconds, not a line per event:
 * "frames/s 30, mesh/s 10, ink/s 2.5" says which of the player's jobs is
 * actually running, and the CPU beside it says what a phone is paying. That is
 * the argument the profiler exists to settle, and it fits in a screen.
 *
 * Off unless asked for. While off there is no timer and no reader: [start]
 * collects the setting and the loop only exists for as long as the switch is
 * on.
 */
object PerfMonitor {
    /** How often a sample is taken while the monitor is on. */
    private const val SAMPLE_MS = 2_000L

    /** How many samples are kept: three minutes at [SAMPLE_MS]. */
    private const val KEEP = 90

    private const val TAG = "PerfMonitor"

    /** Process CPU as a percentage of one core, the newest sample. */
    private val _cpuPercent = MutableStateFlow(0f)
    val cpuPercent: StateFlow<Float> = _cpuPercent.asStateFlow()

    /** Every counted event, per second, the newest sample. */
    private val _eventsPerSecond = MutableStateFlow(0f)
    val eventsPerSecond: StateFlow<Float> = _eventsPerSecond.asStateFlow()

    private data class Sample(
        val atMillis: Long,
        val cpuPercent: Float,
        val threads: Int,
        val rssKb: Long,
        /** Delivered events per second, in [PerfCounters.Counter] order. */
        val perSecond: FloatArray,
    )

    private val samples = ArrayDeque<Sample>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    private val clockTicksPerSecond: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toLong() }
            .getOrDefault(100L)
            .coerceAtLeast(1L)
    }

    /**
     * Follow the setting, and sample only while it is on.
     *
     * Called once from the application. The loop is created by the collector
     * and cancelled with it, so switching the monitor off leaves the process
     * with no timer of its own again.
     */
    fun start(context: Context) {
        if (watcher?.isActive == true) return
        val app = context.applicationContext
        watcher = scope.launch {
            AppSettings.perfMonitor.collectLatest { on ->
                if (!on) {
                    _cpuPercent.value = 0f
                    _eventsPerSecond.value = 0f
                    return@collectLatest
                }
                samples.clear()
                runLoop(app)
            }
        }
    }

    private suspend fun runLoop(context: Context) {
        val counters = PerfCounters.Counter.entries
        var lastTotals = PerfCounters.totals()
        var lastCpuTicks = processCpuTicks()
        var lastAt = SystemClock.elapsedRealtime()
        // First sample immediately, so the panel has a number to show rather
        // than a dash for the first two seconds.
        delay(250)
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val ticks = processCpuTicks()
            val totals = PerfCounters.totals()
            val elapsedMs = (now - lastAt).coerceAtLeast(1L)
            val seconds = elapsedMs / 1000f

            val cpuSeconds = (ticks - lastCpuTicks).coerceAtLeast(0L).toFloat() / clockTicksPerSecond
            val cpu = cpuSeconds / seconds * 100f
            val perSecond = FloatArray(counters.size) { index ->
                (totals[index] - lastTotals[index]).coerceAtLeast(0L) / seconds
            }

            val sample = Sample(
                atMillis = now,
                cpuPercent = cpu,
                threads = threadCount(),
                rssKb = residentKb(),
                perSecond = perSecond,
            )
            samples.addLast(sample)
            while (samples.size > KEEP) samples.removeFirst()

            _cpuPercent.value = cpu
            _eventsPerSecond.value = perSecond.sum()

            lastTotals = totals
            lastCpuTicks = ticks
            lastAt = now
            delay(SAMPLE_MS)
        }
    }

    /** The composed report, for the panel's preview and the file both. */
    fun report(context: Context): String {
        val counters = PerfCounters.Counter.entries
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        val started = samples.firstOrNull()?.atMillis
        val lines = StringBuilder()

        lines.appendLine("Auris performance log")
        lines.appendLine("device      : ${Build.MANUFACTURER} ${Build.MODEL}")
        lines.appendLine("android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        lines.appendLine("app         : ${appVersion(context)}")
        lines.appendLine("live blur   : ${if (AppSettings.liveBlur.value) "on" else "off"}")
        lines.appendLine("samples     : ${samples.size} every ${SAMPLE_MS / 1000}s")
        if (started != null) {
            val wall = (samples.last().atMillis - started).coerceAtLeast(0L) / 1000L
            lines.appendLine("window      : ${wall}s")
        }
        lines.appendLine()
        lines.appendLine(
            "time      cpu%   thr   rss     " +
                counters.joinToString(" ") { it.shortName() },
        )
        samples.forEach { sample ->
            lines.appendLine(
                format.format(Date(System.currentTimeMillis() - (samples.last().atMillis - sample.atMillis))) +
                    "  " +
                    "%5.1f".format(Locale.US, sample.cpuPercent) +
                    "  " +
                    "%3d".format(Locale.US, sample.threads) +
                    "  " +
                    "%5.0fMB".format(Locale.US, sample.rssKb / 1024f) +
                    "  " +
                    sample.perSecond.joinToString(" ") { "%.1f".format(Locale.US, it).padStart(8) },
            )
        }
        if (samples.isNotEmpty()) {
            lines.appendLine()
            lines.appendLine("summary")
            lines.appendLine("  cpu   avg %.1f%%  peak %.1f%%".format(
                Locale.US,
                samples.map { it.cpuPercent }.average(),
                samples.maxOf { it.cpuPercent },
            ))
            counters.forEachIndexed { index, counter ->
                val values = samples.map { it.perSecond[index] }
                lines.appendLine(
                    "  %-9s avg %.1f/s  peak %.1f/s".format(
                        Locale.US,
                        counter.shortName(),
                        values.average(),
                        values.maxOrNull() ?: 0f,
                    ),
                )
            }
        } else {
            lines.appendLine()
            lines.appendLine("no samples yet — leave the monitor on for a few seconds")
        }
        return lines.toString()
    }

    /**
     * Write the report, and say where.
     *
     * The app's own external files directory: readable over `adb pull` and by
     * any file manager, and gone with the app when it is uninstalled.
     */
    fun save(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "auris-perf-$stamp.txt")
        file.writeText(report(context))
        return file
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    /**
     * This process's CPU time, in clock ticks.
     *
     * `/proc/self/stat` rather than a system-wide counter: the question is
     * what *this* app costs, and a phone's total is mostly the game beside it.
     * The comm field is in parentheses and may itself contain spaces or
     * parentheses, so the split starts after the last one.
     */
    private fun processCpuTicks(): Long = runCatching {
        val stat = File("/proc/self/stat").readText()
        val afterComm = stat.substring(stat.lastIndexOf(')') + 2)
        val fields = afterComm.split(' ')
        // Fields are numbered from 1 across the whole line, so after the comm
        // field the list starts at field 3 (state). utime is 14, stime 15.
        val utime = fields[11].toLong()
        val stime = fields[12].toLong()
        utime + stime
    }.getOrDefault(0L)

    private fun threadCount(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
        }
    }.getOrNull() ?: 0

    /** Resident set size in kilobytes — the number a phone's OOM killer reads. */
    private fun residentKb(): Long = runCatching {
        val fields = File("/proc/self/statm").readText().trim().split(' ')
        fields[1].toLong() * (Os.sysconf(OsConstants._SC_PAGESIZE))
    }.getOrDefault(0L) / 1024

    private fun PerfCounters.Counter.shortName(): String = when (this) {
        PerfCounters.Counter.FRAME_READ -> "frames"
        PerfCounters.Counter.MESH_READ -> "mesh"
        PerfCounters.Counter.INK_READ -> "ink"
        PerfCounters.Counter.BLUR_PASS -> "blur"
        PerfCounters.Counter.ANALYSER_PASS -> "analyser"
        PerfCounters.Counter.POSITION_TICK -> "tick"
    }
}