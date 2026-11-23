package com.music.bitchord.data.sources.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Why a backup could not be opened.
 *
 * Each reason has its own message on screen, so the reasons have to be told
 * apart here: a wrong password asks for another try, while a damaged file
 * needs a fresh export. A wrong password and a tampered file both fail the
 * GCM tag, so they could not be separated by that alone. The header carries a
 * password check for that (see [AddonBackupCrypto]).
 */
enum class AddonBackupFailure {
    /** Not one of ours: no magic, or too short to hold a header. */
    NOT_A_BACKUP,

    /** Written by a newer build, with a format or schema this one cannot read. */
    NEWER_VERSION,

    /** The password does not open this file. */
    WRONG_PASSWORD,

    /** The password is right, but the contents changed after export or were cut short. */
    DAMAGED,

    /** Bigger than any real backup, so it is refused before anything is decrypted or unzipped. */
    TOO_LARGE,
}

/**
 * A backup that could not be read, with a [reason] the screen can word.
 *
 * The message is only the reason's name. Nothing from the file and nothing the
 * user typed goes into it, so it is safe to log.
 */
class AddonBackupException(val reason: AddonBackupFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)

/**
 * The encrypted envelope around an add-ons backup.
 *
 * ```
 * offset  size  field
 *      0     8  magic "AURISADD"
 *      8     1  format version (1)
 *      9     1  KDF id (1 = PBKDF2-HMAC-SHA256)
 *     10     4  iterations, big-endian
 *     14    16  salt, random per file
 *     30    12  GCM nonce, random per file
 *     42    16  password check
 *     58     …  AES-256-GCM ciphertext plus 16-byte tag
 * ```
 *
 * PBKDF2 derives 64 bytes from the password. The first 32 are the AES key and
 * the last 32 key an HMAC over the header's first 42 bytes. The first 16 bytes
 * of that HMAC are the password check. A check that does not match means a
 * wrong password. A check that matches with a failed tag means the
 * ciphertext changed. The whole 58-byte header is GCM associated data, so
 * editing any byte of it fails one test or the other.
 *
 * The check lets nobody test a password any faster than the tag already does:
 * both cost one full PBKDF2 run per guess.
 *
 * Iterations are read from the header rather than fixed, so a later build can
 * raise [DEFAULT_ITERATIONS] and still open files written by this one.
 *
 * No dependencies beyond the JCA. Android has had `PBKDF2WithHmacSHA256` since
 * API 26, which is this app's minSdk, and it runs unchanged in unit tests.
 */
object AddonBackupCrypto {

    const val MAGIC = "AURISADD"
    const val FORMAT_VERSION: Int = 1
    const val KDF_PBKDF2_SHA256: Int = 1

    /**
     * OWASP's current figure for PBKDF2-HMAC-SHA256. On a mid-range phone this
     * is roughly half a second to a second, paid once per export or import and
     * always off the main thread.
     */
    const val DEFAULT_ITERATIONS = 600_000

    /** A higher count than this is refused, so a crafted header can't make the app derive for minutes. */
    private const val MAX_ITERATIONS = 10_000_000

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val CHECK_BYTES = 16
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /** Up to the end of the nonce: what the password check is computed over. */
    private const val PREFIX_BYTES = 8 + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES
    const val HEADER_BYTES = PREFIX_BYTES + CHECK_BYTES

    private val CHECK_LABEL = "auris-addons-password-check".toByteArray(Charsets.US_ASCII)

    /**
     * Encrypts [plain] under [password].
     *
     * The caller owns [password] and should clear it after this returns. This
     * function makes no copy that outlives the call, apart from the one inside
     * [PBEKeySpec], which it clears.
     */
    fun seal(
        plain: ByteArray,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val prefix = ByteBuffer.allocate(PREFIX_BYTES)
            .put(MAGIC.toByteArray(Charsets.US_ASCII))
            .put(FORMAT_VERSION.toByte())
            .put(KDF_PBKDF2_SHA256.toByte())
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()
        val keys = derive(password, salt, iterations)
        try {
            val header = prefix + check(keys, prefix)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(keys), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            keys.fill(0)
        }
    }

    /**
     * Decrypts a file written by [seal].
     *
     * @throws AddonBackupException with the reason it could not be opened.
     */
    fun open(sealed: ByteArray, password: CharArray): ByteArray {
        val header = readHeader(sealed)
        val keys = derive(password, header.salt, header.iterations)
        try {
            val prefix = sealed.copyOfRange(0, PREFIX_BYTES)
            val expected = check(keys, prefix)
            val stored = sealed.copyOfRange(PREFIX_BYTES, HEADER_BYTES)
            if (!MessageDigest.isEqual(expected, stored)) {
                throw AddonBackupException(AddonBackupFailure.WRONG_PASSWORD)
            }
            if (sealed.size < HEADER_BYTES + TAG_BITS / 8) {
                throw AddonBackupException(AddonBackupFailure.DAMAGED)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey(keys), GCMParameterSpec(TAG_BITS, header.nonce))
            cipher.updateAAD(sealed, 0, HEADER_BYTES)
            return try {
                cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
            } catch (e: AEADBadTagException) {
                throw AddonBackupException(AddonBackupFailure.DAMAGED, e)
            } catch (e: GeneralSecurityException) {
                throw AddonBackupException(AddonBackupFailure.DAMAGED, e)
            }
        } finally {
            keys.fill(0)
        }
    }

    /** Whether [bytes] starts like one of these files: for a cheap check before asking for a password. */
    fun looksLikeBackup(bytes: ByteArray): Boolean = runCatching { readHeader(bytes) }.isSuccess

    private class Header(val iterations: Int, val salt: ByteArray, val nonce: ByteArray)

    private fun readHeader(bytes: ByteArray): Header {
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw AddonBackupException(AddonBackupFailure.NOT_A_BACKUP)
        }
        if (bytes.size < HEADER_BYTES) throw AddonBackupException(AddonBackupFailure.DAMAGED)
        val buffer = ByteBuffer.wrap(bytes, magic.size, HEADER_BYTES - magic.size)
        val version = buffer.get().toInt() and 0xFF
        val kdf = buffer.get().toInt() and 0xFF
        if (version > FORMAT_VERSION || kdf != KDF_PBKDF2_SHA256) {
            throw AddonBackupException(AddonBackupFailure.NEWER_VERSION)
        }
        if (version < 1) throw AddonBackupException(AddonBackupFailure.NOT_A_BACKUP)
        val iterations = buffer.int
        if (iterations !in 1..MAX_ITERATIONS) throw AddonBackupException(AddonBackupFailure.DAMAGED)
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
        return Header(iterations, salt, nonce)
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 2 * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aesKey(keys: ByteArray) = SecretKeySpec(keys, 0, KEY_BYTES, "AES")

    private fun check(keys: ByteArray, prefix: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys, KEY_BYTES, KEY_BYTES, "HmacSHA256"))
        mac.update(CHECK_LABEL)
        return mac.doFinal(prefix).copyOf(CHECK_BYTES)
    }
}
