package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who the second look asks.
 *
 * The "Upgrade quality" hunt during playback used to leave out the source
 * that was already serving the track. That is right for a catalogue — asking it
 * again reproduces the answer already playing — and wrong for a source that
 * holds several tiers of one recording: an extension asked for a bit-exact
 * copy, answered with a lossy one because its own fallback ladder was walked,
 * and asked again can answer with the tier it wanted the first time.
 *
 * It is also wrong when the extension is ranked *below* YouTube, which is the
 * ordinary case for an extension: the pool is everything above YouTube, so an
 * extension sitting below it was never in the pool at all, and the hunt had
 * nothing to ask for.
 *
 * These are the two readings, and they are both here because both were
 * shipped at one point.
 */
class SourceUpgradeCandidatesTest {

    @Test
    fun `the source already serving the track is left out`() {
        val serving = FakeSource("ext-apple", SourceKind.MODULE)
        val other = FakeSource("addon-1", SourceKind.ADDON)
        val pool = SourceResolver.upgradeCandidates(
            candidates = listOf(other, serving),
            active = listOf(other, serving),
            servedBy = "ext-apple",
            reaskServedBy = false,
        )
        assertEquals(listOf("addon-1"), pool.map { it.configId })
    }

    @Test
    fun `the source already serving the track is asked again`() {
        val serving = FakeSource("ext-apple", SourceKind.MODULE)
        val other = FakeSource("addon-1", SourceKind.ADDON)
        val pool = SourceResolver.upgradeCandidates(
            candidates = listOf(other, serving),
            active = listOf(other, serving),
            servedBy = "ext-apple",
            reaskServedBy = true,
        )
        // Asked again *and* kept in rank order: the serving source is not
        // moved to the front, it is put back where the listener put it.
        assertEquals(listOf("addon-1", "ext-apple"), pool.map { it.configId })
    }

    @Test
    fun `a source ranked below YouTube is asked again too`() {
        // The pool is everything above YouTube. An extension the listener
        // ranked below YouTube is not in it, and was never in it — so leaving
        // it out made "Upgrade quality" do nothing on every track an
        // extension was serving.
        val extension = FakeSource("ext-apple", SourceKind.MODULE)
        val pool = SourceResolver.upgradeCandidates(
            candidates = emptyList(),
            active = listOf(extension),
            servedBy = "ext-apple",
            reaskServedBy = true,
        )
        assertEquals(listOf("ext-apple"), pool.map { it.configId })
    }

    @Test
    fun `a source that is not enabled is not asked again`() {
        // The serving source is remembered by its id, which survives the
        // source being switched off. A disabled source is not asked.
        val serving = FakeSource("ext-apple", SourceKind.MODULE)
        val pool = SourceResolver.upgradeCandidates(
            candidates = emptyList(),
            active = emptyList(),
            servedBy = "ext-apple",
            reaskServedBy = true,
        )
        assertTrue(pool.isEmpty())
        // The id is all that was known; without a source carrying it, there
        // is nothing to ask.
        assertEquals(serving.configId, "ext-apple")
    }

    @Test
    fun `nothing is asked when nothing is known about the serving source`() {
        val other = FakeSource("addon-1", SourceKind.ADDON)
        val pool = SourceResolver.upgradeCandidates(
            candidates = listOf(other),
            active = listOf(other),
            servedBy = null,
            reaskServedBy = true,
        )
        assertEquals(listOf("addon-1"), pool.map { it.configId })
    }

    /**
     * A source that answers nothing, in every sense.
     *
     * The pool decision never calls a source — it is a question about the
     * list, not about the network — so the fake has to be complete enough to
     * satisfy the interface and quiet enough not to be reached.
     */
    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
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
