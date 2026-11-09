package com.music.bitchord.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last uncaught exception, written to a file the listener can send.
 *
 * Auris has no crash reporting and no telemetry, and it is not getting any — but
 * "it crashed" with nothing attached is a report nobody can act on, and a
 * desktop-side reader is not available to everyone. So the stack goes to a file
 * in the app's own storage, where it stays until it is read or overwritten. It
 * leaves the phone only if the listener chooses to send it.
 *
 * ## Why the previous handler is still called
 *
 * Android's default handler is what shows "Auris keeps stopping" and what tells
 * the system an app died. Swallowing it would leave a process that has crashed
 * and does not know it, so the chain is kept: this writes first, then hands the
 * exception on.
 *
 * Writing is best-effort throughout. A crash handler that throws replaces a
 * diagnosable crash with a mystery, and the file is worth less than the app's
 * own fatal path.
 */
object CrashLog {

    const val FILE_NAME = "crash.txt"

    /**
     * How much of the file is kept.
     *
     * Older entries are dropped off the front when it overflows, so the crash a
     * listener just had is always in there and the file cannot grow without
     * bound on a device nobody ever reads it on.
     */
    private const val MAX_BYTES = 256L * 1024

    @Volatile
    private var installed = false

    /** The file the last crash was written to, whether or not anything is in it. */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Everything recorded so far, or null when there is nothing. */
    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    /**
     * Records every uncaught exception from here on.
     *
     * Idempotent: [BitChordApplication] calls it once at startup, and a second
     * call would otherwise chain this handler to itself and recurse.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val application = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(application, thread, error) }
            // The system's own handler, kept for the reason the class note gives.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            append("=== ").append(when_).append("  thread=").append(thread.name).append('\n')
            val trace = java.io.StringWriter()
            error.printStackTrace(java.io.PrintWriter(trace))
            append(trace.toString())
            append('\n')
        }
        val target = file(context)
        val existing = runCatching { if (target.isFile) target.readText() else "" }.getOrDefault("")
        val combined = existing + report
        val kept = if (combined.length > MAX_BYTES) {
            combined.takeLast(MAX_BYTES.toInt())
        } else {
            combined
        }
        target.writeText(kept)
    }
}
