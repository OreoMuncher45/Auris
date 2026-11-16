package com.music.bitchord.perf

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.music.bitchord.data.CrashLog
import com.music.bitchord.data.PerfCounters
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DeviceCodecs
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
 * ## Whose CPU this is
 *
 * This process's, and only this process's. The phone-wide share that used to
 * sit beside it was never real: `/proc/stat` is closed to apps from Android 10
 * on, so the read failed and the columns printed 0.0 — a report that said an
 * app redrawing a full-screen blur thirty times a second was using none of the
 * phone. It is not measurable from inside an app, and a number that cannot be
 * measured is worse than one that is missing.
 *
 * What is measurable is the whole point of the rest of the line: `/proc/self`
 * and the per-thread files under `/proc/self/task` are this app's own files,
 * and they name the threads
 * the time went to. "250% of a core" is an accusation; "RenderThread 120%,
 * ExoPlayer:Playb 80%" is a lead.
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

    /** How many threads the report names, heaviest first. */
    private const val TOP_THREADS = 8

    /**
     * How much of the last crash the report carries.
     *
     * The tail, not the whole file: the top of the stack is where the answer
     * is, and older crashes would only bury it.
     */
    private const val CRASH_TAIL_CHARS = 4_000

    private const val TAG = "PerfMonitor"

    /** Process CPU as a percentage of one core, the newest sample. */
    private val _cpuPercent = MutableStateFlow(0f)
    val cpuPercent: StateFlow<Float> = _cpuPercent.asStateFlow()

    /** Every counted event, per second, the newest sample. */
    private val _eventsPerSecond = MutableStateFlow(0f)
    val eventsPerSecond: StateFlow<Float> = _eventsPerSecond.asStateFlow()

    /** One thread's share of one core, over a sample. */
    internal data class ThreadShare(val name: String, val percentOfCore: Float)

    private data class Sample(
        val atMillis: Long,
        val cpuPercent: Float,
        val threads: Int,
        val rssKb: Long,
        /** Delivered events per second, in [PerfCounters.Counter] order. */
        val perSecond: FloatArray,
        /** Which threads the time went to, heaviest first. */
        val threadShares: List<ThreadShare> = emptyList(),
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
        var lastThreadTicks = threadCpuTicks()
        var lastAt = SystemClock.elapsedRealtime()
        // First sample immediately, so the panel has a number to show rather
        // than a dash for the first two seconds.
        delay(250)
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val ticks = processCpuTicks()
            val threadTicks = threadCpuTicks()
            val totals = PerfCounters.totals()
            val elapsedMs = (now - lastAt).coerceAtLeast(1L)
            val seconds = elapsedMs / 1000f

            val cpuSeconds = (ticks - lastCpuTicks).coerceAtLeast(0L).toFloat() / clockTicksPerSecond
            val cpu = cpuSeconds / seconds * 100f
            val perSecond = FloatArray(counters.size) { index ->
                (totals[index] - lastTotals[index]).coerceAtLeast(0L) / seconds
            }

            // Where that CPU went, by thread name. A thread that has gone still
            // keeps its last reading in the total, so the difference is taken
            // per thread and summed — no stale thread can hide a live one.
            val threadShares = threadSharesBetween(lastThreadTicks, threadTicks, seconds)

            val sample = Sample(
                atMillis = now,
                cpuPercent = cpu,
                threads = threadCount(),
                rssKb = residentKb(),
                perSecond = perSecond,
                threadShares = threadShares,
            )
            samples.addLast(sample)
            while (samples.size > KEEP) samples.removeFirst()

            _cpuPercent.value = cpu
            _eventsPerSecond.value = perSecond.sum()

            lastTotals = totals
            lastCpuTicks = ticks
            lastThreadTicks = threadTicks
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
        lines.appendLine("monitor     : ${if (AppSettings.perfMonitor.value) "on" else "off"}")
        lines.appendLine("atmos       : ${atmosVerdict()}")
        lines.appendLine("cores       : $cores")
        lines.appendLine("samples     : ${samples.size} every ${SAMPLE_MS / 1000}s")
        if (started != null) {
            val wall = (samples.last().atMillis - started).coerceAtLeast(0L) / 1000L
            lines.appendLine("window      : ${wall}s")
        }
        lines.appendLine()
        lines.appendLine(
            "cpu% is this app's share of one core; 100% = one core fully busy, " +
                "cores = that number divided by 100.",
        )
        lines.appendLine(
            "The phone's own total is not readable from inside an app on " +
                "Android 10+, so nothing here is device-wide.",
        )
        lines.appendLine()
        lines.appendLine(
            "time      cpu%  cores  thr   rss     " +
                counters.joinToString(" ") { it.shortName() },
        )
        samples.forEach { sample ->
            lines.appendLine(
                format.format(Date(System.currentTimeMillis() - (samples.last().atMillis - sample.atMillis))) +
                    "  " +
                    "%5.1f".format(Locale.US, sample.cpuPercent) +
                    "  " +
                    "%5.2f".format(Locale.US, sample.cpuPercent / 100f) +
                    "  " +
                    "%4d".format(Locale.US, sample.threads) +
                    "  " +
                    "%5.0fMB".format(Locale.US, sample.rssKb / 1024f) +
                    "  " +
                    sample.perSecond.joinToString(" ") { "%.1f".format(Locale.US, it).padStart(8) },
            )
        }
        if (samples.isNotEmpty()) {
            lines.appendLine()
            lines.appendLine("summary")
            lines.appendLine(
                "  cpu     avg %.1f%%  peak %.1f%%   (%.2f / %.2f cores)".format(
                    Locale.US,
                    samples.map { it.cpuPercent }.average(),
                    samples.maxOf { it.cpuPercent },
                    samples.map { it.cpuPercent / 100f }.average(),
                    samples.maxOf { it.cpuPercent / 100f },
                ),
            )
            lines.appendLine(
                "  rss     avg %.0fMB  peak %.0fMB   threads avg %.0f  peak %d".format(
                    Locale.US,
                    samples.map { it.rssKb / 1024f }.average(),
                    samples.maxOf { it.rssKb / 1024f },
                    samples.map { it.threads }.average(),
                    samples.maxOf { it.threads },
                ),
            )
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
            val threads = threadRollup()
            if (threads.isNotEmpty()) {
                lines.appendLine()
                lines.appendLine("top threads (share of one core, over this window)")
                val nameWidth = threads.maxOf { it.name.length }.coerceIn(16, 32)
                threads.forEach { thread ->
                    lines.appendLine(
                        "  %-${nameWidth}s  avg %6.1f%%  peak %6.1f%%".format(
                            Locale.US,
                            thread.name,
                            thread.averagePercent,
                            thread.peakPercent,
                        ),
                    )
                }
            }
        } else {
            lines.appendLine()
            lines.appendLine(
                if (AppSettings.perfMonitor.value) {
                    "no samples yet — leave the monitor on for a few seconds"
                } else {
                    "no samples: the performance monitor was off, so nothing was timed"
                },
            )
        }
        // The totals are counted whether the monitor is on or not — one atomic
        // add per event — so they are in the log either way. They are the
        // cheap half of the answer: they say how many frames were read, how
        // many meshes were built and how many times the analyser ran, even
        // when nobody was watching the CPU column.
        val totals = PerfCounters.totals()
        lines.appendLine()
        lines.appendLine("counters since this process started")
        counters.forEachIndexed { index, counter ->
            lines.appendLine("  %-9s %d".format(Locale.US, counter.shortName(), totals[index]))
        }
        // The last crash, if there was one.
        //
        // "It crashed" with nothing attached is a report nobody can act on, and
        // the stack is already on disk — it only has to be somewhere the
        // listener can reach. The tail is enough: the top of the stack is
        // where the answer is, and a whole file of old crashes would bury it.
        val crash = runCatching { CrashLog.read(context) }.getOrNull()
        if (!crash.isNullOrBlank()) {
            lines.appendLine()
            lines.appendLine("last crash")
            lines.append(crash.takeLast(CRASH_TAIL_CHARS))
            if (!crash.endsWith("\n")) lines.appendLine()
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
     * What this phone does with the immersive mix, in one line.
     *
     * The question is asked once and cached — the codec list cannot change
     * while the app runs — so it costs nothing to put in every report, and it
     * answers a question the CPU column cannot: a phone whose Dolby
     * decoder is software burns whole cores for as long as it plays, and
     * that is worth being able to see rather than infer.
     */
    private fun atmosVerdict(): String = runCatching {
        val hardware = DeviceCodecs.decodesAtmosInHardware
        val playable = DeviceCodecs.playsDolbyAtmos
        when {
            hardware -> "hardware decoder"
            playable -> "software only — the lossless copy is played instead"
            else -> "no decoder — the spatial mix is never asked for"
        }
    }.getOrDefault("unknown")

    /**
     * Per-thread totals for the heaviest threads, averaged over the window.
     *
     * A thread's average counts only the samples it was in, divided by every
     * sample — so a thread that ran hot for one sample out of ten reads as a
     * tenth of its spike, not as its spike.
     */
    private fun threadRollup(): List<Rollup> {
        if (samples.isEmpty()) return emptyList()
        val byName = LinkedHashMap<String, MutableList<Float>>()
        samples.forEach { sample ->
            sample.threadShares.forEach { share ->
                byName.getOrPut(share.name) { mutableListOf() }.add(share.percentOfCore)
            }
        }
        return byName.map { (name, values) ->
            val total = values.sum()
            Rollup(
                name = name,
                averagePercent = total / samples.size,
                peakPercent = values.maxOrNull() ?: 0f,
            )
        }.sortedByDescending { it.averagePercent }.take(TOP_THREADS)
    }

    private class Rollup(
        val name: String,
        val averagePercent: Float,
        val peakPercent: Float,
    )

    /**
     * This process's CPU time, in clock ticks.
     *
     * `/proc/self/stat` rather than a system-wide counter: the question is
     * what *this* app costs, and a phone's total is mostly the game beside it.
     * The comm field is in parentheses and may itself contain spaces or
     * parentheses, so the split starts after the last one.
     */
    private fun processCpuTicks(): Long = runCatching {
        cpuTicksOf(File("/proc/self/stat").readText())
    }.getOrDefault(0L)

    /**
     * Every thread's CPU time, in clock ticks, by tid.
     *
     * Threads come and go between samples; a sampling pass that failed because
     * one of them exited would be a pass that never completes, so a thread
     * that cannot be read is simply absent from this map.
     */
    private fun threadCpuTicks(): Map<Int, Long> = runCatching {
        val tids = File("/proc/self/task").list() ?: return@runCatching emptyMap()
        val out = HashMap<Int, Long>(tids.size)
        for (tid in tids) {
            val id = tid.toIntOrNull() ?: continue
            val ticks = runCatching {
                cpuTicksOf(File("/proc/self/task/$id/stat").readText())
            }.getOrNull() ?: continue
            out[id] = ticks
        }
        out
    }.getOrDefault(emptyMap())

    /** utime + stime out of a `/proc/.../stat` line, whichever pid it names. */
    private fun cpuTicksOf(stat: String): Long {
        val afterComm = stat.substring(stat.lastIndexOf(')') + 2)
        val fields = afterComm.split(' ')
        // Fields are numbered from 1 across the whole line, so after the comm
        // field the list starts at field 3 (state). utime is 14, stime 15.
        val utime = fields[11].toLongOrNull() ?: 0L
        val stime = fields[12].toLongOrNull() ?: 0L
        return utime + stime
    }

    /**
     * The CPU each thread used between two samples, named, heaviest first.
     *
     * Names come from the thread itself (`/proc/self/task/<tid>/comm`), so the
     * report answers "which thread" with the name a stack trace would use
     * rather than a tid that means nothing an hour later. Several threads can
     * share a name — a pool's workers all do — and their time is summed,
     * because "six OkHttp threads at 4% each" is one answer, not six rows.
     */
    private fun threadSharesBetween(
        before: Map<Int, Long>,
        after: Map<Int, Long>,
        seconds: Float,
    ): List<ThreadShare> {
        if (seconds <= 0f) return emptyList()
        val ticksByName = HashMap<String, Long>()
        for ((tid, ticks) in after) {
            // A thread first seen in this pass has no baseline; its whole
            // life is newly visible, but most of that life was before the
            // window. Start it at zero and let the next pass measure it.
            val base = before[tid] ?: continue
            val delta = ticks - base
            if (delta <= 0L) continue
            val name = threadName(tid) ?: "tid $tid"
            ticksByName[name] = (ticksByName[name] ?: 0L) + delta
        }
        if (ticksByName.isEmpty()) return emptyList()
        return ticksByName
            .map { (name, ticks) ->
                ThreadShare(
                    name = name,
                    percentOfCore = ticks.toFloat() / clockTicksPerSecond / seconds * 100f,
                )
            }
            .sortedByDescending { it.percentOfCore }
            .take(TOP_THREADS)
    }

    /** A thread's own name, as it named itself. */
    private fun threadName(tid: Int): String? = runCatching {
        File("/proc/self/task/$tid/comm").readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun threadCount(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
        }
    }.getOrNull() ?: 0

    /** How many cores the phone has — the ceiling a share of one core is read against. */
    private val cores: Int by lazy {
        runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1).coerceAtLeast(1)
    }

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
        PerfCounters.Counter.RESOLVE -> "resolve"
        PerfCounters.Counter.PREPARE -> "prepare"
        PerfCounters.Counter.WEBVIEW -> "webview"
    }
}
