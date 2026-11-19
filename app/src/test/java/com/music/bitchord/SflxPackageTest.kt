package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxException
import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxPackages
import com.music.bitchord.data.sources.mornye.SflxRegistry
import com.music.bitchord.data.sources.mornye.SflxStore
import com.music.bitchord.data.sources.mornye.SflxTypes
import com.music.bitchord.data.sources.mornye.Versions
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.sflx` reading path: what the host will and will not run.
 *
 * A `.sflx` is a script the listener installs from a URL, so most of what is
 * tested here is refusal — a digest that does not match, an id that is not a
 * directory name, an archive missing its script, entries past their caps. The
 * package that reads cleanly is only worth anything if the ones that do not are
 * actually turned away.
 */
class SflxPackageTest {

    private fun manifest(
        name: String = "qobuz-web",
        displayName: String = "Qobuz",
        version: String = "1.2.23",
        types: String = "\"metadata_provider\", \"download_provider\"",
        minAppVersion: String = "4.9.1",
        network: String = "\"www.qobuz.com\", \"qobuz.com\", \"*.qobuz.com\"",
        extra: String = "",
    ): String = """
        {
          "name": "$name",
          "displayName": "$displayName",
          "version": "$version",
          "description": "Qobuz metadata and downloads",
          "type": [$types],
          "minAppVersion": "$minAppVersion",
          "capabilities": { "requiresNativeContainerConversion": false, "downloadFallbackTier": 1 },
          "permissions": { "network": [$network], "storage": true, "file": false },
          "settings": [ { "key": "quality", "type": "select", "label": "Quality", "options": ["24", "16"] } ]
          $extra
        }
    """.trimIndent()

    private fun packageBytes(
        manifest: String = manifest(),
        script: String = "function initialize(config) { return true; }",
        icon: ByteArray? = null,
        includeScript: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(SflxPackages.MANIFEST_ENTRY))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            if (includeScript) {
                zip.putNextEntry(ZipEntry(SflxPackages.SCRIPT_ENTRY))
                zip.write(script.toByteArray())
                zip.closeEntry()
            }
            if (icon != null) {
                zip.putNextEntry(ZipEntry("assets/icon.png"))
                zip.write(icon)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun store(): Pair<SflxStore, java.io.File> {
        val root = Files.createTempDirectory("sflx").toFile()
        return SflxStore(root) to root
    }

    @Test
    fun readsAPackageIntoItsManifestAndScript() {
        val pkg = SflxPackages.read(packageBytes(icon = byteArrayOf(1, 2, 3)))

        assertEquals("qobuz-web", pkg.manifest.id)
        assertEquals("Qobuz", pkg.manifest.displayName)
        assertEquals("1.2.23", pkg.manifest.version)
        assertTrue(pkg.manifest.isDownloadProvider)
        assertTrue(pkg.manifest.isMetadataProvider)
        assertTrue(pkg.script.contains("initialize"))
        assertEquals(3, pkg.icon?.size)
    }

    @Test
    fun anArchiveWithoutAScriptIsRefused() {
        val error = runCatching { SflxPackages.read(packageBytes(includeScript = false)) }.exceptionOrNull()

        assertTrue(error is SflxException)
        assertTrue(error?.message.orEmpty().contains("index.js"))
    }

    @Test
    fun bytesThatAreNotAnArchiveAreRefused() {
        assertTrue(runCatching { SflxPackages.read("not a zip".toByteArray()) }.isFailure)
    }

    @Test
    fun manifestKeysAreReadHoweverTheyAreCased() {
        // Mornye's own host matches manifest keys case-insensitively, because
        // the format was authored against a Go implementation that does. A
        // package that installs there has to install here.
        val manifest = """
            { "Name": "tidal-web", "DisplayName": "Tidal", "Version": "1.2.9",
              "Type": ["download_provider"], "MinAppVersion": "4.9.0",
              "Permissions": { "Network": ["tidal.com"] } }
        """.trimIndent()

        val parsed = SflxManifestParser.parse(manifest)

        assertEquals("tidal-web", parsed.id)
        assertEquals("Tidal", parsed.displayName)
        assertEquals("4.9.0", parsed.minAppVersion)
        assertEquals(listOf(SflxTypes.DOWNLOAD_PROVIDER), parsed.types)
        assertTrue(parsed.permissions.allowsHost("tidal.com"))
    }

    @Test
    fun aManifestWithNoTypeIsRefused() {
        val error = runCatching {
            SflxManifestParser.parse("""{ "name": "mystery", "version": "1.0" }""")
        }.exceptionOrNull()

        assertTrue(error is SflxException)
    }

    @Test
    fun theNetworkAllowListMatchesHostsAndSubdomainsButNothingElse() {
        val permissions = SflxManifestParser.parse(manifest()).permissions

        // The extension's own declared hosts, each spelled out — which is why
        // the real Qobuz manifest lists `qobuz.com` beside `*.qobuz.com`.
        assertTrue(permissions.allowsHost("www.qobuz.com"))
        assertTrue(permissions.allowsHost("qobuz.com"))
        assertTrue(permissions.allowsHost("play.qobuz.com"))
        assertTrue(permissions.allowsHost("Qobuz.com:443"))
        // A `*.` pattern is a subdomain match and does not cover the bare
        // domain, so a manifest that wants both has to say both.
        val wildcardOnly = SflxManifestParser.parse(manifest(network = "\"*.qobuz.com\"")).permissions
        assertTrue(wildcardOnly.allowsHost("play.qobuz.com"))
        assertFalse(wildcardOnly.allowsHost("qobuz.com"))
        // Wildcards are suffix matches, not "anything".
        assertFalse(permissions.allowsHost("evil-qobuz.com"))
        assertFalse(permissions.allowsHost("qobuz.com.evil.io"))
        assertFalse(permissions.allowsHost(""))
        assertFalse(SflxManifestParser.parse(manifest(network = "\"\"")).permissions.allowsHost("qobuz.com"))
    }

    @Test
    fun versionsCompareAsNumbersNotText() {
        assertTrue(Versions.isAtLeast("4.10.0", "4.9.1"))
        assertTrue(Versions.isAtLeast("4.9.1", "4.9.1"))
        assertTrue(Versions.isAtLeast("4.9", "4.9.0"))
        assertTrue(Versions.isAtLeast("5.0.0", "4.9.1"))
        assertFalse(Versions.isAtLeast("4.8.9", "4.9.1"))
        // A pre-release marker is not part of the number.
        assertTrue(Versions.isAtLeast("4.3.0", "4.3.0-beta"))
    }

    @Test
    fun aManifestOnlyRunsWhileTheHostIsNewEnough() {
        val parsed = SflxManifestParser.parse(manifest(minAppVersion = "4.9.1"))

        assertTrue(parsed.runsOn("4.9.1"))
        assertTrue(parsed.runsOn("4.10.0"))
        assertFalse(parsed.runsOn("4.8.0"))
        // No claim is not a refusal.
        assertTrue(SflxManifestParser.parse(manifest(minAppVersion = "")).runsOn("1.0"))
    }

    @Test
    fun theRegistryIsReadAndTheBrokenEntryIsDropped() {
        val registry = """
            { "version": 1, "extensions": [
              { "id": "qobuz-web", "name": "qobuz-web", "display_name": "Qobuz Web", "version": "1.2.23",
                "description": "Qobuz", "download_url": "https://example/qobuz.sflx", "sha256": "ABC123",
                "category": "download", "tags": ["lossless"], "min_app_version": "4.9.1",
                "icon_url": "https://example/qobuz.png" },
              { "id": "future", "name": "future", "version": "9.0", "download_url": "https://example/f.sflx",
                "sha256": "def", "min_app_version": "99.0" },
              { "id": "broken", "name": "broken" }
            ] }
        """.trimIndent()

        val entries = SflxRegistry.parse(registry)
        // The entry with no download URL or digest is dropped, not carried as a
        // row that could never install.
        assertEquals(2, entries.size)
        assertEquals("abc123", entries[0].sha256)
        assertEquals(listOf("lossless"), entries[0].tags)
    }

    @Test
    fun anEntryIsNotFilteredByTheHostsVersion() {
        // The publisher states the oldest *Mornye* that can run its package.
        // This app is a different host on a different version line, so that
        // number says nothing about whether the package works here — and
        // treating it as though it did emptied the store before: thirteen of
        // Mornye's fourteen extensions ask for a newer Mornye than this app's
        // version number happens to be, and every one of them was hidden.
        val registry = """
            { "extensions": [
              { "id": "qobuz-web", "name": "qobuz-web", "version": "1.2.23",
                "download_url": "https://example/qobuz.sflx", "sha256": "abc", "min_app_version": "4.9.1" }
            ] }
        """.trimIndent()

        val entry = SflxRegistry.parse(registry).single()

        assertEquals("4.9.1", entry.minAppVersion)
        // Kept, and the number is shown as what it is.
        assertEquals("Built for Mornye 4.9.1 or newer", SflxRegistry.hostNote(entry))
    }

    @Test
    fun aPackageForAnotherRuntimeIsNotOffered() {
        // The Python packages in Mornye's unified catalog are not extensions
        // that run slowly here; they are a different program in a different
        // runtime.
        val registry = """
            { "extensions": [
              { "id": "utilities", "name": "utilities", "version": "1.1.4",
                "download_url": "https://example/u.sflx", "sha256": "abc", "runtime": "python" },
              { "id": "spotify-web", "name": "spotify-web", "version": "1.10.6",
                "download_url": "https://example/s.sflx", "sha256": "def", "runtime": "javascript" }
            ] }
        """.trimIndent()

        assertEquals(listOf("spotify-web"), SflxRegistry.parse(registry).map { it.id })
    }

    @Test
    fun aRepositoryLinkIsTurnedIntoTheAddressOfItsRegistry() {
        // The address a listener is given is usually some shape of the
        // repository rather than the file: the root, a folder, the GitHub page.
        val root = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension"
        assertEquals(
            listOf(
                root,
                "$root/main/registry.json",
                "$root/main/registry-unified.json",
                "$root/master/registry.json",
                "$root/master/registry-unified.json",
            ),
            SflxRegistry.candidates(root),
        )
        assertEquals(
            listOf(
                "https://github.com/mornyeapp/Mornye-Extension",
                "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json",
                "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry-unified.json",
                "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/master/registry.json",
                "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/master/registry-unified.json",
            ),
            SflxRegistry.candidates("https://github.com/mornyeapp/Mornye-Extension"),
        )
        // A trailing slash is the same address, and the folder inside it is
        // where the registry lives.
        assertTrue(
            SflxRegistry.candidates("$root/main/extensions/")
                .contains("$root/main/extensions/registry.json"),
        )
    }

    @Test
    fun aLinkThatIsAlreadyTheFileIsLeftAlone() {
        val exact = "https://example.com/store/registry.json"
        assertEquals(listOf(exact), SflxRegistry.candidates(exact))
        // A package link is not a repository, so nothing is guessed for it.
        val pkg = "https://example.com/thing.sflx"
        assertEquals(listOf(pkg), SflxRegistry.candidates(pkg))
    }

    @Test
    fun anInstallIsRefusedWhenTheDigestDoesNotMatch() {
        val (store, _) = store()
        val bytes = packageBytes()

        val error = runCatching { store.install(bytes, expectedSha256 = "00".repeat(32)) }.exceptionOrNull()

        assertTrue(error is SflxException)
        // And nothing was written: a refused package leaves no directory behind.
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun anInstallVerifiesTheDigestAndSurvivesARoundTrip() {
        val (store, root) = store()
        val bytes = packageBytes(icon = byteArrayOf(9, 9))
        val digest = SflxPackages.sha256(bytes)

        val installed = store.install(bytes, expectedSha256 = digest, sourceUrl = "https://example/qobuz.sflx")

        assertEquals("qobuz-web", installed.id)
        assertEquals(digest, installed.sha256)
        assertTrue(installed.scriptFile.isFile)
        assertNotNull(installed.iconFile)
        // Read back off disk, the way the host reads it on the next launch.
        val reread = store.find("qobuz-web")
        assertEquals(digest, reread?.sha256)
        assertEquals("https://example/qobuz.sflx", reread?.sourceUrl)
        assertEquals(1, store.list().size)
        assertTrue(java.io.File(root, "qobuz-web").isDirectory)
    }

    @Test
    fun reinstallingKeepsTheListenersSettingsAndDropsTheOldScript() {
        val (store, _) = store()
        store.install(packageBytes(script = "function initialize() { return \"old\"; }"))
        store.saveSettings("qobuz-web", kotlinx.serialization.json.buildJsonObject { put("quality", kotlinx.serialization.json.JsonPrimitive("24")) })

        store.install(packageBytes(script = "function initialize() { return \"new\"; }"))

        assertEquals("{\"quality\":\"24\"}", store.settings("qobuz-web").toString())
        assertTrue(store.find("qobuz-web")?.scriptFile?.readText().orEmpty().contains("new"))
    }

    @Test
    fun anUnsafeExtensionNameIsRefused() {
        val (store, root) = store()

        val error = runCatching { store.install(packageBytes(manifest = manifest(name = "../evil"))) }
            .exceptionOrNull()

        assertTrue(error is SflxException)
        assertFalse(java.io.File(root.parentFile, "evil").exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun removingAnExtensionTakesItsDirectoryAndSettingsWithIt() {
        val (store, root) = store()
        store.install(packageBytes())
        store.saveSettings("qobuz-web", kotlinx.serialization.json.buildJsonObject { })

        assertTrue(store.remove("qobuz-web"))

        assertNull(store.find("qobuz-web"))
        assertFalse(java.io.File(root, "qobuz-web").exists())
        assertFalse(store.remove("qobuz-web"))
    }

    @Test
    fun anUpdateIsNewerVersionNotMerelyADifferentOne() {
        val (store, _) = store()
        val installed = store.install(packageBytes(manifest = manifest(version = "1.2.9")))

        fun entry(version: String) = com.music.bitchord.data.sources.mornye.SflxRegistryEntry(
            id = "qobuz-web", displayName = "Qobuz", version = version, description = "",
            downloadUrl = "https://example/q.sflx", sha256 = "ab", category = "download",
            tags = emptyList(), minAppVersion = "", iconUrl = "", runtime = "javascript",
        )

        assertTrue(SflxRegistry.isUpdate(entry("1.2.23"), installed))
        assertFalse(SflxRegistry.isUpdate(entry("1.2.9"), installed))
        assertFalse(SflxRegistry.isUpdate(entry("1.2.8"), installed))
    }
}
