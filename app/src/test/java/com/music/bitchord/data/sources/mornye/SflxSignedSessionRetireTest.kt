package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A client whose record an add-ons import replaced must never write its old
 * record back over the imported one.
 */
class SflxSignedSessionRetireTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = SignedSessionConfig(
        namespace = "test",
        baseUrl = "https://gateway.test/v2/",
        appVersion = "ext-1.0",
        platform = "extension",
        callbackUrl = "mornye://session-grant",
        schemeLabel = "TEST-HMAC-V1",
        headerPrefix = "X-Test-",
        timeWindowSeconds = 300,
        bootstrap = "/bootstrap",
        challenge = "/challenge",
        exchange = "/session/exchange",
        refresh = "",
    )

    private val imported = """{"install_id":"imported","session_id":"s","session_secret":"x","expires_at":"2099-01-01T00:00:00Z"}"""

    @Test
    fun aLiveClientStillSaves() {
        val session = SflxSignedSession("demo", config, temp.root)
        val record = File(temp.root, config.fileName())
        record.writeText(imported)
        session.clear()
        assertNotEquals(imported, record.readText())
    }

    @Test
    fun aRetiredClientNeverWritesOverTheImportedRecord() {
        val session = SflxSignedSession("demo", config, temp.root)
        val record = File(temp.root, config.fileName())
        session.retire()
        record.writeText(imported)
        session.clear()
        assertEquals(imported, record.readText())
    }
}
