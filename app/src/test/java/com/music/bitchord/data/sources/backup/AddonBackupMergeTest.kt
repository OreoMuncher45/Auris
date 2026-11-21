package com.music.bitchord.data.sources.backup

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonBackupMergeTest {

    private val script = "x".toByteArray()
    private fun files() = mapOf("manifest.json" to "{}".toByteArray(), "index.js" to script)

    private fun contents(
        sources: List<BackedUpSource> = emptyList(),
        extensions: List<BackedUpExtension> = emptyList(),
        sessions: Set<String> = emptySet(),
    ) = AddonBackupContents(
        index = AddonBackupIndex(sources = sources, extensions = extensions),
        extensionFiles = extensions.associate { it.id to files() },
        sessionFiles = sessions.associateWith { "{}".toByteArray() },
    )

    private val youtube = SourceConfig(kind = SourceKind.YOUTUBE, stock = true)
    private val existingAddon = SourceConfig(
        id = "kept-id",
        kind = SourceKind.ADDON,
        label = "Old name",
        baseUrl = "https://addon.test",
        settings = mapOf("apiKey" to "old"),
    )

    @Test
    fun newAddonIsAddedWithItsSettings() {
        val plan = AddonBackupMerge.plan(
            contents(sources = listOf(BackedUpSource("ADDON", "New", "https://new.test", false, mapOf("apiKey" to "k")))),
            current = listOf(youtube),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.SKIP_EXISTING,
        )
        val added = plan.addSources.single()
        assertEquals(SourceKind.ADDON, added.kind)
        assertEquals("https://new.test", added.baseUrl)
        assertEquals("k", added.settings["apiKey"])
        assertFalse(added.enabled)
        assertFalse(added.stock)
        assertEquals(1, plan.imported)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun sameAddonByNormalisedAddressIsSkippedByDefault() {
        // The manifest URL and the bare root address the same server.
        val plan = AddonBackupMerge.plan(
            contents(sources = listOf(BackedUpSource("ADDON", "New name", "https://addon.test/manifest.json", settings = mapOf("apiKey" to "new")))),
            current = listOf(existingAddon, youtube),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.SKIP_EXISTING,
        )
        assertTrue(plan.addSources.isEmpty())
        assertTrue(plan.replaceSources.isEmpty())
        assertEquals(1, plan.skipped)
    }

    @Test
    fun replaceOverwritesButKeepsIdAndStock() {
        val plan = AddonBackupMerge.plan(
            contents(sources = listOf(BackedUpSource("ADDON", "New name", "https://ADDON.test/", settings = mapOf("apiKey" to "new")))),
            current = listOf(existingAddon.copy(stock = true), youtube),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.REPLACE_EXISTING,
        )
        val replaced = plan.replaceSources.single()
        assertEquals("kept-id", replaced.id)
        assertTrue(replaced.stock)
        assertEquals("New name", replaced.label)
        assertEquals("new", replaced.settings["apiKey"])
        assertEquals(0, plan.skipped)
    }

    @Test
    fun builtInExtensionRowsAndUnknownKindsAreNeverRestoredAsSources() {
        val plan = AddonBackupMerge.plan(
            contents(
                sources = listOf(
                    BackedUpSource("YOUTUBE", baseUrl = "x"),
                    BackedUpSource("MODULE", baseUrl = "sflx://demo"),
                    BackedUpSource("SOMETHING_NEW", baseUrl = "https://x.test"),
                    BackedUpSource("ADDON", baseUrl = "   "),
                    BackedUpSource("CUSTOM_MODULE", baseUrl = "https://index.test/modules.json"),
                ),
            ),
            current = listOf(youtube),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.SKIP_EXISTING,
        )
        assertEquals(listOf(SourceKind.CUSTOM_MODULE), plan.addSources.map { it.kind })
    }

    @Test
    fun duplicateRowsInTheFileAreAddedOnce() {
        val plan = AddonBackupMerge.plan(
            contents(sources = listOf(BackedUpSource("ADDON", baseUrl = "https://a.test"), BackedUpSource("ADDON", baseUrl = "https://a.test/manifest.json"))),
            current = emptyList(),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.SKIP_EXISTING,
        )
        assertEquals(1, plan.addSources.size)
    }

    @Test
    fun extensionsFollowTheModeAndCarryTheirSwitch() {
        val file = contents(
            extensions = listOf(
                BackedUpExtension("fresh", enabled = false),
                BackedUpExtension("installed", enabled = true),
                BackedUpExtension("lyricsonly", enabled = null),
            ),
        )
        val skip = AddonBackupMerge.plan(file, emptyList(), setOf("installed"), emptySet(), AddonMergeMode.SKIP_EXISTING)
        assertEquals(listOf("fresh", "lyricsonly"), skip.extensions)
        assertEquals(mapOf("fresh" to false), skip.extensionEnabled)
        assertEquals(1, skip.skipped)

        val replace = AddonBackupMerge.plan(file, emptyList(), setOf("installed"), emptySet(), AddonMergeMode.REPLACE_EXISTING)
        assertEquals(listOf("fresh", "installed", "lyricsonly"), replace.extensions)
        assertEquals(mapOf("fresh" to false, "installed" to true), replace.extensionEnabled)
    }

    @Test
    fun sessionsAreOnlyOverwrittenWhenReplacing() {
        val file = contents(sessions = setOf("a.json", "b.json"))
        val skip = AddonBackupMerge.plan(file, emptyList(), emptySet(), setOf("a.json"), AddonMergeMode.SKIP_EXISTING)
        assertEquals(listOf("b.json"), skip.sessions)
        val replace = AddonBackupMerge.plan(file, emptyList(), emptySet(), setOf("a.json"), AddonMergeMode.REPLACE_EXISTING)
        assertEquals(setOf("a.json", "b.json"), replace.sessions.toSet())
    }

    @Test
    fun addressPathsStayCaseSensitiveWhileHostAndSchemeDoNot() {
        val module = SourceConfig(kind = SourceKind.CUSTOM_MODULE, baseUrl = "https://modules.test/Module.json")
        val plan = AddonBackupMerge.plan(
            contents(
                sources = listOf(
                    BackedUpSource("CUSTOM_MODULE", baseUrl = "HTTPS://MODULES.test/Module.json"),
                    BackedUpSource("CUSTOM_MODULE", baseUrl = "https://modules.test/module.json"),
                ),
            ),
            current = listOf(module),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.SKIP_EXISTING,
        )
        assertEquals(1, plan.skipped)
        assertEquals(listOf("https://modules.test/module.json"), plan.addSources.map { it.baseUrl })
    }

    @Test
    fun previewMarksWhatIsAlreadyHere() {
        val items = AddonBackupMerge.preview(
            contents(
                sources = listOf(
                    BackedUpSource("ADDON", label = "", baseUrl = "https://addon.test"),
                    BackedUpSource("ADDON", label = "Second", baseUrl = "https://second.test"),
                ),
                extensions = listOf(BackedUpExtension("demo", displayName = "Demo")),
            ),
            current = listOf(existingAddon),
            installedExtensions = setOf("demo"),
        )
        assertEquals(listOf("addon.test", "Second", "Demo"), items.map { it.name })
        assertEquals(listOf(true, false, true), items.map { it.installed })
        assertEquals(
            listOf(AddonPreviewItem.Kind.ADDON, AddonPreviewItem.Kind.ADDON, AddonPreviewItem.Kind.EXTENSION),
            items.map { it.kind },
        )
    }

    @Test
    fun backedUpIsTheInverseOfRestore() {
        val config = existingAddon.copy(enabled = false, allowDownloads = false, checkValidLossless = true)
        val plan = AddonBackupMerge.plan(
            contents(sources = listOf(AddonBackupMerge.backedUp(config))),
            current = emptyList(),
            installedExtensions = emptySet(),
            existingSessions = emptySet(),
            mode = AddonMergeMode.SKIP_EXISTING,
        )
        val restored = plan.addSources.single()
        assertEquals(config.copy(id = restored.id), restored)
    }
}
