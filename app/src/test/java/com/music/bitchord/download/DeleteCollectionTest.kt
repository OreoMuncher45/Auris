package com.music.bitchord.download

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeleteCollectionTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    @After
    fun tearDown() {
        Downloads.cancel("q1")
        Downloads.collections.value.keys.toList().forEach(Downloads::forgetCollection)
        DownloadSession.clear()
    }

    /** A track still downloading has no file to delete and would arrive after the release is gone. */
    @Test
    fun `deleting a release calls off its tracks still downloading`() = runBlocking {
        val target = DownloadTarget(id = "VLPLdel", title = "Going", playlist = true)
        Downloads.rememberCollection(target, listOf(song("q1")))
        // Exempt from the Wi-Fi and offline rule, whatever another test left.
        Downloads.preferCachedCopy(listOf("q1"))
        assertTrue(Downloads.enqueueAll(app, listOf(song("q1")), automatic = true) == setOf("q1"))
        assertTrue(Downloads.isInFlight("q1"))

        Downloads.deleteCollection(app, "VLPLdel")

        assertFalse("nothing of the deleted release is still queued", Downloads.isInFlight("q1"))
        assertFalse(Downloads.isDownloadedCollection("VLPLdel"))
    }

    /** A first fill reads the switch as the record is created, not as the pass began. */
    @Test
    fun `a record created with the switch since turned off is created off`() {
        val target = DownloadTarget(id = "LM", title = "Liked Music", playlist = true)
        Downloads.rememberCollection(target, listOf(song("l1")), switchOn = { false })
        assertFalse(Downloads.collections.value.getValue("LM").autoDownload)
    }
}
