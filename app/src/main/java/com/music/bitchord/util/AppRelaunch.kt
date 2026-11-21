package com.music.bitchord.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process

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
 * ## How
 *
 * The foreground activity starts [RelaunchActivity], which lives in a process of
 * its own (`:relaunch`, see the manifest). That activity kills the main process,
 * starts the launcher activity in a fresh task, and exits. This is the
 * ProcessPhoenix pattern.
 *
 * It replaces an AlarmManager `PendingIntent` that fired after the process had
 * been killed. Android 14 and 15 refuse that launch ("Background activity launch
 * blocked"): when the alarm fires, the app has no visible window, no process
 * and no opt-in, so the app closed and stayed closed. Every launch here comes
 * from an activity that is in the foreground at the moment it calls
 * `startActivity`. That is the case background-activity-start rules always
 * allow, on every API level. Nothing is timed against a process death, so
 * there is no race to lose.
 *
 * Why the second process. A process that starts an activity and then kills
 * itself races the system: the new activity may still be bound to the dying
 * process and get removed with it, leaving a blank task. The `:relaunch`
 * process outlives the main one, so the kill always lands before the launch.
 */
object AppRelaunch {

    /** Suffix of the process [RelaunchActivity] runs in; matches the manifest. */
    const val PROCESS_SUFFIX = ":relaunch"

    internal const val EXTRA_MAIN_PID = "com.music.bitchord.relaunch.MAIN_PID"

    /**
     * Kills this process and starts [context]'s app again.
     *
     * [context] should be, or wrap, the foreground activity, as every caller's
     * `LocalContext` does. Without one, the start falls back to the
     * application context: older versions allow it, and Android 14+ may not.
     *
     * Does nothing, and leaves the process running, when the package has no
     * launch intent. There is nothing to come back to, and exiting would leave
     * the listener with a dead app and no way back.
     */
    fun relaunch(context: Context) {
        val application = context.applicationContext
        restartIntent(application) ?: return
        val activity = context.findActivity()
        val intent = Intent(application, RelaunchActivity::class.java)
            .putExtra(EXTRA_MAIN_PID, Process.myPid())
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (activity ?: application).startActivity(intent)
    }

    /**
     * The launcher's own intent, in a cleared task. The listener comes back to
     * the app's normal start rather than to the settings screen they were in.
     */
    internal fun restartIntent(context: Context): Intent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        val component = launch.component ?: return null
        return Intent.makeRestartActivityTask(component)
    }

    /**
     * Whether [application] is the short-lived `:relaunch` process. The
     * application class skips its start-up work there: that process only
     * kills one pid and starts one activity.
     */
    fun isRelaunchProcess(application: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // API 26–27 have no getProcessName; the cmdline is the process name.
            runCatching {
                java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
            }.getOrNull()
        }
        return name != null && name.endsWith(PROCESS_SUFFIX)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}

/**
 * The relaunch trampoline. It runs in the `:relaunch` process, is translucent
 * and draws nothing, and lives for one `onCreate`.
 *
 * It kills the main process first, so the launcher activity it starts can only
 * come up in a new one. It starts that activity while it is itself the
 * foreground activity, then removes its own process.
 */
class RelaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(AppRelaunch.EXTRA_MAIN_PID, -1)
        if (mainPid > 0 && mainPid != Process.myPid()) Process.killProcess(mainPid)
        AppRelaunch.restartIntent(this)?.let(::startActivity)
        finish()
        Process.killProcess(Process.myPid())
    }
}
