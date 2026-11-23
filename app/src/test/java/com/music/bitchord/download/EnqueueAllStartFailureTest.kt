package com.music.bitchord.download

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [Downloads.enqueueAll] reports as admitted is what the sync records as
 * asked for, so a track it reports wrongly is one the next pass never retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EnqueueAllStartFailureTest {

    /** A context whose download service can't be started, as from the background. */
    private class NoForegroundStart(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent?): ComponentName? =
            throw IllegalStateException("not allowed to start a foreground service")
    }

    private val failing = NoForegroundStart(ApplicationProvider.getApplicationContext())

    /**
     * A context whose start fails, after [during] has run as a second caller
     * arriving while the first caller's start is still unanswered.
     */
    private class FailsAfter(base: Context, private val during: () -> Unit) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent?): ComponentName? {
            during()
            throw IllegalStateException("not allowed to start a foreground service")
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun inFlight(id: String) = Downloads.busy() && Downloads.active.value[id] is DownloadState.Queued

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    @After
    fun tearDown() {
        listOf("dup1", "f1", "f2", "p1").forEach(Downloads::cancel)
        DownloadSession.clear()
    }

    @Test
    fun `a track only provisionally queued by another call is not reported as already queued`() {
        var second: Set<String>? = null
        val first = FailsAfter(app) {
            // The sync arrives while the listener's batch waits on its start,
            // and its own start fails too.
            second = Downloads.enqueueAll(failing, listOf(song("p1")), automatic = true)
        }
        val firstResult = Downloads.enqueueAll(first, listOf(song("p1")), automatic = true)

        assertEquals(emptySet<String>(), second)
        assertEquals(emptySet<String>(), firstResult)
        assertFalse("the failed start took the track back off the queue", Downloads.busy())
    }

    @Test
    fun `a second call's successful start keeps the track the first call's failure would drop`() {
        var second: Set<String>? = null
        val first = FailsAfter(app) {
            second = Downloads.enqueueAll(app, listOf(song("p1")), automatic = true)
        }
        val firstResult = Downloads.enqueueAll(first, listOf(song("p1")), automatic = true)

        assertEquals(setOf("p1"), second)
        assertEquals(setOf("p1"), firstResult)
        assertTrue(inFlight("p1"))
    }

    @Test
    fun `a track listed twice is not admitted when the service fails to start`() {
        val admitted = Downloads.enqueueAll(failing, listOf(song("dup1"), song("dup1")), automatic = true)
        assertTrue("nothing was queued, so nothing is admitted: $admitted", admitted.isEmpty())
        assertTrue(Downloads.active.value["dup1"] is DownloadState.Failed)
    }

    @Test
    fun `a failed start admits none of the tracks it took back`() {
        val admitted = Downloads.enqueueAll(failing, listOf(song("f1"), song("f2"), song("f1")), automatic = true)
        assertEquals(emptySet<String>(), admitted)
    }
}
