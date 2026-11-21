package com.music.bitchord.data.sources.mornye

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A loaded engine keeps its session client after the host's map forgets it.
 * That retained client must still be unable to write over a record a later
 * import restores.
 */
class SflxHostSessionReplaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val manifest = JSONObject()
        .put("name", "demo")
        .put("displayName", "Demo")
        .put("version", "1.0.0")
        .put("type", org.json.JSONArray().put("download_provider"))
        .put("signedSession", JSONObject().put("namespace", "demo").put("baseUrl", "https://gateway.test/v2/"))
        .toString()

    private fun host(): Pair<SflxHost, InstalledSflx> {
        val store = SflxStore(File(temp.root, "store"))
        val installed = store.restore(
            "demo",
            mapOf(SflxStore.MANIFEST_FILE to manifest.toByteArray(), SflxStore.SCRIPT_FILE to "//".toByteArray()),
        )
        val sessions = File(temp.root, "sessions").apply { mkdirs() }
        return SflxHost(store, "3.4", sessions) to installed
    }

    private fun recordFile(installed: InstalledSflx): File =
        File(temp.root, "sessions/" + SignedSessionConfig.from(installed.manifest)!!.fileName())

    @Test
    fun aClientRetainedAcrossAMapClearCannotOverwriteALaterImport() {
        val (host, installed) = host()
        // What a loaded engine holds on to through its sessionCall.
        val retained = host.sessionFor(installed)!!

        // An import with no session records still reloads sessions, clearing the map.
        host.reloadSessions()

        // A later import replaces the record on disk.
        val imported = """{"install_id":"imported","session_id":"s","session_secret":"x"}"""
        host.replaceSessionRecords { recordFile(installed).writeText(imported) }

        // The engine's old client saving now must not put its record back.
        retained.clear()
        assertEquals(imported, recordFile(installed).readText())

        // And the next call gets a fresh client, which can save again.
        val fresh = host.sessionFor(installed)!!
        assertNotSame(retained, fresh)
        fresh.clear()
        org.junit.Assert.assertNotEquals(imported, recordFile(installed).readText())
    }
}
