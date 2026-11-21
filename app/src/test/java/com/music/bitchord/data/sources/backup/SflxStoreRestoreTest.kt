package com.music.bitchord.data.sources.backup

import com.music.bitchord.data.sources.mornye.SflxException
import com.music.bitchord.data.sources.mornye.SflxStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SflxStoreRestoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest(id: String) = """{"name":"$id","displayName":"Demo","version":"1.0.0","type":["metadata_provider"]}"""

    private fun files(id: String = "demo") = mapOf(
        SflxStore.MANIFEST_FILE to manifest(id).toByteArray(),
        SflxStore.SCRIPT_FILE to "export default {}".toByteArray(),
        SflxStore.SETTINGS_FILE to """{"region":"x"}""".toByteArray(),
    )

    @Test
    fun restoreThenExportRoundTrips() {
        val store = SflxStore(temp.root)
        val installed = store.restore("demo", files())
        assertEquals("demo", installed.id)
        assertEquals("x", store.settings("demo")!!["region"].toString().trim('"'))
        // The cache never travels.
        File(installed.directory, "cache").apply { mkdirs() }.resolve("track.flac").writeText("decrypted")
        val exported = store.exportFiles("demo")!!
        assertEquals(setOf(SflxStore.MANIFEST_FILE, SflxStore.SCRIPT_FILE, SflxStore.SETTINGS_FILE), exported.keys)
        assertArrayEquals(files()[SflxStore.SCRIPT_FILE], exported[SflxStore.SCRIPT_FILE])
    }

    @Test
    fun restoreReplacesAnInstalledCopyWhole() {
        val store = SflxStore(temp.root)
        store.restore("demo", files() + (SflxStore.ICON_FILE to byteArrayOf(9)))
        store.restore("demo", files())
        assertNull(store.find("demo")!!.iconFile)
        assertFalse(File(temp.root, "demo.restoring").exists())
    }

    @Test(expected = SflxException::class)
    fun manifestNamingAnotherIdIsRefused() {
        SflxStore(temp.root).restore("demo", files(id = "other"))
    }

    @Test
    fun unsafeIdsAreRefusedAndWriteNothing() {
        val store = SflxStore(temp.root)
        listOf("../evil", ".hidden", "a/b", "").forEach { id ->
            try {
                store.restore(id, files(id))
                throw AssertionError("restored $id")
            } catch (_: SflxException) {
            }
        }
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun exportOfMissingPackageIsNull() {
        assertNull(SflxStore(temp.root).exportFiles("nothing"))
    }
}
