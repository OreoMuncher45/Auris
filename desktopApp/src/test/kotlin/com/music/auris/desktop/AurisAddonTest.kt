package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AurisAddonTest {

    // ── Base URLs ────────────────────────────────────────────────────────

    @Test
    fun aPastedManifestUrlBecomesTheAddonsBase() {
        assertEquals("https://addon.example", AurisAddonClient.normalizeBase("https://addon.example/manifest.json"))
        assertEquals("https://addon.example", AurisAddonClient.normalizeBase("https://addon.example/"))
        assertEquals("https://addon.example", AurisAddonClient.normalizeBase("  https://addon.example  "))
        // Any .json filename, not only manifest.json: otherwise /search would be sent to
        // `…/addon.json/search`.
        assertEquals("https://addon.example/x", AurisAddonClient.normalizeBase("https://addon.example/x/addon.json"))
    }

    @Test
    fun aTokenBearingPathSurvivesNormalisation() {
        // The token is *in the path* and every later call has to carry it.
        assertEquals(
            "https://addon.example/t/s3cret",
            AurisAddonClient.normalizeBase("https://addon.example/t/s3cret/manifest.json"),
        )
    }

    // ── Track identity ───────────────────────────────────────────────────

    @Test
    fun trackKeysRoundTripAndNameTheirSource() {
        val key = AurisAddonSource.trackKey("source-1", "tidal/12345")
        val parsed = AurisAddonSource.parseTrack(key)

        assertEquals("source-1", parsed?.sourceId)
        assertEquals("tidal/12345", parsed?.trackId)
        // An ordinary YouTube id is not an addon track.
        assertNull(AurisAddonSource.parseTrack("dQw4w9WgXcQ"))
    }

    @Test
    fun aTrackIdMayContainTheSeparator() {
        val parsed = AurisAddonSource.parseTrack(AurisAddonSource.trackKey("s1", "a::b"))

        assertEquals("s1", parsed?.sourceId)
        assertEquals("a::b", parsed?.trackId)
    }

    // ── Stream metadata ──────────────────────────────────────────────────

    @Test
    fun bitrateIsReadInWhicheverUnitTheAddonUsed() {
        // A FLAC at 1411 and an MP3 at 320000 are the same claim, differently spelled; anything
        // over 3000 is bits per second.
        assertEquals(1411, AurisAddonStream(bitrate = 1411.0).kbps)
        assertEquals(320, AurisAddonStream(bitrate = 320_000.0).kbps)
        assertNull(AurisAddonStream().kbps)
    }

    @Test
    fun hiResDetailIsRecoveredFromFreeTextWhenTheFieldsAreMissing() {
        val stated = AurisAddonStream(sampleRate = 96_000.0, bitDepth = 24.0)
        assertEquals(96_000, stated.sampleRateHz)
        assertEquals(24, stated.bits)

        // Addons in the wild state neither field and put it all in the label.
        val labelled = AurisAddonStream(quality = "FLAC 24-bit 192 kHz 9216 kbps")
        assertEquals(192_000, labelled.sampleRateHz)
        assertEquals(24, labelled.bits)

        // kHz stated where the field is specified in Hz.
        assertEquals(44_100, AurisAddonStream(sampleRate = 44.1).sampleRateHz)
    }

    @Test
    fun onlyWordsThatCanOnlyMeanATransportAreReadAsOne() {
        // `format` usually holds a codec or a container, and "none" is a positive statement that
        // this is a file.
        assertEquals(AurisAddonStream.HLS, AurisAddonStream(manifest = "hls").transport)
        assertEquals(AurisAddonStream.HLS, AurisAddonStream(mediaType = "application/vnd.apple.mpegurl").transport)
        assertEquals(AurisAddonStream.DASH, AurisAddonStream(format = "dash").transport)
        assertNull(AurisAddonStream(format = "flac").transport)
        assertNull(AurisAddonStream(format = "none").transport)
    }

    @Test
    fun anythingButFalseNamesADrmScheme() {
        assertFalse(AurisAddonStream().isEncrypted)
        assertFalse(AurisAddonStream(encrypted = jsonOf("false")).isEncrypted)
        assertTrue(AurisAddonStream(encrypted = jsonOf("true")).isEncrypted)
        assertTrue(AurisAddonStream(encrypted = jsonOf("\"widevine\"")).isEncrypted)
    }

    @Test
    fun immersiveMixesAreRecognisedWhereverTheAddonSaysSo() {
        assertTrue(AurisAddonStream(quality = "Dolby Atmos").isDolbyAtmos)
        assertTrue(AurisAddonStream(audioModes = listOf("DOLBY_ATMOS")).isDolbyAtmos)
        assertTrue(AurisAddonStream(codec = "eac3").isDolbyAtmos)
        assertFalse(AurisAddonStream(quality = "FLAC 24-bit").isDolbyAtmos)

        assertTrue(AurisAddonTrack(audioModes = listOf("DOLBY_ATMOS")).isDolbyAtmos)
        assertTrue(AurisAddonTrack(atmos = true).isDolbyAtmos)
        assertFalse(AurisAddonTrack(audioModes = listOf("STEREO")).isDolbyAtmos)
    }

    // ── Manifests ────────────────────────────────────────────────────────

    @Test
    fun silenceAboutResourcesIsNotAClaimThatNothingWorks() {
        // An empty `resources` is settled by asking the endpoints, not by refusing here.
        assertTrue(AurisAddonManifest(id = "a").isPlayable)
        assertTrue(AurisAddonManifest(id = "a", resources = listOf("search")).isPlayable)
        // Everything starts by looking a track up by name.
        assertFalse(AurisAddonManifest(id = "a", resources = listOf("stream")).isPlayable)
    }

    @Test
    fun anUnnamedAddonFallsBackToItsId() {
        assertEquals("my-addon", AurisAddonManifest(id = "my-addon").displayName)
        assertEquals("Nice Name", AurisAddonManifest(id = "my-addon", name = "Nice Name").displayName)
    }

    // ── URL identification ───────────────────────────────────────────────

    @Test
    fun anAddonManifestIsIdentifiedAsAnAddon() {
        val body = """{"id":"example","name":"Example","version":"1.2","resources":["search","stream"]}"""

        val found = AurisSourceFormats.detect(body, "https://addon.example/manifest.json")

        assertTrue(found is AurisDetectedFormat.Addon)
        assertEquals("Example", found.manifest.displayName)
        assertEquals("https://addon.example", found.baseUrl)
    }

    @Test
    fun aModuleIndexIsIdentifiedAsOneAndCounted() {
        val body = """
            {"category:music":[
              {"id":"one","name":"One","download":"one.js"},
              {"id":"two","name":"Two","download":"two.js"}
            ]}
        """.trimIndent()

        val found = AurisSourceFormats.detect(body, "https://index.example/index.json")

        assertTrue(found is AurisDetectedFormat.ModuleIndex)
        assertEquals(2, found.moduleCount)
    }

    @Test
    fun aRefusalSaysWhatWasFoundRatherThanWhatWasWanted() {
        val notJson = AurisSourceFormats.detect("<html>nope</html>")
        assertTrue(notJson is AurisDetectedFormat.Unsupported)
        assertTrue(notJson.reason.contains("JSON"))

        val list = AurisSourceFormats.detect("""[{"id":"x"}]""")
        assertTrue(list is AurisDetectedFormat.Unsupported)

        val noId = AurisSourceFormats.detect("""{"name":"No id here"}""")
        assertTrue(noId is AurisDetectedFormat.Unsupported)
        assertTrue(noId.reason.contains("name"))

        val wrongResources = AurisSourceFormats.detect("""{"id":"x","resources":["stream"]}""")
        assertTrue(wrongResources is AurisDetectedFormat.Unsupported)
        assertTrue(wrongResources.reason.contains("search"))

        val emptyIndex = AurisSourceFormats.detect("""{"category:music":[]}""")
        assertTrue(emptyIndex is AurisDetectedFormat.Unsupported)
        assertTrue(emptyIndex.reason.contains("no modules"))
    }

    // ── Source order ─────────────────────────────────────────────────────

    @Test
    fun sourcesAreWalkedByRankAndThenByTheOrderTheyAreStoredIn() {
        val configs = listOf(
            AurisSourceConfig("youtube", AurisSourceKind.YOUTUBE),
            AurisSourceConfig("jio", AurisSourceKind.JIOSAAVN),
            AurisSourceConfig("addon-2", AurisSourceKind.ADDON, baseUrl = "https://b.example"),
            AurisSourceConfig("legacy", AurisSourceKind.MODULE, baseUrl = "https://m.example"),
            AurisSourceConfig("addon-1", AurisSourceKind.ADDON, baseUrl = "https://a.example"),
        )

        assertEquals(
            listOf("addon-2", "addon-1", "legacy", "jio", "youtube"),
            configs.inSourceOrder().map { it.id },
        )
    }

    @Test
    fun onlyTheSourcesSomebodyAddedCanBeRemoved() {
        assertTrue(AurisSourceConfig("a", AurisSourceKind.ADDON).isUserAdded)
        assertTrue(AurisSourceConfig("c", AurisSourceKind.CUSTOM_MODULE).isUserAdded)
        assertFalse(AurisSourceConfig("j", AurisSourceKind.JIOSAAVN).isUserAdded)
        assertFalse(AurisSourceConfig("y", AurisSourceKind.YOUTUBE).isUserAdded)
    }

    @Test
    fun duplicateAddonAddressesAreComparedAfterCanonicalisation() {
        val sources = listOf(
            AurisSourceConfig("a", AurisSourceKind.ADDON, baseUrl = "HTTPS://Addon.Example/path/"),
        )

        assertEquals("a", sources.duplicateOf("https://addon.example/path")?.id)
        assertNull(sources.duplicateOf("https://addon.example/path", exceptId = "a"))
    }

    @Test
    fun userAddedSourcesCanBeReorderedWithoutMovingBuiltInsIntoTheirGroup() {
        val sources = listOf(
            AurisSourceConfig("youtube", AurisSourceKind.YOUTUBE),
            AurisSourceConfig("a", AurisSourceKind.ADDON, baseUrl = "https://a.example"),
            AurisSourceConfig("jio", AurisSourceKind.JIOSAAVN),
            AurisSourceConfig("b", AurisSourceKind.ADDON, baseUrl = "https://b.example"),
        )

        assertEquals(
            listOf("b", "a", "jio", "youtube"),
            moveUserSource(sources, "b", -1).map { it.id },
        )
    }

    private fun jsonOf(raw: String) = AurisAddonClient.json.parseToJsonElement(raw)
}
