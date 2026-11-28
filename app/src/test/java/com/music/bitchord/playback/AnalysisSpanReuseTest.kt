package com.music.bitchord.playback

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * An analysis copy's cached head is extended only by the same stream: two
 * streams of one length are not one representation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AnalysisSpanReuseTest {

    private val length = 7L * 1024 * 1024

    private val first = SourceStream(
        "https://cdn-a.example/tracks/123/320.mp4?sig=one",
        format = StreamFormat(codec = "aac", kbps = 160),
        sourceConfigId = "jio",
    )

    // Another resolve of the same row: same length, another file.
    private val second = SourceStream(
        "https://cdn-b.example/tracks/123/96.opus?sig=two",
        format = StreamFormat(codec = "opus", kbps = 160),
        sourceConfigId = "addon",
    )

    @Test
    fun twoStreamsOfOneLengthAreTwoRepresentations() {
        assertNotEquals(AudioCache.streamIdentity(first), AudioCache.streamIdentity(second))
        // The head cached from the first: the second's tail is not appended.
        assertFalse(
            AudioCache.spansReusable(
                hasBytes = true,
                recordedIdentity = AudioCache.streamIdentity(first),
                currentIdentity = AudioCache.streamIdentity(second),
                recordedLength = length,
                total = length,
            ),
        )
    }

    @Test
    fun theSameStreamReResolvedRefillsOnlyWhatIsMissing() {
        // A fresh signature on the same file is the same representation.
        val again = first.copy(url = "https://cdn-a.example/tracks/123/320.mp4?sig=three")
        assertEquals(AudioCache.streamIdentity(first), AudioCache.streamIdentity(again))
        assertTrue(
            AudioCache.spansReusable(true, AudioCache.streamIdentity(first), AudioCache.streamIdentity(again), length, length),
        )
    }

    @Test
    fun anIdentityThatCannotBeVerifiedIsNotReused() {
        assertFalse(AudioCache.spansReusable(true, null, AudioCache.streamIdentity(first), length, length))
        assertFalse(AudioCache.spansReusable(true, AudioCache.streamIdentity(first), null, length, length))
        // An empty entry has nothing to mix with.
        assertTrue(AudioCache.spansReusable(false, null, null, 0L, length))
    }

    @Test
    fun aTailLeftWithNoHeadIsClearedNotExtended() {
        // An eviction took the head and left the old stream's tail: the entry
        // has spans though byte 0 is empty, and it is cleared before a new head
        // is written in front of it.
        var removed = false
        val prepared = AudioCache.prepareAnalysisEntry(
            hasSpans = true,
            recordedIdentity = AudioCache.streamIdentity(first),
            identity = AudioCache.streamIdentity(second),
            recordedLength = length,
            total = length,
            remove = { removed = true; true },
        )
        assertEquals(AudioCache.EntryPreparation.CLEARED, prepared)
        assertTrue(removed)
    }

    @Test
    fun aFailedClearStopsTheRoundBeforeAnythingIsStampedOrWritten() {
        // The old spans are held by a reader: the clear fails, and the entry is
        // refused, so the round neither stamps the new identity nor writes.
        val prepared = AudioCache.prepareAnalysisEntry(
            hasSpans = true,
            recordedIdentity = AudioCache.streamIdentity(first),
            identity = AudioCache.streamIdentity(second),
            recordedLength = length,
            total = length,
            remove = { false },
        )
        assertEquals(AudioCache.EntryPreparation.REFUSED, prepared)
    }

    @Test
    fun theSameStreamsSpansAreKeptWithoutAClear() {
        var removed = false
        val prepared = AudioCache.prepareAnalysisEntry(
            hasSpans = true,
            recordedIdentity = AudioCache.streamIdentity(first),
            identity = AudioCache.streamIdentity(first),
            recordedLength = length,
            total = length,
            remove = { removed = true; true },
        )
        assertEquals(AudioCache.EntryPreparation.REUSED, prepared)
        assertFalse(removed)
    }
}
