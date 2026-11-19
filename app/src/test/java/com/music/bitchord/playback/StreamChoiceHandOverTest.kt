package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StreamChoice.handOver] releases a pin only once its bytes are gone and its
 * track is still away from the playhead. Ids are unique to this test because
 * [StreamChoice] is process-wide.
 */
class StreamChoiceHandOverTest {

    private val ids = listOf("handover-free", "handover-held", "handover-reader", "handover-yt")

    @After
    fun tearDown() = ids.forEach(StreamChoice::forget)

    private fun pin(id: String) {
        StreamChoice.remember(id, SourceStream("https://example.invalid/$id", sourceConfigId = "off"), substituted = true)
    }

    @Test
    fun aPinWhoseCacheEntryCouldNotBeRemovedIsKept() = runTest {
        pin("handover-free")
        pin("handover-held")
        val stale = mapOf("handover-free" to false, "handover-held" to false)

        // One entry fails to remove (an I/O failure), so its bytes remain.
        val released = StreamChoice.handOver(
            stale,
            drop = { asked, _ -> asked - "handover-held" },
            window = { emptySet() },
        )

        assertEquals(setOf("handover-free"), released)
        assertNull(StreamChoice.of("handover-free"))
        assertNotNull(StreamChoice.of("handover-held"))
    }

    @Test
    fun aTrackAReaderMovedOntoDuringTheDropKeepsItsPin() = runTest {
        pin("handover-reader")
        var window = emptySet<String>()

        val released = StreamChoice.handOver(
            mapOf("handover-reader" to false),
            drop = { asked, _ ->
                // The listener skips onto the track while its bytes are dropped.
                window = setOf("handover-reader")
                asked
            },
            window = { window },
        )

        assertEquals(emptySet<String>(), released)
        assertNotNull(StreamChoice.of("handover-reader"))
    }

    @Test
    fun aTrackPinnedAfreshDuringTheDropKeepsItsNewPin() = runTest {
        pin("handover-free")

        val released = StreamChoice.handOver(
            mapOf("handover-free" to false),
            drop = { asked, _ ->
                // The old pin goes and a new one is made while the bytes are dropped.
                StreamChoice.forget("handover-free")
                StreamChoice.remember("handover-free", SourceStream("https://example.invalid/new"), substituted = true)
                asked
            },
            window = { emptySet() },
        )

        assertEquals(emptySet<String>(), released)
        assertEquals("https://example.invalid/new", StreamChoice.of("handover-free")?.url)
    }

    /** A fake [StreamChoice.WriterGate]: [running] tracks have a fetch open, and [started] counts fetches per track. */
    private class FakeWriters : StreamChoice.WriterGate {
        val running = mutableSetOf<String>()
        val started = mutableMapOf<String, Long>()

        fun start(id: String) {
            started[id] = (started[id] ?: 0L) + 1
        }

        override fun token(videoId: String): Long? = if (videoId in running) null else started[videoId] ?: 0L

        override fun releaseIf(videoId: String, token: Long, release: () -> Boolean): Boolean =
            videoId !in running && (started[videoId] ?: 0L) == token && release()
    }

    @Test
    fun aTrackWithAWriterStillRunningIsNeitherDroppedNorReleased() = runTest {
        pin("handover-held")
        pin("handover-free")
        val writers = FakeWriters().apply { running += "handover-held" }
        val asked = mutableSetOf<String>()

        val released = StreamChoice.handOver(
            mapOf("handover-held" to false, "handover-free" to false),
            drop = { ids, _ -> asked += ids; ids },
            window = { emptySet() },
            writers = writers,
        )

        // Its bytes are not touched while it is written, and its pin stays.
        assertEquals(setOf("handover-free"), asked)
        assertEquals(setOf("handover-free"), released)
        assertNotNull(StreamChoice.of("handover-held"))
    }

    @Test
    fun aWriterThatStartsDuringTheDropKeepsThePin() = runTest {
        pin("handover-reader")
        val writers = FakeWriters()

        val released = StreamChoice.handOver(
            mapOf("handover-reader" to false),
            drop = { ids, _ ->
                // Read-ahead opens the track, on its old pin, while its bytes are dropped.
                writers.start("handover-reader")
                ids
            },
            window = { emptySet() },
            writers = writers,
        )

        assertEquals(emptySet<String>(), released)
        assertNotNull(StreamChoice.of("handover-reader"))
    }

    @Test
    fun eachSourceIsDroppedOnItsOwnSide() = runTest {
        pin("handover-free")
        StreamChoice.remember("handover-yt", SourceStream("https://example.invalid/yt"), substituted = false)
        val calls = mutableListOf<Pair<Set<String>, Boolean>>()

        StreamChoice.handOver(
            mapOf("handover-free" to false, "handover-yt" to true),
            drop = { asked, youtube -> calls += asked to youtube; asked },
            window = { emptySet() },
        )

        assertEquals(setOf(setOf("handover-free") to false, setOf("handover-yt") to true), calls.toSet())
        assertNull(StreamChoice.of("handover-free"))
        assertNull(StreamChoice.of("handover-yt"))
    }
}
