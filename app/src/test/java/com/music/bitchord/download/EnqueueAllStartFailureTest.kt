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

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    @After
    fun tearDown() = DownloadSession.clear()

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
