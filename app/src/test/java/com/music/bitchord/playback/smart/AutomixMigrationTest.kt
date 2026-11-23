package com.music.bitchord.playback.smart

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The first launch of a build with BitChord's Automix engine: the old
 * engine's on-disk state goes, the settings both engines share stay.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AutomixMigrationTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("automix_migration_test", Context.MODE_PRIVATE)
    private lateinit var filesDir: File
    private val deferred = mutableListOf<File>()

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        filesDir = Files.createTempDirectory("automix-migration").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun run(): Boolean = AutomixMigration.run(prefs, filesDir, deleteLater = { deferred += it })

    /**
     * A run whose renames all fail. Injected rather than staged with a
     * read-only directory, which a privileged test process can still rename
     * inside.
     */
    private fun runWithoutRenames(): Boolean =
        AutomixMigration.run(prefs, filesDir, deleteLater = { deferred += it }, rename = { _, _ -> false })

    private fun oldInstall() {
        File(filesDir, AutomixMigration.RETIRED_ANALYSIS_DIRECTORY).apply { mkdirs() }.resolve("123_11.json").writeText("{\"version\":1}")
        File(filesDir, AutomixMigration.RETIRED_PROFILE_DIRECTORY).apply { mkdirs() }.resolve("p.json").writeText("{}")
        prefs.edit()
            .putString(AutomixMigration.KEY_RETIRED_ENGINE, "V2")
            .putInt("crossfade_seconds", 7)
            .putBoolean("smart_fade_enabled", true)
            .putString("automix_performance_mode", "PERFORMANCE")
            .putBoolean("smart_version_alignment", false)
            .commit()
    }

    @Test
    fun theOldEnginesStoresAreMovedOutAndHandedOffForDeletion() {
        oldInstall()
        assertTrue(run())
        assertFalse(File(filesDir, AutomixMigration.RETIRED_ANALYSIS_DIRECTORY).exists())
        assertFalse(File(filesDir, AutomixMigration.RETIRED_PROFILE_DIRECTORY).exists())
        assertEquals(2, deferred.size)
        // What was handed off still holds the old files until the deleter runs.
        assertTrue(deferred.all { it.exists() && it.name.contains(".retired-") })
        val analysis = deferred.single { it.name.startsWith("${AutomixMigration.RETIRED_ANALYSIS_DIRECTORY}.retired-") }
        val profiles = deferred.single { it.name.startsWith("${AutomixMigration.RETIRED_PROFILE_DIRECTORY}.retired-") }
        assertTrue(File(analysis, "123_11.json").isFile)
        assertTrue(File(profiles, "p.json").isFile)
        deferred.forEach { it.deleteRecursively() }
        assertEquals(emptyList<String>(), filesDir.list()!!.toList())
    }

    @Test
    fun theEngineSwitchGoesAndTheSharedSettingsStay() {
        oldInstall()
        run()
        assertFalse(prefs.contains(AutomixMigration.KEY_RETIRED_ENGINE))
        assertEquals(7, prefs.getInt("crossfade_seconds", 0))
        assertTrue(prefs.getBoolean("smart_fade_enabled", false))
        assertEquals("PERFORMANCE", prefs.getString("automix_performance_mode", null))
        assertFalse(prefs.getBoolean("smart_version_alignment", true))
    }

    @Test
    fun itRunsOnceAndLeavesTheNewEnginesStoreAlone() {
        oldInstall()
        assertTrue(run())
        // The new engine writes its own store under the same name afterwards.
        val fresh = File(filesDir, AutomixMigration.RETIRED_ANALYSIS_DIRECTORY).apply { mkdirs() }.resolve("456_11.json")
        fresh.writeText("{\"version\":3}")
        assertFalse(run())
        assertTrue(fresh.exists())
    }

    @Test
    fun aRetirementThatDoesNotFinishNeverReachesTheNewStore() {
        val fresh = File(AutomixMigration.analysisHome(filesDir), "smart_analysis")
            .apply { mkdirs() }.resolve("456_11.json")
        fresh.writeText("{\"version\":3}")
        oldInstall()
        // The old directories cannot be renamed: they are emptied in place, later.
        assertFalse(runWithoutRenames())
        assertTrue(deferred.any { it.name == AutomixMigration.RETIRED_ANALYSIS_DIRECTORY })
        deferred.forEach { it.deleteRecursively() }
        deferred.clear()
        // The marker never landed; the next launch retires again.
        assertTrue(run())
        assertTrue(fresh.exists())
        assertFalse(AutomixMigration.RETIRED_DIRECTORIES.any { AutomixMigration.analysisHome(filesDir).path.endsWith(it) })
    }

    @Test
    fun aFreshInstallHasNothingToMoveAndIsMarkedDone() {
        assertTrue(run())
        assertTrue(deferred.isEmpty())
        assertTrue(prefs.getBoolean(AutomixMigration.KEY_DONE, false))
    }

    @Test
    fun aRetiredDirectoryLeftByAKilledProcessIsDeletedOnTheNextLaunch() {
        oldInstall()
        assertTrue(run())
        // The process died before the deleter got to them.
        val leftover = deferred.toList()
        deferred.clear()
        val fresh = File(filesDir, AutomixMigration.RETIRED_ANALYSIS_DIRECTORY).apply { mkdirs() }.resolve("456_11.json")
        fresh.writeText("{\"version\":3}")

        assertFalse(run())

        assertEquals(leftover.map { it.name }.sorted(), deferred.map { it.name }.sorted())
        assertTrue(fresh.exists())
    }

    @Test
    fun anOldDirectoryThatWillNotGoLeavesTheMoveToRunAgain() {
        oldInstall()
        // A directory that can be neither renamed nor emptied: the rename
        // fails, and the deferred delete has not run yet.
        assertFalse(runWithoutRenames())
        assertFalse(prefs.getBoolean(AutomixMigration.KEY_DONE, false))
        assertTrue(run())
        assertTrue(prefs.getBoolean(AutomixMigration.KEY_DONE, false))
    }
}
