package com.music.bitchord.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Restarts the app, for the settings whose change cannot be half-applied.
 *
 * Used by the content-provider switch. Flow and Autoplay each keep learning
 * state — skips, bans, an hourly snapshot, a ranking model — and every part of it
 * was trained against whichever provider built it. Switching the provider inside
 * a live process means deciding, per cache and per model, whether it still
 * describes the feed it came from; the honest answer is "no" for all of them, and
 * the cheapest way to be sure of that is to start over.
 *
 * ## Why an alarm and not a direct launch
 *
 * A process cannot start an activity and then reliably kill itself: whatever the
 * app does between the two lines can win the race, and the half-dead result is a
 * blank task. The launch is handed to the alarm clock far enough in the future
 * that [Runtime.exit] has finished, then the process goes.
 *
 * The launch intent is the package's own — whatever the launcher would send —
 * with the task cleared, so the listener comes back to the app's normal start
 * rather than to the settings screen they were in.
 */
object AppRelaunch {

    /** Roughly how long the restart waits, so the kill lands first. */
    private const val RESTART_DELAY_MS = 250L

    /**
     * Kills this process and starts [context]'s app again.
     *
     * Does nothing, and leaves the process running, when the package has no
     * launch intent — there is nothing to come back to, and exiting would leave
     * the listener with a dead app and no way back.
     */
    fun relaunch(context: Context) {
        val application = context.applicationContext
        val launch = application.packageManager.getLaunchIntentForPackage(application.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val alarm = application.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getActivity(
            application,
            // A fixed request code, so a second switch before the first restart
            // has happened replaces that launch rather than queueing another.
            REQUEST_CODE,
            launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)

        // Killed from a thread of its own, and by pid rather than by
        // [Runtime.exit].
        //
        // The straightforward version — `Runtime.getRuntime().exit(0)` on the
        // caller's thread — runs the JVM's shutdown sequence from inside a
        // Compose click handler, on the main thread, in the middle of a frame.
        // That is what crashed the app on the provider switch: the process died
        // badly enough to take the activity with it and the alarm's launch
        // arrived at a dying process. This is what a restart utility does
        // instead: leave the UI thread alone, wait past the next frame, and kill
        // the pid outright, so nothing is unwound and nothing can fail while it
        // is.
        Thread {
            runCatching { Thread.sleep(RESTART_DELAY_MS) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    private const val REQUEST_CODE = 1
}
