package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxShim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How the stream adapter treats an extension that decrypts the file it
 * downloads, run.
 *
 * The real preamble and [SflxShim.ADAPTER] run in Node against a small
 * extension shaped like Deezer 1.3.12's downloader: `file.download` to an
 * `.encrypted.flac` path, `file.transformPatternedBlocks` to the real one,
 * `file.delete` of the encrypted copy, and the decrypted path as `file_path`.
 * The host's `__spine.fileCall` is a recorder here — the folder's own rules
 * are [com.music.bitchord.data.sources.mornye.SflxFilesTest]'s business.
 * Where Node is missing the tests are skipped, as the adapter tests are.
 */
class SflxFileAdapterTest {

    companion object {
        private var withFiles: Map<String, JsonObject> = emptyMap()
        private var withoutFiles: Map<String, JsonObject> = emptyMap()

        @BeforeClass
        @JvmStatic
        fun run() {
            val node = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
                .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
                ?: return
            withFiles = runNode(node, HEAD_WITH_FILES)
            withoutFiles = runNode(node, HEAD_WITHOUT_FILES)
        }

        private fun runNode(node: String, head: String): Map<String, JsonObject> {
            val manifest = SflxManifestParser.parse(
                """
                {"id":"deezer","name":"deezer","displayName":"Deezer","version":"1.3.12",
                 "type":["metadata_provider","download_provider"],
                 "permissions":{"network":["*.dzcdn.net"],"storage":true,"file":true},
                 "qualityOptions":[{"id":"flac","label":"FLAC / Hi-Res"}]}
                """.trimIndent(),
            )
            val dir = kotlin.io.path.createTempDirectory("sflx-file-adapter").toFile()
            try {
                val script = File(dir, "run.cjs")
                script.writeText(head + SflxShim.preamble(manifest, "{}", "3.3", cacheDir = "/sandbox/deezer") +
                    "\n" + FAKE_DEEZER + "\n" + SflxShim.ADAPTER + "\n" + TAIL)
                val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
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
                return lines.associate { entry -> entry.jsonObject.getValue("case").jsonPrimitive.content to entry.jsonObject }
            } finally {
                dir.deleteRecursively()
            }
        }

        /** A host that gives the extension a folder: every file call is recorded and answered. */
        private const val HEAD_WITH_FILES = """
            globalThis.__calls = [];
            globalThis.__spine = {
              storageGet: () => null, storageSet: () => 'ok', storageRemove: () => 'ok',
              fileCall: (method, argsJson) => {
                const args = JSON.parse(argsJson);
                __calls.push(method + ' ' + args[0] + (method === 'download' ? ' ' + args[1] + ' ' + JSON.stringify(args[2]) : ''));
                if (method === 'download' && globalThis.__mode === 'download fails') return JSON.stringify({ success: false, error: 'HTTP 403' });
                if (method === 'download') return JSON.stringify({ success: true, path: args[1], size: 10 });
                if (method === 'transformPatternedBlocks') return JSON.stringify({ success: true, path: args[1], bytes_processed: 10 });
                return JSON.stringify({ success: true });
              },
            };
        """

        /** A host that gives it none, as for an extension whose manifest does not ask. */
        private const val HEAD_WITHOUT_FILES = """
            globalThis.__calls = [];
            globalThis.__spine = { storageGet: () => null, storageSet: () => 'ok', storageRemove: () => 'ok' };
        """

        // Deezer 1.3.12's download(), cut down to its file calls.
        private const val FAKE_DEEZER = """
            registerExtension({
              download(trackID, quality, outputPath, onProgress, options) {
                const mode = globalThis.__mode;
                const encryptedPath = outputPath.replace(/\.flac$/, '.encrypted.flac');
                const url = 'https://e-cdns-proxy-1.dzcdn.net/media/1/2/3/4/5/' + trackID + '/0123456789abcdef.flac';
                const res = file.download(url, encryptedPath, { headers: { 'User-Agent': 'UA' } });
                if (!res || !res.success) return { success: false, error_message: 'download: ' + (res && res.error) };
                if (mode === 'stream only') return { success: true, file_path: res.path };
                const input = mode === 'transforms another file' ? '/sandbox/deezer/other' : (res.path || encryptedPath);
                let progress = 0;
                const out = file.transformPatternedBlocks(input, outputPath,
                  { operation: 'decrypt', algorithm: 'blowfish', mode: 'cbc', key: '00', keyEncoding: 'hex',
                    iv: '0001020304050607', ivEncoding: 'hex', segmentSize: 2048, transformEvery: 3 },
                  function (done, total) { progress = done / total; });
                if (!out || !out.success) return { success: false, error_message: 'Failed to decrypt Deezer stream: ' + (out && out.error) };
                __calls.push('progress ' + progress);
                file.delete(input);
                if (mode === 'forgets its path') return { success: true };
                return { success: true, file_path: out.path, audio_codec: 'flac', actual_container: 'flac', bit_depth: 16, sample_rate: 44100 };
              },
            });
        """

        private const val TAIL = """
            (async () => {
              const cases = ['deezer', 'transforms another file', 'download fails', 'stream only', 'forgets its path'];
              const out = [];
              const before = [file.download, file.transformPatternedBlocks, file.delete];
              for (const name of cases) {
                __calls.length = 0; globalThis.__mode = name;
                const answer = JSON.parse(await module.exports.getTrackStreamUrl('3135556', 'flac',
                  JSON.stringify({ settings: {}, track: { id: '3135556' } })));
                out.push({ case: name, answer, calls: __calls.slice(),
                           restored: before[0] === file.download && before[1] === file.transformPatternedBlocks && before[2] === file.delete });
              }
              console.log(JSON.stringify(out));
            })();
        """
    }

