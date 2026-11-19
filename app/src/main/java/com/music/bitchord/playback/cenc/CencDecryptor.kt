package com.music.bitchord.playback.cenc

import androidx.media3.common.C
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Common Encryption (ISO/IEC 23001-7) for one sample, decrypted in place.
 *
 * Two schemes, because those are the two the files in the wild use:
 *
 *  - `cenc`: AES-CTR. The encrypted ranges of one sample form a single
 *    keystream, so they are gathered, decrypted together and put back. An
 *    eight-byte IV is the high half of the counter block.
 *  - `cbcs`: AES-CBC with a pattern. The chain restarts from the IV at every
 *    subsample; within one, `crypt` blocks are encrypted and `skip` blocks are
 *    left clear, repeating, and a trailing partial block is always clear. A
 *    0:0 pattern means every whole block is encrypted.
 *
 * The clear and encrypted sizes are the sample's subsample table; a sample
 * with no table is one encrypted range.
 */
internal object CencDecryptor {

    private const val BLOCK = 16

    fun decrypt(
        data: ByteArray,
        offset: Int,
        length: Int,
        mode: Int,
        key: ByteArray,
        iv: ByteArray,
        clearSizes: IntArray,
        encryptedSizes: IntArray,
        cryptBlocks: Int = 0,
        skipBlocks: Int = 0,
    ) {
        require(clearSizes.size == encryptedSizes.size) { "subsample table is uneven" }
        require(clearSizes.all { it >= 0 } && encryptedSizes.all { it >= 0 }) { "a negative subsample size" }
        // The table must account for every byte: a tail it leaves out would be
        // passed on as clear while still being ciphertext.
        val covered = clearSizes.sumOf { it.toLong() } + encryptedSizes.sumOf { it.toLong() }
        require(covered == length.toLong()) { "subsamples cover $covered bytes of a $length-byte sample" }
        when (mode) {
            C.CRYPTO_MODE_AES_CTR -> ctr(data, offset, key, counterBlock(iv), clearSizes, encryptedSizes)
            C.CRYPTO_MODE_AES_CBC -> cbcs(data, offset, key, counterBlock(iv), clearSizes, encryptedSizes, cryptBlocks, skipBlocks)
            else -> throw IllegalArgumentException("unsupported encryption mode $mode")
        }
    }

    private fun ctr(data: ByteArray, offset: Int, key: ByteArray, iv: ByteArray, clear: IntArray, encrypted: IntArray) {
        val total = encrypted.sum()
        if (total == 0) return
        val gathered = ByteArray(total)
        var at = offset
        var into = 0
        for (index in clear.indices) {
            at += clear[index]
            System.arraycopy(data, at, gathered, into, encrypted[index])
            at += encrypted[index]
            into += encrypted[index]
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(gathered)
        at = offset
        into = 0
        for (index in clear.indices) {
            at += clear[index]
            System.arraycopy(plain, into, data, at, encrypted[index])
            at += encrypted[index]
            into += encrypted[index]
        }
    }

    private fun cbcs(
        data: ByteArray,
        offset: Int,
        key: ByteArray,
        iv: ByteArray,
        clear: IntArray,
        encrypted: IntArray,
        cryptBlocks: Int,
        skipBlocks: Int,
    ) {
        val crypt = if (cryptBlocks == 0 && skipBlocks == 0) 1 else cryptBlocks
        val skip = if (cryptBlocks == 0 && skipBlocks == 0) 0 else skipBlocks
        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        var at = offset
        for (index in clear.indices) {
            at += clear[index]
            val start = at
            val wholeBlocks = encrypted[index] / BLOCK
            // The blocks this subsample encrypts, in order: one CBC chain.
            val positions = ArrayList<Int>()
            var block = 0
            while (block < wholeBlocks) {
                var c = 0
                while (c < crypt && block < wholeBlocks) {
                    positions += start + block * BLOCK
                    block++
                    c++
                }
                block += skip
            }
            if (positions.isNotEmpty()) {
                val gathered = ByteArray(positions.size * BLOCK)
                positions.forEachIndexed { i, p -> System.arraycopy(data, p, gathered, i * BLOCK, BLOCK) }
                cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
                val plain = cipher.doFinal(gathered)
                positions.forEachIndexed { i, p -> System.arraycopy(plain, i * BLOCK, data, p, BLOCK) }
            }
            at += encrypted[index]
        }
    }

    /** An IV as a 16-byte block: an eight-byte one is the high half, the counter the low. */
    private fun counterBlock(iv: ByteArray): ByteArray = when (iv.size) {
        BLOCK -> iv
        8 -> iv.copyOf(BLOCK)
        else -> throw IllegalArgumentException("an IV is 8 or 16 bytes, not ${iv.size}")
    }
}
