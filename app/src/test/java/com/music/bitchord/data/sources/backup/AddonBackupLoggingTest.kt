package com.music.bitchord.data.sources.backup

import com.music.bitchord.data.DebugLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Nothing secret reaches the log: not the password, a key typed into an
 * addon's settings, an address, or a session record. Runs the real unlock path
 * with a capturing sink installed.
 */
class AddonBackupLoggingTest {

    private val lines = mutableListOf<String>()
    private var previous: DebugLog.Sink? = null

    private val password = "pw-SECRET-4242"
    private val apiKey = "KEY-SECRET-9999"
    private val address = "https://private-addon.SECRET.test"
    private val sessionSecret = "SESSION-SECRET-7777"

    @Before
    fun capture() {
        previous = DebugLog.sink
        DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
            synchronized(lines) { lines += "$level/$tag: $message ${error?.toString().orEmpty()} ${error?.cause?.toString().orEmpty()}" }
        }
    }

    @After
    fun restore() {
        DebugLog.sink = previous
    }

    private fun sealed(): ByteArray = AddonBackupCrypto.seal(
        AddonBackupArchive.write(
            AddonBackupContents(
                index = AddonBackupIndex(
                    sources = listOf(BackedUpSource("ADDON", "Private", address, settings = mapOf("apiKey" to apiKey))),
                ),
                sessionFiles = mapOf("ns-0011223344556677.json" to """{"session_secret":"$sessionSecret"}""".toByteArray()),
            ),
        ),
        password.toCharArray(),
        1_000,
    )

    private fun assertNoSecrets() {
        val all = synchronized(lines) { lines.joinToString("\n") }
        listOf(password, apiKey, address, sessionSecret, "SECRET").forEach {
            assertFalse("log contains a secret:\n$all", all.contains(it))
        }
    }

    @Test
    fun wrongPasswordLogsTheReasonOnly() = runBlocking {
        val result = AddonBackup.unlock(sealed(), "not-the-password".toCharArray())
        assertEquals(AddonBackupFailure.WRONG_PASSWORD, AddonBackup.reasonOf(result.exceptionOrNull()!!))
        assertTrue(lines.any { it.contains("WRONG_PASSWORD") })
        assertNoSecrets()
    }

    @Test
    fun damagedFileLogsTheReasonOnly() = runBlocking {
        val bytes = sealed()
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 1).toByte()
        val result = AddonBackup.unlock(bytes, password.toCharArray())
        assertEquals(AddonBackupFailure.DAMAGED, AddonBackup.reasonOf(result.exceptionOrNull()!!))
        assertNoSecrets()
    }

    @Test
    fun successfulUnlockLogsNothingSecretAndClearsThePassword() = runBlocking {
        val typed = password.toCharArray()
        val unlocked = AddonBackup.unlock(sealed(), typed).getOrThrow()
        assertTrue("the caller's password array is wiped", typed.all { it == '\u0000' })
        assertEquals(apiKey, unlocked.contents.index.sources.single().settings["apiKey"])
        assertFalse(unlocked.toString().contains(apiKey))
        assertNoSecrets()
    }
}
