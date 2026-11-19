package com.music.bitchord.playback.cenc

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CencDecryptorTest {

    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val plain = ByteArray(200) { (it * 31 + 5).toByte() }

    @Test
    fun `cenc subsamples share one keystream`() {
        val iv8 = ByteArray(8) { (0x40 + it).toByte() }
        val clear = intArrayOf(10, 5)
        val encrypted = intArrayOf(90, 95)
        // Encrypt the way a packager does: the encrypted ranges as one CTR stream.
        val ranges = plain.copyOfRange(10, 100) + plain.copyOfRange(105, 200)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv8.copyOf(16)))
        val sealed = cipher.doFinal(ranges)
        val sample = plain.copyOf()
        System.arraycopy(sealed, 0, sample, 10, 90)
        System.arraycopy(sealed, 90, sample, 105, 95)

        CencDecryptor.decrypt(sample, 0, sample.size, C.CRYPTO_MODE_AES_CTR, key, iv8, clear, encrypted)
        assertArrayEquals(plain, sample)
    }

    @Test
    fun `cbcs restarts the chain per subsample and follows the pattern`() {
        val iv = ByteArray(16) { (0x10 + it).toByte() }
        for ((crypt, skip) in listOf(1 to 9, 0 to 0, 2 to 1)) {
            val clear = intArrayOf(3, 7)
            val encrypted = intArrayOf(100, 90) // partial trailing blocks stay clear
            val sample = plain.copyOf()
            var at = 0
            for (i in clear.indices) {
                at += clear[i]
                val blocks = encrypted[i] / 16
                val c = if (crypt == 0 && skip == 0) 1 else crypt
                val s = if (crypt == 0 && skip == 0) 0 else skip
                val positions = ArrayList<Int>()
                var b = 0
                while (b < blocks) {
                    var n = 0
                    while (n < c && b < blocks) { positions += at + b * 16; b++; n++ }
                    b += s
                }
                val gathered = ByteArray(positions.size * 16)
                positions.forEachIndexed { k, p -> System.arraycopy(sample, p, gathered, k * 16, 16) }
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val sealed = cipher.doFinal(gathered)
                positions.forEachIndexed { k, p -> System.arraycopy(sealed, k * 16, sample, p, 16) }
                at += encrypted[i]
            }
            CencDecryptor.decrypt(sample, 0, sample.size, C.CRYPTO_MODE_AES_CBC, key, iv, clear, encrypted, crypt, skip)
            assertArrayEquals("pattern $crypt:$skip", plain, sample)
        }
    }

    @Test
    fun `keys are read in the spellings extensions use`() {
        val bytes = ByteArray(16) { it.toByte() }
        val hex = "000102030405060708090a0b0c0d0e0f"
        assertArrayEquals(bytes, CencKeys.parseKey(hex))
        assertArrayEquals(bytes, CencKeys.parseKey("0x" + hex.uppercase()))
        assertArrayEquals(bytes, CencKeys.parseKey(Base64.getEncoder().encodeToString(bytes)))
        assertNull(CencKeys.parseKey(""))
        assertNull(CencKeys.parseKey("not a key"))
    }

    @Test
    fun `keys survive a restart`() {
        val dir = Files.createTempDirectory("cenc").toFile()
        try {
            CencKeys.clear()
            CencKeys.init(dir)
            val kid = ByteArray(16) { (it + 1).toByte() }
            CencKeys.register(kid, key)
            CencKeys.clear()
            assertNull(CencKeys.keyFor(kid))
            CencKeys.init(dir)
            assertArrayEquals(key, CencKeys.keyFor(kid))
            assertEquals(1, dir.listFiles()?.size)
        } finally {
            CencKeys.clear()
            dir.deleteRecursively()
        }
    }
}
