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

    @Test
    fun aLoadedEngineKeepsPersistingAfterAnImportThatDidNotReplaceItsPackage() {
        val (host, installed) = host()
        // What the engine was loaded with, before any import.
        val engineCall = host.sessionCallFor(installed)!!

        // Session records are restored; the package itself is left alone.
        val imported = """{"install_id":"imported","session_id":"s","session_secret":"x"}"""
        host.replaceSessionRecords { recordFile(installed).writeText(imported) }
        assertEquals(imported, recordFile(installed).readText())

        // The engine's next call reaches a current client, so its writes persist again.
        engineCall("clear", "[]")
        org.junit.Assert.assertNotEquals(imported, recordFile(installed).readText())
    }

    @Test
    fun noClientLeavesTheMapUnretiredWhileClientsAreCreatedDuringADrop() {
        val (host, installed) = host()
        val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<SflxSignedSession, Boolean>())
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        // Callers keep asking for a client, as engines and the UI do, while the host drops them.
        val callers = (1..4).map {
            kotlin.concurrent.thread {
                while (running.get()) host.sessionFor(installed)?.let(seen::add)
            }
        }
        repeat(500) { host.reloadSessions() }
        running.set(false)
        callers.forEach { it.join(5_000) }

        val current = host.sessionFor(installed)
        val orphans = seen.filter { it !== current && !it.isRetired }
        assertEquals("every client that left the map was retired", emptyList<SflxSignedSession>(), orphans)
        org.junit.Assert.assertTrue("the stress actually produced several clients", seen.size > 1)
    }
}
