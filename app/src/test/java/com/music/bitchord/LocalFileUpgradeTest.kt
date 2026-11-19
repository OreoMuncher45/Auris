package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a song on this device may be looked at for a better copy.
 *
 * The listener's switch decides that the app may do it on its own; what is
 * *available* decides whether there is any point. The two used to disagree
 * about one source in particular: the one the file was downloaded from.
 *
 * An extension holds several tiers of one recording and answers with whichever
 * its own ladder reached, so it is the only source that can hold a better copy
 * of a track it downloaded — and it is also the source a listener is most
 * likely to have ranked *below* YouTube, because it is the one they reach for
 * when they want the catalogue rather than the default. Leaving it out of the
 * question made "upgrade local music" do nothing at all on every track an
 * extension had downloaded: the file was on the device, the FLAC was one call
 * away, and the call was never made.
 */
class LocalFileUpgradeTest {

    @Test
    fun `a source above YouTube that can serve lossless is enough`() {
        assertTrue(
            SourceResolver.losslessAvailable(
                candidates = listOf(FakeSource("ext-apple", SourceKind.MODULE)),
                active = listOf(FakeSource("ext-apple", SourceKind.MODULE)),
                alsoConfigId = null,
            ),
        )
    }

    @Test
    fun `the source the file came from counts whatever rank it has`() {
        // Ranked below YouTube, and the only lossless source there is.
        val extension = FakeSource("ext-apple", SourceKind.MODULE)
        assertTrue(
            "the source that made the file is the one that may hold a better tier",
            SourceResolver.losslessAvailable(
                candidates = emptyList(),
                active = listOf(extension),
                alsoConfigId = "ext-apple",
            ),
        )
    }

    @Test
    fun `a source that cannot serve lossless does not make it possible`() {
        assertFalse(
            SourceResolver.losslessAvailable(
                candidates = emptyList(),
                active = listOf(FakeSource("jiosaavn", SourceKind.JIOSAAVN)),
                alsoConfigId = "jiosaavn",
            ),
        )
    }

    @Test
    fun `a metadata-only source serves no audio and so no lossless copy`() {
        assertFalse(
            SourceResolver.losslessAvailable(
                candidates = emptyList(),
                active = listOf(FakeSource("ext-apple", SourceKind.MODULE, servesAudio = false)),
                alsoConfigId = "ext-apple",
            ),
        )
    }

    @Test
    fun `a source that is switched off does not count`() {
        // Not in the active list at all: the listener turned it off.
        assertFalse(
            SourceResolver.losslessAvailable(
                candidates = emptyList(),
                active = emptyList(),
                alsoConfigId = "ext-apple",
            ),
        )
    }

    @Test
    fun `nothing available means nothing is looked for`() {
        assertFalse(
            SourceResolver.losslessAvailable(
                candidates = listOf(FakeSource("jiosaavn", SourceKind.JIOSAAVN)),
                active = listOf(FakeSource("jiosaavn", SourceKind.JIOSAAVN)),
                alsoConfigId = null,
            ),
        )
    }

    /**
     * The source behind a track has to be switched on, and has to serve audio,
     * before it counts as somewhere a better copy can come from.
     *
     * The hand-asked upgrade uses this one: the automatic one asks for a
     * bit-exact copy (see [SourceResolver.losslessAvailable]), while a hand-
     * asked one may take any better copy — and so counts every source that
     * could stand in for YouTube as well as the source the file came from.
     * That source counts only while it is switched on: a source the listener
     * has turned off is not somewhere an upgrade can come from, and a source
     * that describes a catalogue rather than serving audio never was.
     */
    @Test
    fun `the source behind a track counts only while it can serve audio`() {
        val extension = FakeSource("ext-apple", SourceKind.MODULE)
        assertTrue(
            "an enabled source that serves audio counts",
            SourceResolver.servingAvailable(listOf(extension), "ext-apple"),
        )
        assertFalse(
            "a source that serves no audio does not count",
            SourceResolver.servingAvailable(
                listOf(FakeSource("ext-apple", SourceKind.MODULE, servesAudio = false)),
                "ext-apple",
            ),
        )
        assertFalse(
            "a source that has been switched off does not count",
            SourceResolver.servingAvailable(emptyList(), "ext-apple"),
        )
        assertFalse(
            "a track with no source behind it has nothing to count",
            SourceResolver.servingAvailable(listOf(extension), null),
        )
    }

    /**
     * A source that answers nothing, in every sense.
     *
     * The decision never calls a source — it is a question about the list,
     * not about the network — so the fake has to be complete enough to
     * satisfy the interface and quiet enough not to be reached.
     */
    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
        override val servesAudio: Boolean = true,
    ) : MusicSource {
        override val displayName: String = configId
        override suspend fun health(): SourceHealth = SourceHealth.Ok("fake")
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = emptyList()

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }
}
