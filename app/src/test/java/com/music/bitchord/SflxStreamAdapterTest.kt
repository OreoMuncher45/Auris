package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxShim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How the adapter turns an extension's `download` into a stream answer, run.
 *
 * The real preamble and [SflxShim.ADAPTER] are executed in a JavaScript engine
 * against small extensions shaped like Amazon Music 2.3.19's downloader, and
 * the answers they return are what is checked. The app's own engine is an
 * Android library, so this uses Node, which CI runners carry; where it is
 * missing the tests are skipped rather than failed.
 */
class SflxStreamAdapterTest {

    companion object {
        private var results: Map<String, JsonObject> = emptyMap()

        @BeforeClass
        @JvmStatic
        fun run() {
            val node = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
                .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
                ?: return
            val manifest = SflxManifestParser.parse(
                """
                {"id":"amazon","name":"amazon","displayName":"Amazon Music","version":"2.3.19",
                 "type":["metadata_provider","download_provider"],
                 "qualityOptions":[{"id":"best","label":"FLAC Best Available","kind":"lossless"},
                                   {"id":"opus","label":"Opus 320kbps","kind":"lossy"}]}
                """.trimIndent(),
            )
            val dir = kotlin.io.path.createTempDirectory("sflx-adapter").toFile()
            try {
                val script = File(dir, "run.cjs")
                script.writeText(HARNESS_HEAD + SflxShim.preamble(manifest, "{}", "3.3", cacheDir = "/cache") +
                    "\n" + FAKE_EXTENSION + "\n" + SflxShim.ADAPTER + "\n" + HARNESS_TAIL)
                val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
                // Read alongside the wait, so a Node that stalls with stdout
                // open is timed out and killed rather than blocking the run.
                val output = StringBuilder()
                val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
                try {
                    check(process.waitFor(60, TimeUnit.SECONDS)) { "Node did not finish in 60s" }
                    reader.join(10_000)
                    check(process.exitValue() == 0) { output.toString() }
                } finally {
                    process.destroyForcibly()
                }
                val lines = Json.parseToJsonElement(output.lines().last { it.startsWith("[") }) as JsonArray
                results = lines.associate { entry ->
                    val case = entry.jsonObject
                    case.getValue("case").jsonPrimitive.content to case
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        private const val HARNESS_HEAD = """
            globalThis.__spine = new Proxy({}, { get: () => () => null });
            globalThis.__calls = [];
        """

        // Amazon's control flow, cut down: checkAvailability searches and
        // swaps in a different id; download prefers the prepared row's id,
        // renames the path when the file is encrypted, and returns the key.
        private const val FAKE_EXTENSION = """
            registerExtension({
              checkAvailability(isrc, name, artist, options) {
                __calls.push('checkAvailability');
                return { available: true, track_id: 'SEARCHED', prepared_context: { web_metadata: options.track || null } };
              },
              download(trackID, quality, outputPath, onProgress, options) {
                const prepared = options.preparedContext || {};
                const web = prepared.web_metadata || prepared.host_track || null;
                if (!web) __calls.push('getTrack');
                const id = web && web.provider_id === 'amazon' ? web.id : trackID;
                const mode = globalThis.__mode;
                const path = mode === 'encrypted' ? outputPath.replace(/\.[^.]+$/, '.m4a') : outputPath;
                if (mode === 'segments') {
                  file.downloadSegments([{ url: 'https://cdn.example/init' }, { url: 'https://cdn.example/1' }], path, {});
                  return { success: true, decryption_key: 'ffeeddccbbaa99887766554433221100', audio_codec: 'flac' };
                }
                const res = file.download('https://cdn.example/' + id, path, { headers: { a: '1' } });
                if (mode === 'reads-file') { file.getSize(path); __calls.push('read past the capture'); }
                if (mode === 'silent') return { success: true };
                return { success: true, decryption_key: mode === 'encrypted' ? '00112233445566778899aabbccddeeff' : '',
                         audio_codec: 'flac', bit_depth: 24, sample_rate: 96000 };
              },
            });
        """

        private const val HARNESS_TAIL = """
            (async () => {
              const row = { id: 'OWN', provider_id: 'amazon' };
              const cases = [
                ['own row', 'OWN', row, 'plain'],
                ['foreign row', 'OTHER', row, 'plain'],
                ['silent lossless', 'OWN', row, 'silent'],
                ['silent lossy', 'OWN', row, 'silent', 'opus'],
                ['encrypted', 'OWN', row, 'encrypted'],
                ['reads the file', 'OWN', row, 'reads-file'],
                ['encrypted segments', 'OWN', row, 'segments'],
              ];
              const out = [];
              for (const [name, id, track, mode, tier] of cases) {
                __calls.length = 0; globalThis.__mode = mode;
                const answer = JSON.parse(await module.exports.getTrackStreamUrl(id, tier || 'best', JSON.stringify({ settings: {}, track })));
                out.push({ case: name, answer, calls: __calls.slice(),
                           restored: JSON.stringify(file.getSize()).indexOf('does not expose') >= 0 });
              }
              console.log(JSON.stringify(out));
            })();
        """
    }

    private fun case(name: String): JsonObject {
        assumeTrue("Node is not on this machine", results.isNotEmpty())
        return results.getValue(name)
    }

    private fun JsonObject.answer() = getValue("answer").jsonObject
    private fun JsonObject.track() = answer().getValue("track").jsonObject
    private fun JsonObject.calls() = getValue("calls").jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.int

    @Test
    fun `a track from the extension's own search skips the availability check`() {
        val own = case("own row")
        assertFalse(own.calls().contains("checkAvailability"))
        assertFalse("the row is handed over as host_track", own.calls().contains("getTrack"))
        assertEquals("https://cdn.example/OWN", own.answer().text("streamUrl"))
    }

    @Test
    fun `a track from elsewhere keeps the availability check`() {
        assertTrue(case("foreign row").calls().contains("checkAvailability"))
    }

    @Test
    fun `an answer that names nothing is reported as its tier's declared kind`() {
        val lossless = case("silent lossless").track()
        assertEquals("LOSSLESS", lossless.text("audioQuality"))
        assertFalse("the offered placeholder path is not evidence", lossless.containsKey("mimeType"))
        val lossy = case("silent lossy").track()
        assertEquals("opus", lossy.text("audioQuality"))
    }

    @Test
    fun `the finished download states the codec, and an encrypted one its key`() {
        val plain = case("own row")
        assertEquals("audio/flac", plain.track().text("mimeType"))
        assertEquals(24, plain.track().number("bitDepth"))
        assertEquals(96000, plain.track().number("sampleRate"))
        assertFalse(plain.answer().containsKey("decryption"))
        val encrypted = case("encrypted").answer()
        assertEquals("00112233445566778899aabbccddeeff", encrypted.getValue("decryption").jsonObject.text("key"))
    }

    @Test
    fun `an encrypted segmented answer carries its key, so the host can refuse it`() {
        val segmented = case("encrypted segments").answer()
        assertTrue(segmented.containsKey("segments"))
        assertEquals("ffeeddccbbaa99887766554433221100", segmented.getValue("decryption").jsonObject.text("key"))
    }

    @Test
    fun `file work after the capture ends the call with the URL taken, and the stand-ins are restored`() {
        val reads = case("reads the file")
        assertFalse(reads.calls().contains("read past the capture"))
        assertEquals("https://cdn.example/OWN", reads.answer().text("streamUrl"))
        results.values.forEach { assertTrue("${it.text("case")}: file API restored", it.getValue("restored").jsonPrimitive.boolean) }
    }
}
