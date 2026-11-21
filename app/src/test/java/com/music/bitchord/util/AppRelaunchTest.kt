package com.music.bitchord.util

import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The catalogue switch's restart is started by the foreground activity.
 *
 * It used to be an AlarmManager PendingIntent that fired after the process
 * was gone. Android 14/15 block that as a background activity launch, so the
 * app closed and stayed closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppRelaunchTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun relaunchStartsTrampolineFromTheForegroundActivity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AppRelaunch.relaunch(activity)

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("relaunch must start an activity from the foreground one", started)
        assertEquals(RelaunchActivity::class.java.name, started.component?.className)
        assertEquals(Process.myPid(), started.getIntExtra(AppRelaunch.EXTRA_MAIN_PID, -1))
        // From an activity, so no NEW_TASK: the start inherits its foreground status.
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun relaunchSchedulesNoAlarm() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AppRelaunch.relaunch(activity)

        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertNull("an alarm-fired launch is blocked on API 34+", alarms.nextScheduledAlarm)
    }

    @Test
    fun restartIntentIsTheLauncherInAClearedTask() {
        val restart = AppRelaunch.restartIntent(app)

        assertNotNull(restart)
        assertEquals("com.music.bitchord.MainActivity", restart!!.component?.className)
        val wanted = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertTrue(restart.flags and wanted == wanted)
    }
}
