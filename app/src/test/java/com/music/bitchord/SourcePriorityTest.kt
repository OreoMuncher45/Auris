package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which source may answer, which one answers first, and what may be swapped in
 * halfway through a track.
 *
 * Three rules, and each of them was broken in a way that made the source list
 * mean something other than what it said:
 *
 *  - **The sources the user added are equal.** They are asked in the order they
 *    were added, every one of them is eligible on every rung that pays for a
 *    copy, and the best copy available wins. YouTube is last, not excluded.
 *  - **One track, one copy.** The first pin for a track is the one that stands,
 *    because two servers' bytes at the same offsets are two different files.
 *  - **The immersive rung is a tier, not a bonus.** Asked for and missed is not
 *    answered late by the tier below it, so a late lossless copy is refused
 *    under Dolby Atmos and no late lossless pass is queued.
 */
class SourcePriorityTest {

    private val lossy = StreamFormat(codec = "mp4", kbps = 320)
    private val lossless = StreamFormat(codec = "flac")
    private val atmos = StreamFormat(codec = "eac3-joc")

    private var wifi: AudioQuality = AudioQuality.LOSSLESS
    private var metered: Boolean? = null
    private var codecs: Boolean? = null

    @Before
    fun setUp() {
        wifi = AppSettings.audioQualityWifi.value
        metered = AppSettings.meteredConnection.value
        codecs = DeviceCodecs.forced
    }

    @After
    fun tearDown() {
        AppSettings.audioQualityWifi.value = wifi
        AppSettings.meteredConnection.value = metered
        DeviceCodecs.forced = codecs
        StreamChoice.forget("race")
        StreamChoice.forget("fresh")
    }

    /** The ceiling in force for the duration of [body]. */
    private fun on(rung: AudioQuality, atmosCapable: Boolean = true, body: () -> Unit) {
        AppSettings.audioQualityWifi.value = rung
        AppSettings.meteredConnection.value = false
        DeviceCodecs.forced = atmosCapable
        body()
    }

    // ── Which sources may answer ─────────────────────────────────────────────

    /**
     * The sources a user added are asked on every rung that pays for a copy.
     *
     * They used not to be: High excluded every source that could serve
     * lossless, which is every addon, and Medium and Low excluded everything
     * that was not YouTube. So an addon — the kind of source this app is
     * built around — was outranked by JioSaavn on the one rung where
     * JioSaavn was allowed to answer at all.
     */
    @Test
    fun `an addon is eligible on every rung that pays for a copy`() {
        for (rung in listOf(AudioQuality.DOLBY, AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.MEDIUM)) {
            assertTrue("$rung permits an addon", rung.permits(SourceKind.ADDON))
            assertTrue("$rung permits a custom module", rung.permits(SourceKind.CUSTOM_MODULE))
            assertTrue("$rung permits JioSaavn", rung.permits(SourceKind.JIOSAAVN))
            assertTrue("$rung permits YouTube", rung.permits(SourceKind.YOUTUBE))
        }
    }

    /**
     * Low is the one rung with a byte budget below a configured source's own
     * floor: 64kbps against a lowest tier of 128kbps. Letting one answer would
     * spend twice the data the rung exists to save.
     */
    @Test
    fun `the low rung is YouTube alone`() {
        assertTrue(AudioQuality.LOW.permits(SourceKind.YOUTUBE))
        assertFalse(AudioQuality.LOW.permits(SourceKind.ADDON))
        assertFalse(AudioQuality.LOW.permits(SourceKind.CUSTOM_MODULE))
        assertFalse(AudioQuality.LOW.permits(SourceKind.JIOSAAVN))
    }

    /** The order the list says: what the user added first, YouTube last. */
    @Test
    fun `the sources a user added come first, with YouTube last`() {
        assertEquals(SourceKind.USER_ADDED, SourceKind.ADDON.rank)
        assertEquals(SourceKind.USER_ADDED, SourceKind.CUSTOM_MODULE.rank)
        assertTrue(SourceKind.ADDON.rank < SourceKind.JIOSAAVN.rank)
        assertTrue(SourceKind.JIOSAAVN.rank < SourceKind.YOUTUBE.rank)
    }

    // ── One track, one copy ──────────────────────────────────────────────────

