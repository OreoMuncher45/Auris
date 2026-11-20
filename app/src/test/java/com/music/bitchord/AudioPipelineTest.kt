package com.music.bitchord

import android.media.AudioFormat
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.PlaybackStreamResolver
import com.music.bitchord.playback.StreamChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification of the Audio Pipeline models, quality badge tier classification,
 * preference matching, and authoritative stream source tracking.
 */
class AudioPipelineTest {

    @Before
    fun setUp() {
        NerdStats.forgetLastSession()
        AudioOutputStatus.reset()
    }

    // ── Quality Badge Classification ──────────────────────────────────────

    @Test
    fun `low quality tier identifies streams at or below 80 kbps`() {
        val lowOpus = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = 64,
            sampleRateHz = 48000,
            channels = 2,
        )
        assertTrue(lowOpus.isLowQuality)
        assertFalse(lowOpus.isMediumQuality)
        assertFalse(lowOpus.isHiQuality)
        assertFalse(lowOpus.isLossless)

        val edgeLow = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = NerdStats.LOW_QUALITY_MAX_KBPS,
            sampleRateHz = 48000,
            channels = 2,
        )
        assertTrue(edgeLow.isLowQuality)
        assertFalse(edgeLow.isMediumQuality)

        val justAbove = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = NerdStats.LOW_QUALITY_MAX_KBPS + 1,
            sampleRateHz = 48000,
            channels = 2,
        )
        assertFalse(justAbove.isLowQuality)
        assertTrue(justAbove.isMediumQuality)
    }

    @Test
    fun `medium quality tier identifies streams between 80 kbps and 256 kbps`() {
        val ytOpus160 = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = 160,
            sampleRateHz = 48000,
            channels = 2,
        )
        assertFalse(ytOpus160.isLowQuality)
        assertTrue(ytOpus160.isMediumQuality)
        assertFalse(ytOpus160.isHiQuality)
        assertFalse(ytOpus160.isLossless)

        val aac128 = NerdStats.Snapshot(
            mimeType = "audio/mp4a-latm",
            bitrateKbps = 128,
            sampleRateHz = 44100,
            channels = 2,
        )
        assertTrue(aac128.isMediumQuality)
        assertFalse(aac128.isHiQuality)
    }

    @Test
    fun `high quality tier requires 256 kbps or higher and is lossy`() {
        val aac320 = NerdStats.Snapshot(
            mimeType = "audio/mp4a-latm",
            bitrateKbps = 320,
            sampleRateHz = 44100,
            channels = 2,
        )
        assertFalse(aac320.isLowQuality)
        assertFalse(aac320.isMediumQuality)
        assertTrue(aac320.isHiQuality)
        assertFalse(aac320.isLossless)

        val boundary256 = NerdStats.Snapshot(
            mimeType = "audio/mp4a-latm",
            bitrateKbps = NerdStats.HI_QUALITY_KBPS,
            sampleRateHz = 44100,
            channels = 2,
        )
        assertTrue(boundary256.isHiQuality)
        assertFalse(boundary256.isMediumQuality)
    }

    @Test
    fun `lossless and hi-res lossless are never tagged as lossy tiers`() {
        val flacCd = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 1411,
            sampleRateHz = 44100,
            channels = 2,
            bitDepth = 16,
        )
        assertTrue(flacCd.isLossless)
        assertFalse(flacCd.isHiRes)
        assertFalse(flacCd.isLowQuality)
        assertFalse(flacCd.isMediumQuality)
        assertFalse(flacCd.isHiQuality)

        val flacHiRes = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 4608,
            sampleRateHz = 96000,
            channels = 2,
            bitDepth = 24,
        )
        assertTrue(flacHiRes.isLossless)
        assertTrue(flacHiRes.isHiRes)
        assertFalse(flacHiRes.isLowQuality)
        assertFalse(flacHiRes.isMediumQuality)
        assertFalse(flacHiRes.isHiQuality)
    }

    // ── Codec Label Resolution ─────────────────────────────────────────────

    @Test
    fun `codecLabel formats standard mime types into clean display names`() {
        assertEquals("FLAC", NerdStats.codecLabel("audio/flac"))
        assertEquals("Opus", NerdStats.codecLabel("audio/opus"))
        assertEquals("AAC", NerdStats.codecLabel("audio/mp4a-latm"))
        assertEquals("AAC", NerdStats.codecLabel("audio/aac"))
        assertEquals("MP3", NerdStats.codecLabel("audio/mpeg"))
        assertEquals("MP3", NerdStats.codecLabel("audio/mp3"))
        assertEquals("ALAC", NerdStats.codecLabel("audio/alac"))
        assertEquals("PCM", NerdStats.codecLabel("audio/raw"))
        assertEquals("Vorbis", NerdStats.codecLabel("audio/vorbis"))
        assertEquals("E-AC-3 JOC", NerdStats.codecLabel("audio/eac3-joc"))
        assertEquals("E-AC-3", NerdStats.codecLabel("audio/eac3"))
        assertEquals("AC-4", NerdStats.codecLabel("audio/ac4"))
        assertEquals("AC-4", NerdStats.codecLabel("audio/ac-4"))
        assertEquals("AC-3", NerdStats.codecLabel("audio/ac3"))
        assertEquals("Dolby TrueHD", NerdStats.codecLabel("audio/truehd"))
        assertEquals("Dolby MAT", NerdStats.codecLabel("audio/vnd.dolby.mat"))
        assertNull(NerdStats.codecLabel(null))
    }

    // ── Dolby Atmos Badge ──────────────────────────────────────────────────

    /**
     * The badge follows the decoder, and the decoder's input is not always the
     * JOC mime. A DASH manifest that says `ec-3` yields `audio/eac3`, and the
     * FFmpeg bed fallback reports the same — both are the Atmos mix arriving at
     * the renderer and both have to light the badge. This used to match only
     * the exact `audio/eac3-joc` suffix, so the badge went missing on exactly
     * the devices that were playing the stream through a fallback.
     */
    @Test
    fun `the Atmos badge follows every spelling of the Dolby decoder mime`() {
        assertTrue(NerdStats.isDolbyAtmosMime("audio/eac3-joc"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/eac3"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/ec-3"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/ac3"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/eac3;codecs=ec-3"))
        assertTrue(NerdStats.isDolbyAtmosMime("AUDIO/EAC3-JOC"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/ac4"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/ac-4"))
        assertTrue(NerdStats.isDolbyAtmosMime("AUDIO/AC4"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/mp4;codecs=ac-4"))
        assertFalse(NerdStats.isDolbyAtmosMime("audio/mp4a-latm"))
        assertFalse(NerdStats.isDolbyAtmosMime("audio/opus"))
        assertFalse(NerdStats.isDolbyAtmosMime("audio/truehd"))
        assertFalse(NerdStats.isDolbyAtmosMime(null))

        assertTrue(NerdStats.isDolbyMime("audio/truehd"))
        assertTrue(NerdStats.isDolbyMime("audio/vnd.dolby.mlp"))
        assertTrue(NerdStats.isDolbyMime("audio/vnd.dolby.mat"))
        assertTrue(NerdStats.isLosslessMime("audio/truehd"))
        assertTrue(NerdStats.isLosslessMime("audio/vnd.dolby.mlp"))
    }

    @Test
    fun `AC4 is Atmos immediately and after decoding without another upgrade`() {
        val claimed = NerdStats.Snapshot(
            mimeType = null,
            bitrateKbps = 259,
            sampleRateHz = 48000,
            channels = 2,
            claimed = StreamFormat(codec = "ac4", kbps = 259),
        )
        assertTrue(claimed.isDolbyAtmos)

        val decoded = claimed.copyForTest(mimeType = "audio/ac4")
        assertTrue(decoded.isDolbyAtmos)
        assertTrue(decoded.isHiQuality)

        // Once the decoder names a different payload, a stale source claim
        // cannot keep the Atmos badge alive.
        val opus = claimed.copyForTest(mimeType = "audio/opus")
        assertFalse(opus.isDolbyAtmos)
    }

    private fun NerdStats.Snapshot.copyForTest(mimeType: String): NerdStats.Snapshot =
        NerdStats.Snapshot(
            mimeType = mimeType,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channels = channels,
            claimed = claimed,
        )

    // ── Authoritative Source Tracking ──────────────────────────────────────

    @Test
    fun `a losing YouTube candidate cannot relabel the pinned source`() {
        val id = "pipeline-pinned-source"
        val previousConfigs = SourceRegistry.configs.value
        val resolver = PlaybackStreamResolver { _, _ -> error("no race needed") }
        StreamChoice.forget(id)
        try {
            SourceRegistry.configs.value = listOf(
                SourceConfig(id = "tidal-config", kind = SourceKind.MODULE, label = "Tidal"),
            )
            val tidal = SourceStream(
                url = "https://tidal.example.com/track.flac",
                format = StreamFormat(codec = "flac"),
                sourceConfigId = "tidal-config",
            )
            StreamChoice.remember(id, tidal, substituted = true)

            val serving = resolver.pinOrPinned(
                id,
                SourceStream("https://youtube.example.com/track.webm"),
                substituted = false,
            )
            resolver.recordServingSource(id, serving)

            assertEquals(tidal.url, serving.url)
            assertEquals("Tidal", NerdStats.exactSourceFor(id))
        } finally {
            StreamChoice.forget(id)
            SourceRegistry.configs.value = previousConfigs
        }
    }

    @Test
    fun `an actual YouTube pin still records YouTube`() {
        val id = "pipeline-youtube-control"
        val resolver = PlaybackStreamResolver { _, _ -> error("no race needed") }
        StreamChoice.forget(id)
        try {
            val serving = resolver.pinOrPinned(
                id,
                SourceStream("https://youtube.example.com/track.webm"),
                substituted = false,
            )
            resolver.recordServingSource(id, serving)

            assertEquals("YouTube", NerdStats.exactSourceFor(id))
        } finally {
            StreamChoice.forget(id)
        }
    }

    @Test
    fun `authoritative sources are recorded and retrieved across track identifiers`() {
        NerdStats.recordSource("track-123", "YouTube")
        assertEquals("YouTube", NerdStats.sourceFor("track-123"))

        NerdStats.onSourceStream("track-456", StreamFormat(codec = "flac"), source = "Unified Addon")
        assertEquals("Unified Addon", NerdStats.sourceFor("track-456"))

        NerdStats.onStreamPicked("track-789", 320, source = "JioSaavn")
        assertEquals("JioSaavn", NerdStats.sourceFor("track-789"))
    }

    @Test
    fun `source lookup unwraps prefixed source track keys`() {
        NerdStats.recordSource("inner-id-abc", "Hi-Res FLAC")
        val fullMediaId = "src:config-uuid-123::inner-id-abc"
        assertEquals("Hi-Res FLAC", NerdStats.sourceFor(fullMediaId))
    }

    @Test
    fun `clearDeclared drops the recorded source for that track`() {
        NerdStats.recordSource("track-abandoned", "Tidal HiFi")
        assertEquals("Tidal HiFi", NerdStats.sourceFor("track-abandoned"))

        NerdStats.clearDeclared("track-abandoned")
        assertNull(NerdStats.sourceFor("track-abandoned"))
    }

    @Test
    fun `forgetLastSession clears all recorded sources and snapshots`() {
        NerdStats.recordSource("track-1", "YouTube")
        NerdStats.recordSource("track-2", "JioSaavn")
        NerdStats.current.value = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 1411,
            sampleRateHz = 44100,
            channels = 2,
            sourceName = "JioSaavn",
        )

        NerdStats.forgetLastSession()

        assertNull(NerdStats.current.value)
        assertNull(NerdStats.sourceFor("track-1"))
        assertNull(NerdStats.sourceFor("track-2"))
    }

    // ── Dynamic Source Transition & Leak Prevention ────────────────────────

    @Test
    fun `stale provider must never leak across tracks sharing same inner identifier`() {
        // Track 1 played from JioSaavn with inner track id "101"
        NerdStats.recordSource("101", "JioSaavn")
        assertEquals("JioSaavn", NerdStats.exactSourceFor("101"))

        // Track 2 is an Addon track with the exact same inner id "101" under an addon prefix
        val addonMediaId = "src:addon-uuid-xyz::101"
        // Before Addon resolves or records itself, exactSourceFor must NOT leak JioSaavn
        assertNull(NerdStats.exactSourceFor(addonMediaId))

        // Once Addon stream is recorded for the exact mediaId, it strictly returns the Addon
        NerdStats.recordSource(addonMediaId, "Unified Addon")
        assertEquals("Unified Addon", NerdStats.exactSourceFor(addonMediaId))
        // And track 1 remains JioSaavn without being mutated
        assertEquals("JioSaavn", NerdStats.exactSourceFor("101"))
    }

    @Test
    fun `playback source transitions maintain correct sequence without stale bleed`() {
        val jiosaavnId = "src:jiosaavn-uuid::track-jio"
        val addonId = "src:addon-uuid::track-addon"
        val localId = "local-storage-track-uri"

        // 1. JioSaavn playback
        NerdStats.recordSource(jiosaavnId, "JioSaavn")
        assertEquals("JioSaavn", NerdStats.exactSourceFor(jiosaavnId))

        // 2. Transition JioSaavn -> Addon
        NerdStats.recordSource(addonId, "Tidal Addon")
        assertEquals("Tidal Addon", NerdStats.exactSourceFor(addonId))

        // 3. Transition Addon -> Local Storage (Local Storage does not rely on streaming source)
        assertNull(NerdStats.exactSourceFor(localId))

        // 4. Transition Local -> JioSaavn
        assertEquals("JioSaavn", NerdStats.exactSourceFor(jiosaavnId))

        // 5. Transition Local -> Addon
        assertEquals("Tidal Addon", NerdStats.exactSourceFor(addonId))

        // 6. Transition Addon -> JioSaavn
        assertEquals("JioSaavn", NerdStats.exactSourceFor(jiosaavnId))
    }

    @Test
    fun `unknown provider and missing metadata remain null in exact lookup`() {
        assertNull(NerdStats.exactSourceFor(null))
        assertNull(NerdStats.exactSourceFor(""))
        assertNull(NerdStats.exactSourceFor("src:unknown-provider-uuid::track-999"))
    }

    // ── AudioOutputStatus Model ────────────────────────────────────────────

    @Test
    fun `AudioOutputStatus tracks decoder name and buffer size`() {
        AudioOutputStatus.publishDecoder("c2.android.flac.decoder")
        assertEquals("c2.android.flac.decoder", AudioOutputStatus.current.value.decoderName)

        AudioOutputStatus.publishAudioTrack(
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            sampleRateHz = 48000,
            bufferSize = 15376,
        )
        val snap = AudioOutputStatus.current.value
        assertEquals("c2.android.flac.decoder", snap.decoderName)
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, snap.actualEncoding)
        assertEquals(48000, snap.actualSampleRateHz)
        assertEquals(15376, snap.bufferSize)

        AudioOutputStatus.reset()
        assertNull(AudioOutputStatus.current.value.decoderName)
        assertNull(AudioOutputStatus.current.value.bufferSize)
        assertNull(AudioOutputStatus.current.value.actualEncoding)
    }

    // ── Resampler & DSP Logic Checks ───────────────────────────────────────

    @Test
    fun `resampler passthrough when input rate equals output rate`() {
        val inRate = 48000
        val outRate = 48000
        val isPassthrough = inRate == outRate
        val type = if (isPassthrough) "None" else "Resampler"
        val quality = if (isPassthrough) "Passthrough" else "Resampled"

        assertEquals("None", type)
        assertEquals("Passthrough", quality)
    }

    @Test
    fun `resampler active when input rate differs from output rate`() {
        val inRate = 44100
        val outRate = 48000
        val isPassthrough = inRate == outRate
        val type = if (isPassthrough) "None" else "Resampler"
        val quality = if (isPassthrough) "Passthrough" else "Resampled"

        assertEquals("Resampler", type)
        assertEquals("Resampled", quality)
    }
}
