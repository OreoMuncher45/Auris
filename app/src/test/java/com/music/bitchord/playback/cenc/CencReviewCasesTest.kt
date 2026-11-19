package com.music.bitchord.playback.cenc

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.ParserException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** The edge cases review asked for, one test each. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CencReviewCasesTest {

    private fun resource(name: String) = File(requireNotNull(javaClass.classLoader?.getResource("cenc/$name")).toURI())
    private val key = ByteArray(16) { (it * 0x11).toByte() }
    private val kid = ByteArray(16) { (0xA0 + it).toByte() }

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.US_ASCII) + body
    }

    @Test
    fun `a short read is not yet a verdict`() {
        val file = resource("sine-cenc.mp4").readBytes()
        assertEquals(CencHead.Result.NeedMore, CencHead.parse(file, 4))
        assertEquals(CencHead.Result.NeedMore, CencHead.parse(file, 0))
    }

    @Test
    fun `a vast box size cannot wrap the cursor`() {
        val ftyp = box("ftyp", ByteArray(8))
        // A 64-bit size near the top of the range after a nonzero cursor.
        val huge = byteArrayOf(0, 0, 0, 1) + "free".toByteArray() +
            byteArrayOf(0x7F, -1, -1, -1, -1, -1, -1, -0x10)
        assertEquals(CencHead.Result.NeedMore, CencHead.parse(ftyp + huge + ByteArray(32)))
    }

    @Test
    fun `tenc-shaped bytes outside the sample entry are not read`() {
        val real = resource("sine-cenc.mp4").readBytes()
        val result = CencHead.parse(real) as CencHead.Result.Encrypted
        // A decoy inside a free box at the head of moov.
        val decoyKid = ByteArray(16) { 0x5A }
        val decoy = box("free", box("tenc", ByteArray(8) + decoyKid))
        val moovAt = indexOf(real, "moov") - 4
        val moovSize = u32(real, moovAt)
        val newMoov = box("moov", decoy + real.copyOfRange(moovAt + 8, moovAt + moovSize))
        val patched = real.copyOfRange(0, moovAt) + newMoov + real.copyOfRange(moovAt + moovSize, real.size)
        val again = CencHead.parse(patched) as CencHead.Result.Encrypted
        assertArrayEquals(result.info.kid, again.info.kid)
        assertTrue(again.info.fragmented)
    }

    @Test
    fun `a partial header answer must start at byte zero`() {
        assertTrue(CencProbe.startsAtZero("bytes 0-1048575/9000000"))
        assertTrue(CencProbe.startsAtZero("bytes 0-8999/9000"))
        assertFalse(CencProbe.startsAtZero("bytes 4096-1048575/9000000"))
        assertFalse(CencProbe.startsAtZero(""))
    }

    @Test
    fun `a subsample table must cover the whole sample`() {
        val data = ByteArray(32)
        try {
            CencDecryptor.decrypt(data, 0, 32, C.CRYPTO_MODE_AES_CTR, key, ByteArray(8), intArrayOf(0), intArrayOf(16))
            fail("a table covering half the sample was accepted")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            CencDecryptor.decrypt(data, 0, 32, C.CRYPTO_MODE_AES_CTR, key, ByteArray(8), intArrayOf(-1), intArrayOf(33))
            fail("a negative size was accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `a base64 key that begins with 0x is read as base64`() {
        // "0" and "x" are the 6-bit values 52 and 49: 110100 110001, so a key
        // opening 0xD3 0x1_ is spelled "0x…" in base64.
        val bytes = ByteArray(16) { i -> if (i == 0) 0xD3.toByte() else if (i == 1) 0x10 else (i * 9).toByte() }
        val spelled = Base64.getEncoder().withoutPadding().encodeToString(bytes)
        assertTrue(spelled.startsWith("0x"))
        assertArrayEquals(bytes, CencKeys.parseKey(spelled))
    }

    @Test
    fun `a failed write is retried, and the last good copy survives`() {
        val dir = Files.createTempDirectory("cenc").toFile()
        try {
            CencKeys.clear()
            CencKeys.init(dir)
            val first = ByteArray(16) { 1 }
            CencKeys.register(first, key)
            val store = File(dir, "cenc-keys.txt")
            val good = store.readText()
            // Block the temporary file's name so the next write fails.
            File(dir, "cenc-keys.txt.tmp").mkdirs()
            val second = ByteArray(16) { 2 }
            CencKeys.register(second, key)
            assertEquals("the last good copy is untouched", good, store.readText())
            File(dir, "cenc-keys.txt.tmp").deleteRecursively()
            // The same key again: memory has it, but the file still owes it.
            CencKeys.register(second, key)
            CencKeys.clear()
            CencKeys.init(dir)
            assertArrayEquals(key, CencKeys.keyFor(second))
            assertArrayEquals(key, CencKeys.keyFor(first))
        } finally {
            CencKeys.clear()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a key that is used is not the one evicted`() {
        CencKeys.clear()
        try {
            val kept = ByteArray(16) { 0x77 }
            CencKeys.register(kept, key)
            for (i in 0 until 4096) {
                if (i % 512 == 0) CencKeys.keyFor(kept) // played from the cache now and then
                val other = ByteArray(16).also { java.nio.ByteBuffer.wrap(it).putInt(i + 1) }
                CencKeys.register(other, key)
            }
            assertArrayEquals(key, CencKeys.keyFor(kept))
        } finally {
            CencKeys.clear()
        }
    }

    @Test
    fun `the saved flac states its length, even when the source left it unknown`() {
        val source = resource("sine.flac").readBytes()
        val stated = streamInfoTotal(source)
        assertTrue(stated > 0)
        val out = ByteArrayOutputStream()
        CencFlacFile.write(resource("sine-cenc.mp4"), out) { if (it.contentEquals(kid)) key else null }
        assertEquals("counted from the frames", stated, streamInfoTotal(out.toByteArray()))

        val unknown = source.copyOf(42).also { header ->
            header[21] = (header[21].toInt() and 0xF0).toByte()
            for (i in 22..25) header[i] = 0
        }
        assertEquals(0L, streamInfoTotal(unknown))
        assertEquals(stated, streamInfoTotal(CencFlacFile.completed(unknown, stated)))
        assertEquals("a stated count is kept", stated, streamInfoTotal(CencFlacFile.completed(source.copyOf(42), stated + 1)))
    }

    @Test
    fun `a file with a header and no audio is not a download`() {
        val whole = resource("sine-cenc.mp4").readBytes()
        val firstFragment = indexOf(whole, "moof") - 4
        val headerOnly = File.createTempFile("header-only", ".mp4").apply { writeBytes(whole.copyOf(firstFragment)) }
        try {
            CencFlacFile.write(headerOnly, ByteArrayOutputStream()) { key }
            fail("a header-only file was written as a download")
        } catch (expected: ParserException) {
            assertTrue(expected.message.orEmpty().contains("no audio"))
        } finally {
            headerOnly.delete()
        }
    }

    private fun streamInfoTotal(flac: ByteArray): Long {
        val at = 8 + 13
        return ((flac[at].toLong() and 0x0F) shl 32) or ((flac[at + 1].toLong() and 0xFF) shl 24) or
            ((flac[at + 2].toLong() and 0xFF) shl 16) or ((flac[at + 3].toLong() and 0xFF) shl 8) or
            (flac[at + 4].toLong() and 0xFF)
    }

    private fun indexOf(data: ByteArray, type: String): Int {
        val t = type.toByteArray()
        for (i in 0..data.size - 4) if (data[i] == t[0] && data[i + 1] == t[1] && data[i + 2] == t[2] && data[i + 3] == t[3]) return i
        return -1
    }

    private fun u32(data: ByteArray, at: Int) =
        (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)
}
