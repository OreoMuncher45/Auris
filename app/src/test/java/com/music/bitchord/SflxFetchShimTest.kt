package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxShim
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `fetch` is synchronous for a Mornye extension.
 *
 * The mobile host answers `fetch(url, opts)` with a response object at once;
 * Amazon reads `res.status` on the next line. Handing it a Promise made every
 * read `undefined`, so config.json "failed", the session fell back to random,
 * and every search returned no results.
 */
class SflxFetchShimTest {

    private fun preamble(): String {
        val manifest = SflxManifestParser.parse(
            """
            {
              "id": "amazon",
              "name": "amazon",
              "displayName": "Amazon Music",
              "version": "2.3.19",
              "type": ["metadata_provider", "download_provider"],
              "permissions": {"network": ["na.mesk.skill.music.a2z.com"]}
            }
            """.trimIndent(),
        )
        return SflxShim.preamble(manifest, "{}", "3.2")
    }

    @Test
    fun `fetch answers with the response itself, not a promise`() {
        val js = preamble()
        assertTrue("fetch must be a plain function", js.contains("globalThis.fetch = function (url, options)"))
        assertFalse(
            "an async fetch is a Promise, and every res.status read is undefined",
            Regex("""globalThis\.fetch\s*=\s*async""").containsMatchIn(js),
        )
    }

    @Test
    fun `the shim supplies the URL web api quickjs lacks`() {
        // QuickJS is an ECMAScript engine, not a browser: no URL global.
        // Amazon's parser builds `new URL(deeplink, base)` per result and
        // swallows the ReferenceError, so a full search page yielded zero
        // tracks. Verified against the live catalogue in real QuickJS: 0
        // results before the polyfill, 14 after.
        val js = preamble()
        assertTrue("extensions need URL", js.contains("globalThis.URL = function (input, base)"))
        assertTrue("extensions need URLSearchParams", js.contains("globalThis.URLSearchParams = function (init)"))
        assertFalse(
            "must override the engine's href-only URL stub, not defer to it",
            js.contains("if (typeof URL === 'undefined')"),
        )
    }

    @Test
    fun `the response carries the fields an extension branches on`() {
        val js = preamble()
        for (field in listOf("ok: !!res.ok", "status:", "statusCode:", "text: function", "json: function", "headers: responseHeaders")) {
            assertTrue("response is missing $field", js.contains(field))
        }
    }
}