    private fun with(name: String): JsonObject {
        assumeTrue("Node is not on this machine", withFiles.isNotEmpty())
        return withFiles.getValue(name)
    }

    private fun without(name: String): JsonObject {
        assumeTrue("Node is not on this machine", withoutFiles.isNotEmpty())
        return withoutFiles.getValue(name)
    }

    private fun JsonObject.answer() = getValue("answer").jsonObject
    private fun JsonObject.calls() = getValue("calls").jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.text(name: String) = get(name)?.jsonPrimitive?.content

    @Test
    fun `a downloader that decrypts its file is answered with the file`() {
        val case = with("deezer")
        val answer = case.answer()
        assertEquals("/sandbox/deezer/3135556.flac", answer.text("localFile"))
        assertNull("no URL: the CDN's bytes are still encrypted", answer.text("streamUrl"))
        assertEquals("", answer.text("error"))
        assertEquals(
            listOf(
                // The capture's URL, headers and path, fetched for real only
                // when the transform needs the bytes.
                "download https://e-cdns-proxy-1.dzcdn.net/media/1/2/3/4/5/3135556/0123456789abcdef.flac " +
                    "/sandbox/deezer/3135556.encrypted.flac {\"User-Agent\":\"UA\"}",
                "transformPatternedBlocks /sandbox/deezer/3135556.encrypted.flac",
                "progress 1",
                "delete /sandbox/deezer/3135556.encrypted.flac",
            ),
            case.calls(),
        )
        assertEquals("audio/flac", answer.getValue("track").jsonObject.text("mimeType"))
        assertTrue("the probe's substitutions are undone", case.getValue("restored").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `only the file it downloaded is fetched for it`() {
        val case = with("transforms another file")
        assertTrue(case.calls().none { it.startsWith("download") || it.startsWith("transform") })
        assertNull(case.answer().text("localFile"))
    }

    @Test
    fun `a download that fails is the extension's failure`() {
        val answer = with("download fails").answer()
        assertNull(answer.text("localFile"))
        assertNull(answer.text("streamUrl"))
        assertEquals("Failed to decrypt Deezer stream: HTTP 403", answer.text("error"))
    }

    @Test
    fun `a downloader that never asks for its file is still streamed, and nothing is downloaded`() {
        val case = with("stream only")
        assertTrue(case.calls().isEmpty())
        assertNull(case.answer().text("localFile"))
        assertEquals(
            "https://e-cdns-proxy-1.dzcdn.net/media/1/2/3/4/5/3135556/0123456789abcdef.flac",
            case.answer().text("streamUrl"),
        )
    }

    @Test
    fun `a file the extension does not name is no answer, and its download is cleaned up`() {
        val case = with("forgets its path")
        assertNull(case.answer().text("localFile"))
        assertEquals("the extension did not finish writing its file", case.answer().text("error"))
        assertEquals("delete /sandbox/deezer/3135556.encrypted.flac", case.calls().last())
    }

    @Test
    fun `without a folder the transform stops the probe as it always did`() {
        val case = without("deezer")
        assertTrue(case.calls().isEmpty())
        assertNull(case.answer().text("localFile"))
        assertEquals("true", case.answer().text("unfinished"))
        assertFalse(case.answer().text("streamUrl").isNullOrEmpty())
    }
}
