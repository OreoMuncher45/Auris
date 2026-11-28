package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which cache entry a YouTube track opens when the key its current settings
 * name holds nothing — GitHub #60, "cached songs don't play".
 *
 * A track's key depends on settings that can change after it was cached:
 * ranking a source above YouTube files it under `videoId#alt2`, turning Dolby
 * Atmos off under `#alt2-noatmos`, and un-ranking moves it back to the plain
 * `videoId`. Before the sibling fallback, a song the Cached songs folder
 * listed — fully on disk — asked for an empty entry and, offline, had no
 * network to fill it.
 */
class AudioCacheSiblingTest {

    private val id = "abcdefghijk"
    private val plain = id
    private val alt = "$id#alt2"
    private val altNoAtmos = "$id#alt2-noatmos"

    /** A cache holding [complete] and [partial] entries; [kept] are the YouTube/JioSaavn ones. */
    private fun choose(
        preferred: String,
        offline: Boolean,
        complete: Set<String> = emptySet(),
        partial: Set<String> = emptySet(),
        kept: Set<String> = complete,
    ): String? = AudioCache.chooseCompleteSibling(
        videoId = id,
        preferred = preferred,
        holdsBytes = { it in complete || it in partial },
        offline = { offline },
        isComplete = { it in complete },
        isKeptOrigin = { it in kept },
    )

    @Test
    fun `offline, a song cached as plain YouTube plays after a source was ranked above YouTube`() {
        assertEquals(plain, choose(preferred = alt, offline = true, complete = setOf(plain)))
    }

    @Test
    fun `offline, a substituted copy plays after the source was un-ranked`() {
        assertEquals(alt, choose(preferred = plain, offline = true, complete = setOf(alt)))
    }

    @Test
    fun `offline, an Atmos copy plays after Atmos was turned off`() {
        assertEquals(alt, choose(preferred = altNoAtmos, offline = true, complete = setOf(alt)))
    }

    @Test
    fun `offline, an addon's complete copy is used too`() {
        // Offline there is nothing better to wait for; any whole file beats silence.
        assertEquals(
            alt,
            choose(preferred = plain, offline = true, complete = setOf(alt), kept = emptySet()),
        )
    }

    @Test
    fun `the preferred entry always wins when it holds anything`() {
        assertNull(choose(preferred = alt, offline = true, complete = setOf(plain), partial = setOf(alt)))
        assertNull(choose(preferred = plain, offline = false, complete = setOf(plain, alt)))
    }

    @Test
    fun `a half-cached sibling is never borrowed`() {
        // Reading a partial entry would start streaming into the middle of a
        // different rendition — the seam the separate keys exist to prevent.
        assertNull(choose(preferred = alt, offline = true, partial = setOf(plain)))
    }

    @Test
    fun `online with nothing ranked above YouTube, a kept YouTube copy under alt2 is reused`() {
        assertEquals(alt, choose(preferred = plain, offline = false, complete = setOf(alt)))
    }

    @Test
    fun `online, an addon copy under alt2 is not reused for a plain request`() {
        assertNull(choose(preferred = plain, offline = false, complete = setOf(alt), kept = emptySet()))
    }

    @Test
    fun `online with a source ranked above YouTube, the source still gets its chance`() {
        assertNull(choose(preferred = alt, offline = false, complete = setOf(plain)))
        assertNull(choose(preferred = altNoAtmos, offline = false, complete = setOf(alt)))
    }

    @Test
    fun `the plain entry is tried before the alt buckets`() {
        assertEquals(
            plain,
            choose(preferred = altNoAtmos, offline = true, complete = setOf(plain, alt)),
        )
    }

    @Test
    fun `the offline flag is not read when the preferred entry already has bytes`() {
        var asked = false
        AudioCache.chooseCompleteSibling(
            videoId = id,
            preferred = plain,
            holdsBytes = { true },
            offline = { asked = true; true },
            isComplete = { true },
            isKeptOrigin = { true },
        )
        assertEquals(false, asked)
    }

    @Test
    fun `online, the direct Premium entry still borrows a complete kept copy`() {
        // The listener removed a substitute source: the complete JioSaavn copy
        // under #alt2 plays instead of a fresh download, under a rendition too.
        com.music.bitchord.playback.smart.AutomixAnalysisSource.rendition = { "premium" }
        try {
            // The substitute buckets are the rendition's own too.
            val premiumAlt = AudioCache.altKeyOf(id, "")
            val premiumAltNoAtmos = AudioCache.altKeyOf(id, "-noatmos")
            assertEquals(premiumAlt, choose(preferred = "$id#premium", offline = false, complete = setOf(premiumAlt)))
            // Still not for a substitute bucket preferred online.
            assertNull(choose(preferred = premiumAlt, offline = false, complete = setOf(premiumAltNoAtmos)))
        } finally {
            com.music.bitchord.playback.smart.AutomixAnalysisSource.rendition = { null }
        }
    }
}