    /**
     * The first pin for a track is the one that stands.
     *
     * The cache entry for a track is shared by every writer for it, and the
     * bytes of two different servers at the same offsets are two different
     * recordings. So a prepared copy that lost the race must not be replaced
     * by the race's own answer.
     */
    @Test
    fun `the first pin for a track is the one that stands`() {
        assertTrue(
            "the first pin should take",
            StreamChoice.remember("race", SourceStream("https://prepared/track.flac"), substituted = true),
        )
        assertFalse(
            "a second pin must not take the entry over",
            StreamChoice.remember("race", SourceStream("https://youtube/track.webm"), substituted = false),
        )
        assertEquals("https://prepared/track.flac", StreamChoice.of("race")?.url)
    }

    /** A track released from its pin may be pinned afresh. */
    @Test
    fun `a forgotten track can be pinned afresh`() {
        StreamChoice.remember("fresh", SourceStream("https://prepared/track.flac"), substituted = true)
        StreamChoice.forget("fresh")
        assertTrue(
            StreamChoice.remember("fresh", SourceStream("https://youtube/track.webm"), substituted = false),
        )
        assertEquals("https://youtube/track.webm", StreamChoice.of("fresh")?.url)
    }

    // ── What may be swapped in late ──────────────────────────────────────────

    /**
     * Under the immersive rung, a lossless copy offered halfway through a track
     * is refused. The rung asked for the spatial mix; the spatial mix is not
     * held; and a FLAC cut in over the stereo copy is a downgrade dressed as
     * an upgrade.
     */
    @Test
    fun `the immersive rung refuses a late lossless swap`() {
        on(AudioQuality.DOLBY) {
            assertFalse(SourceResolver.worthSwapping(lossless, playing = lossy))
            assertTrue(
                "an Atmos answer is the one late swap the rung allows",
                SourceResolver.worthSwapping(atmos, playing = lossy),
            )
        }
    }

    /**
     * A lossless copy offered where the spatial mix was asked for is refused —
     * unless the listener asked for the upgrade themselves.
     *
     * "Upgrade quality" is a request for the best copy the sources hold, not
     * for the copy of one particular tier. Refusing it because the copy was
     * not the tier that was asked for is what made the row do nothing at all
     * on a device whose rung was Dolby.
     *
     * An immersive mix is still never swapped away from, asked for or not.
     */
    @Test
    fun `an asked-for lossless swap is allowed on the immersive rung`() {
        on(AudioQuality.DOLBY) {
            assertTrue(
                "the listener's own request is answered by the best copy available",
                SourceResolver.worthSwapping(lossless, playing = lossy, explicit = true),
            )
            assertFalse(
                "an immersive mix is never swapped away from",
                SourceResolver.worthSwapping(lossless, playing = atmos, explicit = true),
            )
        }
    }

    /** An immersive mix is never swapped away from. */
    @Test
    fun `an immersive mix is never swapped away from`() {
        on(AudioQuality.LOSSLESS) {
            assertFalse(SourceResolver.worthSwapping(lossless, playing = atmos))
            assertFalse(SourceResolver.worthSwapping(lossy, playing = atmos))
        }
    }

    /** On the lossless rung a bit-exact copy is always worth the seam. */
    @Test
    fun `the lossless rung accepts a late bit-exact copy`() {
        on(AudioQuality.LOSSLESS) {
            assertTrue(SourceResolver.worthSwapping(lossless, playing = lossy))
        }
    }

    /**
     * The immersive rung queues no late lossless pass at all: a tier that was
     * asked for and missed is not answered by the tier below it.
     */
    @Test
    fun `the immersive rung queues no late lossless pass`() {
        on(AudioQuality.DOLBY) {
            assertFalse(QualityUpgrade.needsLosslessFollowUp(lossy))
            assertFalse(QualityUpgrade.needsLosslessFollowUp(lossless))
        }
    }

    /** The lossless rung asks again, and stops asking once it has the copy. */
    @Test
    fun `the lossless rung asks again for the bit-exact copy`() {
        on(AudioQuality.LOSSLESS) {
            assertTrue(QualityUpgrade.needsLosslessFollowUp(lossy))
            assertFalse(QualityUpgrade.needsLosslessFollowUp(lossless))
            assertFalse(QualityUpgrade.needsLosslessFollowUp(atmos))
        }
    }

    /** A device that cannot decode Atmos is not on the immersive rung at all. */
    @Test
    fun `a device without an Atmos decoder keeps the lossless ladder`() {
        on(AudioQuality.DOLBY, atmosCapable = false) {
            assertTrue(QualityUpgrade.needsLosslessFollowUp(lossy))
            assertTrue(SourceResolver.worthSwapping(lossless, playing = lossy))
        }
    }
}
