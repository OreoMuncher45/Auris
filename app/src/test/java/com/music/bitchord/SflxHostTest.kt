package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxHost
import com.music.bitchord.data.sources.mornye.SflxManifest
import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxQuality
import com.music.bitchord.data.sources.mornye.SflxRows
import com.music.bitchord.data.sources.mornye.SflxShim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two vocabularies an extension meets.
 *
 * An `.sflx` extension describes a track its own way — `name`, `artists`,
 * `duration_ms`, `cover_url` — and this app describes one as `title`, `artist`,
 * `duration`, `albumCover`. Both the row mapping and the quality mapping are
 * where those two meet, and both fail *quietly* when they are wrong: a missing
 * duration shows as `0:00`, a quality the extension does not recognise is
 * simply its default, a missing id is a row that cannot be played. So they are
 * tested by exactly the cases that would otherwise go unnoticed.
 */
class SflxHostTest {

    // ── Rows ─────────────────────────────────────────────────────────────

    @Test
    fun `an extension row becomes this app's row`() {
        val parsed = SflxRows.parse(
            """
            {"tracks":[
              {"id":"track-1","title":"Song","artist":"Band","album":"Record",
               "albumCover":"https://img/1.jpg","duration":215,"audioQuality":"HI_RES_LOSSLESS",
               "format":"flac","raw":{"id":"track-1","name":"Song","isrc":"US1234567890"}}
            ],"total":1}
            """.trimIndent(),
        )

        assertEquals(1, parsed.response.tracks.size)
        val row = parsed.response.tracks.first()
        assertEquals("track-1", row.id)
        assertEquals("Song", row.title)
        assertEquals("Band", row.artist)
        assertEquals("Record", row.album)
        assertEquals("https://img/1.jpg", row.albumCover)
        assertEquals(215, row.duration)
        assertEquals("HI_RES_LOSSLESS", row.audioQuality)
        assertEquals("flac", row.format)
    }

    @Test
    fun `the extension's own object travels with the row`() {
        // It is what the download call hands back as prepared context: the ISRC
        // and the millisecond duration are things this app does not model and
        // the extension does.
        val parsed = SflxRows.parse(
            """{"tracks":[{"id":"a","title":"t","artist":"a","duration":1,"raw":{"id":"a","isrc":"US1"}}]}""",
        )
        assertEquals("US1", parsed.rows.first().raw?.get("isrc")?.toString()?.trim('"'))
    }

    @Test
    fun `a row with no id is dropped, because it cannot be played`() {
        val parsed = SflxRows.parse("""{"tracks":[{"title":"no id"},{"id":"","title":"blank"},"not an object"]}""")
        assertTrue(parsed.response.tracks.isEmpty())
    }

    @Test
    fun `a row with no name is still shown, filled in rather than hidden`() {
        // A search that returned something is worth showing even when the
        // extension was sloppy about naming it — the alternative is an album's
        // worth of rows disappearing over one missing field.
        val parsed = SflxRows.parse("""{"tracks":[{"id":"a"}]}""")
        assertEquals("Unknown track", parsed.response.tracks.first().title)
        assertEquals("Unknown artist", parsed.response.tracks.first().artist)
    }

    @Test
    fun `an answer that is not a search is empty rather than a crash`() {
        for (answer in listOf("", "not json", "{}", """{"tracks":"nope"}""", "[]")) {
            val parsed = SflxRows.parse(answer)
            assertTrue("$answer should map to no rows", parsed.response.tracks.isEmpty())
        }
    }

    // ── Quality ──────────────────────────────────────────────────────────

