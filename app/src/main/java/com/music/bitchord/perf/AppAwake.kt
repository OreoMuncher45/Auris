package com.music.bitchord.perf

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether there is anyone to see the screen, and whether it is lit.
 *
 * Two different questions that stop the same work: the app being off screen
 * (a game in front, or the player dismissed) and the display being off (in a
 * pocket) both mean nothing the app draws is being looked at. Work that only
 * exists to be seen — the analyser's decodes, the clip's frames, the backdrop
 * — has no business running in either.
 *
 * Reads are cheap enough to ask per request: the lifecycle state is a field,
 * and `isInteractive` is one system call. Nothing here subscribes, so nothing
 * here can leak a receiver or keep a wakelock.
 */
object AppAwake {
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** True only with the app on screen and the display on. */
    fun isAwake(): Boolean = isOnScreen() && isScreenOn()

    fun isOnScreen(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun isScreenOn(): Boolean {
        val context = appContext ?: return true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }
}