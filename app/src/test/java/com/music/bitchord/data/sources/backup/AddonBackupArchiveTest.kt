package com.music.bitchord.data.sources.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddonBackupArchiveTest {

    private val manifest = """{"name":"demo","displayName":"Demo","version":"1.2.0","type":["metadata_provider"]}"""

    private fun contents() = AddonBackupContents(
        index = AddonBackupIndex(
            exportedAt = 1_790_000_000_000,
            appVersion = "3.4.0",
            sources = listOf(
                BackedUpSource(kind = "ADDON", label = "Mine", baseUrl = "https://addon.test", settings = mapOf("apiKey" to "k-123")),
            ),
            extensions = listOf(BackedUpExtension(id = "demo", displayName = "Demo", version = "1.2.0", enabled = false)),
        ),
        extensionFiles = mapOf(
            "demo" to mapOf(
                "manifest.json" to manifest.toByteArray(),
                "index.js" to "export default {}".toByteArray(),
                "settings.json" to """{"token":"t"}""".toByteArray(),
                "icon.png" to byteArrayOf(1, 2, 3),
            ),
        ),
        sessionFiles = mapOf("demo-0123456789abcdef.json" to """{"session_secret":"s"}""".toByteArray()),
    )

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun failure(block: () -> Unit): AddonBackupFailure {
        try {
            block()
        } catch (e: AddonBackupException) {
            return e.reason
        }
        fail("expected an AddonBackupException")
        error("unreachable")
    }

    @Test
    fun roundTripKeepsEverything() {
        val original = contents()
        val read = AddonBackupArchive.read(AddonBackupArchive.write(original))
        assertEquals(original.index, read.index)
        assertEquals(original.extensionFiles.keys, read.extensionFiles.keys)
        original.extensionFiles["demo"]!!.forEach { (name, bytes) ->
            assertArrayEquals(name, bytes, read.extensionFiles["demo"]!![name])
        }
        assertArrayEquals(
            original.sessionFiles.values.single(),
            read.sessionFiles["demo-0123456789abcdef.json"],
        )
    }

    @Test
    fun fullEnvelopeRoundTrip() {
        val sealed = AddonBackupCrypto.seal(AddonBackupArchive.write(contents()), "pw-12345".toCharArray(), 1_000)
        val read = AddonBackupArchive.read(AddonBackupCrypto.open(sealed, "pw-12345".toCharArray()))
        assertEquals("k-123", read.index.sources.single().settings["apiKey"])
        assertEquals(false, read.index.extensions.single().enabled)
    }

    @Test
    fun newerSchemaIsRefused() {
        val bytes = zip("addons.json" to """{"schema":${AddonBackupArchive.SCHEMA + 1}}""")
        assertEquals(AddonBackupFailure.NEWER_VERSION, failure { AddonBackupArchive.read(bytes) })
    }

    @Test
    fun olderOrMinimalIndexReadsWithDefaults() {
        // A file from an earlier build that wrote fewer fields still reads; unknown fields from a later build are skipped.
        val bytes = zip(
            "addons.json" to """{"schema":1,"sources":[{"kind":"ADDON","baseUrl":"https://a.test"}],"futureField":42}""",
        )
        val read = AddonBackupArchive.read(bytes)
        val source = read.index.sources.single()
        assertEquals("", source.label)
        assertTrue(source.enabled)
        assertTrue(source.settings.isEmpty())
        assertTrue(read.index.extensions.isEmpty())
    }

    @Test
    fun missingIndexIsDamaged() {
        assertEquals(AddonBackupFailure.DAMAGED, failure { AddonBackupArchive.read(zip("other.txt" to "x")) })
    }

    @Test
    fun notAZipIsDamaged() {
        assertEquals(AddonBackupFailure.DAMAGED, failure { AddonBackupArchive.read("not a zip at all".toByteArray()) })
    }

    @Test
    fun unsafeAndUnknownEntriesAreIgnored() {
        val bytes = zip(
            "addons.json" to "{}",
            "extensions/../../evil/index.js" to "x",
            "extensions/demo/../../index.js" to "x",
            "extensions/demo/manifest.json" to manifest,
            "extensions/demo/index.js" to "ok",
            "extensions/demo/cache/track.flac" to "decrypted",
            "extensions/demo/notes.txt" to "x",
            "extensions/.hidden/manifest.json" to manifest,
            "sessions/../../shared_prefs/x.json" to "x",
            "sessions/Upper.json" to "x",
            "sessions/ok-1.json" to "{}",
            "future/thing.bin" to "x",
        )
        val read = AddonBackupArchive.read(bytes)
        assertEquals(setOf("demo"), read.extensionFiles.keys)
        assertEquals(setOf("manifest.json", "index.js"), read.extensionFiles["demo"]!!.keys)
        assertEquals(setOf("ok-1.json"), read.sessionFiles.keys)
    }

    @Test
    fun extensionWithoutScriptIsDropped() {
        val bytes = zip("addons.json" to "{}", "extensions/demo/manifest.json" to manifest)
        assertTrue(AddonBackupArchive.read(bytes).extensionFiles.isEmpty())
    }

    @Test
    fun sessionNamesAreChecked() {
        assertTrue(AddonBackupArchive.isSessionName("spotiflac-0123abcd.json"))
        assertFalse(AddonBackupArchive.isSessionName("../x.json"))
        assertFalse(AddonBackupArchive.isSessionName("a/b.json"))
        assertFalse(AddonBackupArchive.isSessionName("x.json.tmp"))
        assertFalse(AddonBackupArchive.isSessionName(".json"))
    }

    @Test
    fun toStringsCarryNoSecrets() {
        val c = contents()
        listOf(c.toString(), c.index.toString(), c.index.sources.single().toString()).forEach {
            assertFalse(it, it.contains("k-123"))
        }
    }
}