    private fun qobuzLike(): SflxManifest = SflxManifestParser.parse(
        """
        {
          "name": "qobuz-web", "type": ["download_provider"],
          "qualityOptions": [
            {"id":"HI_RES_LOSSLESS","kind":"lossless"},
            {"id":"HI_RES","kind":"lossless"},
            {"id":"LOSSLESS","kind":"lossless"},
            {"id":"MP3_320","kind":"lossy"}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `lossless maps to the best lossless tier the extension lists`() {
        assertEquals("HI_RES_LOSSLESS", SflxQuality.forTier(qobuzLike(), "LOSSLESS"))
    }

    @Test
    fun `high maps to a lossy tier rather than to a lossless one`() {
        // Asking for "high" and being given a hi-res FLAC is the whole bug this
        // mapping exists to avoid: the tier is a cap, not a wish.
        assertEquals("MP3_320", SflxQuality.forTier(qobuzLike(), "HIGH"))
    }

    @Test
    fun `low maps to the lowest lossy tier`() {
        assertEquals("MP3_320", SflxQuality.forTier(qobuzLike(), "LOW"))
    }

    @Test
    fun `an extension with no quality list is asked in this app's own words`() {
        val plain = SflxManifestParser.parse("""{"name":"plain","type":["download_provider"]}""")
        assertEquals("LOSSLESS", SflxQuality.forTier(plain, "LOSSLESS"))
        assertEquals("HIGH", SflxQuality.forTier(plain, "HIGH"))
    }

    @Test
    fun `an extension with only lossless tiers answers every tier with one of them`() {
        val losslessOnly = SflxManifestParser.parse(
            """
            {"name":"only","type":["download_provider"],
             "qualityOptions":[{"id":"HI_RES","kind":"lossless"},{"id":"LOSSLESS","kind":"lossless"}]}
            """.trimIndent(),
        )
        assertEquals("HI_RES", SflxQuality.forTier(losslessOnly, "LOSSLESS"))
        // No lossy tier to fall to, so the answer is the closest honest one
        // rather than a tier the extension has never heard of.
        assertEquals("LOSSLESS", SflxQuality.forTier(losslessOnly, "HIGH"))
    }

    @Test
    fun `the three kinds decide what an immersive request may be answered with`() {
        val manifest = SflxManifestParser.parse(
            """
            {"name":"spatial","type":["download_provider"],
             "qualityOptions":[
               {"id":"ATMOS","kind":"spatial"},
               {"id":"HI_RES","kind":"lossless"},
               {"id":"MP3_320","kind":"lossy"}]}
            """.trimIndent(),
        )
        // Lossless may reach Hi-Res and never the immersive mix.
        assertEquals("HI_RES", SflxQuality.forTier(manifest, "LOSSLESS"))
        assertEquals("MP3_320", SflxQuality.forTier(manifest, "HIGH"))
        // The immersive request prefers the spatial tier, and is the only
        // request that is allowed to.
        assertEquals("ATMOS", SflxQuality.forTier(manifest, "LOSSLESS", wantsAtmos = true))
    }

    @Test
    fun `a kind is inferred from the id when the manifest omits it`() {
        val manifest = SflxManifestParser.parse(
            """
            {"name":"old","type":["download_provider"],
             "qualityOptions":[{"id":"hi_res"},{"id":"DOLBY_ATMOS"},{"id":"aac_160"}]}
            """.trimIndent(),
        )
        // "hi_res" is lossless by name, "DOLBY_ATMOS" is spatial, and the AAC
        // one is what a lossy request gets.
        assertEquals("hi_res", SflxQuality.forTier(manifest, "LOSSLESS"))
        assertEquals("DOLBY_ATMOS", SflxQuality.forTier(manifest, "LOSSLESS", wantsAtmos = true))
        assertEquals("aac_160", SflxQuality.forTier(manifest, "HIGH"))
    }

    @Test
    fun `a spatial request falls back to lossless and not the other way round`() {
        val manifest = SflxManifestParser.parse(
            """
            {"name":"spatialless","type":["download_provider"],
             "qualityOptions":[{"id":"HI_RES","kind":"lossless"},{"id":"MP3_320","kind":"lossy"}]}
            """.trimIndent(),
        )
        assertEquals("HI_RES", SflxQuality.forTier(manifest, "LOSSLESS", wantsAtmos = true))
    }

    // ── Atmos, not merely surround ───────────────────────────────────────

    /**
     * Amazon's own manifest, as published.
     *
     * It lists two spatial tiers, and the first one is not the immersive mix:
     * `eac3` is Dolby Digital Plus — surround — and `ac4` is Dolby Atmos.
     * They are different files, and only one of them is what "Dolby Atmos"
     * means.
     */
    private fun amazonLike(): SflxManifest = SflxManifestParser.parse(
        """
        {
          "name": "amazon", "type": ["download_provider"],
          "qualityOptions": [
            {"id":"best","label":"FLAC Best Available","kind":"lossless"},
            {"id":"opus","label":"Opus 320kbps","kind":"lossy"},
            {"id":"eac3","label":"Dolby Digital Plus","kind":"spatial"},
            {"id":"ac4","label":"Dolby Atmos (AC-4)","kind":"spatial"}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `an immersive request asks for the Atmos option and not the surround one`() {
        // Taking the first spatial option — which is what this used to do —
        // answered "Dolby Atmos" with an E-AC-3 stream the device may not even
        // decode, and never asked for the AC-4 master at all.
        assertEquals("ac4", SflxQuality.forTier(amazonLike(), "LOSSLESS", wantsAtmos = true))
    }

    @Test
    fun `the canonical Atmos id is taken as it is`() {
        val tidal = SflxManifestParser.parse(
            """
            {"name":"tidal-web","type":["download_provider"],
             "qualityOptions":[
               {"id":"DOLBY_ATMOS","label":"Dolby Atmos","kind":"spatial"},
               {"id":"HI_RES_LOSSLESS","label":"HiRes FLAC","kind":"lossless"},
               {"id":"LOSSLESS","label":"Lossless","kind":"lossless"},
               {"id":"HIGH","label":"High","kind":"lossy"},
               {"id":"LOW","label":"Low","kind":"lossy"}]}
            """.trimIndent(),
        )
        assertEquals("DOLBY_ATMOS", SflxQuality.forTier(tidal, "LOSSLESS", wantsAtmos = true))
    }

    @Test
    fun `an extension with surround but no Atmos answers with lossless`() {
        // Surround is not Atmos: the two are different mixes and a device
        // without the right decoder cannot play either. What is offered
        // instead is the bit-exact master, which is what the listener asked
        // for before the immersive rung was tried.
        val surroundOnly = SflxManifestParser.parse(
            """
            {"name":"surround","type":["download_provider"],
             "qualityOptions":[
               {"id":"eac3","label":"Dolby Digital Plus","kind":"spatial"},
               {"id":"best","label":"FLAC Best Available","kind":"lossless"}]}
            """.trimIndent(),
        )
        assertEquals("best", SflxQuality.forTier(surroundOnly, "LOSSLESS", wantsAtmos = true))
    }

    @Test
    fun `a lossless request never takes an immersive tier`() {
        // The immersive rung is its own request, not a bonus on top of a
        // bit-exact one.
        assertEquals("best", SflxQuality.forTier(amazonLike(), "LOSSLESS"))
    }

    // ── Routing ──────────────────────────────────────────────────────────

    @Test
    fun `an extension's source address round-trips`() {
        val url = SflxHost.configUrlFor("qobuz-web")
        assertEquals("sflx://qobuz-web", url)
        assertEquals("qobuz-web", SflxHost.extensionIdOf(url))
    }

    @Test
    fun `an ordinary source address is not mistaken for an extension`() {
        for (url in listOf("", "https://example.com/index.json", "sflx", "sflx:/", "sflx://")) {
            assertNull("'$url' is not an extension", SflxHost.extensionIdOf(url))
        }
    }

    // ── The shim ─────────────────────────────────────────────────────────

    @Test
    fun `a manifest cannot close the string it is injected into`() {
        // Every value in the preamble comes from a package written by a
        // stranger. An id holding a quote would otherwise end the literal and
        // put attacker-authored text in the script body.
        val hostile = SflxManifestParser.parse(
            """{"name":"evil\"; globalThis.pwned = true; var x = \"","type":["download_provider"]}""",
        )
        val preamble = SflxShim.preamble(hostile, "{}", "3.0.25")
        assertTrue("the quote must be escaped", preamble.contains("\\\""))
        assertTrue(preamble.contains("globalThis.registerExtension"))
        // Interpolated, not left as Kotlin. The whole preamble is built by
        // string templates, so a template that is not substituted reaches the
        // interpreter as source it cannot parse — and every extension would fail
        // to load with a syntax error rather than a specific complaint.
        assertTrue("the version must be substituted", preamble.contains("appVersion: \"3.0.25\""))
        assertFalse("no Kotlin template may survive", preamble.contains("jsString("))
        assertFalse("nor a dangling interpolation", preamble.contains("\${"))
    }

    @Test
    fun `the extension's tiers reach the host as JSON`() {
        val qualities = SflxShim.qualitiesOf(qobuzLike())
        assertTrue(qualities.contains("HI_RES_LOSSLESS"))
        assertTrue(qualities.contains("lossless"))
    }
}
