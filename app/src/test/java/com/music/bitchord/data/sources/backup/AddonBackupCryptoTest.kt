package com.music.bitchord.data.sources.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AddonBackupCryptoTest {

    // Low on purpose: the count is read from the header, so tests don't pay for 600k rounds.
    private val iterations = 1_000
    private val plain = "addons and their keys".toByteArray()

    private fun seal(password: String = "correct horse", body: ByteArray = plain, rounds: Int = iterations) =
        AddonBackupCrypto.seal(body, password.toCharArray(), rounds)

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
    fun roundTrip() {
        assertArrayEquals(plain, AddonBackupCrypto.open(seal(), "correct horse".toCharArray()))
    }

    @Test
    fun roundTripOfEmptyAndLargeBodies() {
        assertArrayEquals(ByteArray(0), AddonBackupCrypto.open(seal(body = ByteArray(0)), "correct horse".toCharArray()))
        val large = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        assertArrayEquals(large, AddonBackupCrypto.open(seal(body = large), "correct horse".toCharArray()))
    }

    @Test
    fun wrongPasswordIsReportedAsSuch() {
        assertEquals(
            AddonBackupFailure.WRONG_PASSWORD,
            failure { AddonBackupCrypto.open(seal(), "battery staple".toCharArray()) },
        )
    }

    @Test
    fun tamperedCiphertextIsDamagedNotWrongPassword() {
        val sealed = seal()
        sealed[AddonBackupCrypto.HEADER_BYTES + 3] = (sealed[AddonBackupCrypto.HEADER_BYTES + 3].toInt() xor 1).toByte()
        assertEquals(AddonBackupFailure.DAMAGED, failure { AddonBackupCrypto.open(sealed, "correct horse".toCharArray()) })
    }

    @Test
    fun tamperedTagIsDamaged() {
        val sealed = seal()
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex].toInt() xor 0x80).toByte()
        assertEquals(AddonBackupFailure.DAMAGED, failure { AddonBackupCrypto.open(sealed, "correct horse".toCharArray()) })
    }

    @Test
    fun tamperedHeaderNeverOpens() {
        // Salt, nonce, iteration count and the check itself: each edit must fail one test or the other.
        listOf(10, 13, 14, 29, 30, 41, 42, 57).forEach { offset ->
            val sealed = seal()
            sealed[offset] = (sealed[offset].toInt() xor 1).toByte()
            val reason = failure { AddonBackupCrypto.open(sealed, "correct horse".toCharArray()) }
            assertTrue(
                "offset $offset gave $reason",
                reason == AddonBackupFailure.WRONG_PASSWORD || reason == AddonBackupFailure.DAMAGED,
            )
        }
    }

    @Test
    fun truncatedFileIsDamaged() {
        val sealed = seal()
        assertEquals(
            AddonBackupFailure.DAMAGED,
            failure { AddonBackupCrypto.open(sealed.copyOf(sealed.size - 5), "correct horse".toCharArray()) },
        )
        assertEquals(
            AddonBackupFailure.DAMAGED,
            failure { AddonBackupCrypto.open(sealed.copyOf(20), "correct horse".toCharArray()) },
        )
    }

    @Test
    fun foreignFileIsNotABackup() {
        assertEquals(
            AddonBackupFailure.NOT_A_BACKUP,
            failure { AddonBackupCrypto.open("PK\u0003\u0004 a zip".toByteArray(), "x".toCharArray()) },
        )
        assertFalse(AddonBackupCrypto.looksLikeBackup("{}".toByteArray()))
        assertTrue(AddonBackupCrypto.looksLikeBackup(seal()))
    }

    @Test
    fun newerFormatVersionIsRefusedAsNewer() {
        val sealed = seal()
        sealed[8] = (AddonBackupCrypto.FORMAT_VERSION + 1).toByte()
        assertEquals(AddonBackupFailure.NEWER_VERSION, failure { AddonBackupCrypto.open(sealed, "correct horse".toCharArray()) })
    }

    @Test
    fun unknownKdfIsRefusedAsNewer() {
        val sealed = seal()
        sealed[9] = 2
        assertEquals(AddonBackupFailure.NEWER_VERSION, failure { AddonBackupCrypto.open(sealed, "correct horse".toCharArray()) })
    }

    @Test
    fun filesWithAnyIterationCountOpen() {
        // A later build that raises the default still opens files written with the old count, and the reverse.
        listOf(1_000, 5_000, 20_000).forEach { rounds ->
            assertArrayEquals(plain, AddonBackupCrypto.open(seal(rounds = rounds), "correct horse".toCharArray()))
        }
    }

    @Test
    fun saltAndNonceAreFreshEachTime() {
        val first = seal()
        val second = seal()
        assertNotEquals(first.copyOfRange(14, 42).toList(), second.copyOfRange(14, 42).toList())
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun unicodePasswordsWork() {
        val sealed = seal(password = "pässwörd 🎵 पासवर्ड")
        assertArrayEquals(plain, AddonBackupCrypto.open(sealed, "pässwörd 🎵 पासवर्ड".toCharArray()))
        assertEquals(
            AddonBackupFailure.WRONG_PASSWORD,
            failure { AddonBackupCrypto.open(sealed, "passwort 🎵 पासवर्ड".toCharArray()) },
        )
    }

    @Test
    fun failureMessageCarriesNoSecret() {
        val password = "hunter2-very-secret"
        try {
            AddonBackupCrypto.open(seal(), password.toCharArray())
            fail()
        } catch (e: AddonBackupException) {
            assertFalse(e.toString().contains(password))
            assertEquals("WRONG_PASSWORD", e.message)
        }
    }
}
