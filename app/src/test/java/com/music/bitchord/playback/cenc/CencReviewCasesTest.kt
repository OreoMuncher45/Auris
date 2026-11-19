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
        assertTrue(CencProbe.startsAtZero("bytes 0-99/*"))
        assertFalse(CencProbe.startsAtZero("bytes 4096-1048575/9000000"))
        assertFalse(CencProbe.startsAtZero(""))
        assertFalse("no complete length", CencProbe.startsAtZero("bytes 0-99/"))
        assertFalse("ends past the length", CencProbe.startsAtZero("bytes 0-99/50"))
        assertFalse("trailing junk", CencProbe.startsAtZero("bytes 0-99/100 extra"))
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
    fun `a key used within a month is kept past the cap, an idle one is not`() {
        CencKeys.clear()
        try {
            var now = 1_000_000_000_000L
            CencKeys.clock = { now }
            val idle = ByteArray(16) { 0x11 }
            val paused = ByteArray(16) { 0x22 }
            CencKeys.register(idle, key)
            CencKeys.register(paused, key)
            now += 40L * 24 * 60 * 60 * 1000
            CencKeys.keyFor(paused) // played from the cache last week
            now += 7L * 24 * 60 * 60 * 1000
            for (i in 0 until 4096) {
                CencKeys.register(ByteArray(16).also { java.nio.ByteBuffer.wrap(it).putInt(i + 1) }, key)
            }
            assertArrayEquals("a key used within the month survives the cap", key, CencKeys.keyFor(paused))
            assertEquals("a key idle for over a month goes", null, CencKeys.keyFor(idle))
        } finally {
            CencKeys.clear()
        }
    }

    @Test
    fun `a use survives a restart, and keeps its key through eviction`() {
        val dir = Files.createTempDirectory("cenc").toFile()
        CencKeys.clear()
        try {
            var now = 1_000_000_000_000L
            CencKeys.clock = { now }
            CencKeys.init(dir)
            val recent = ByteArray(16) { 0x33 }
            CencKeys.register(recent, key)
            for (i in 0 until 4095) CencKeys.register(ByteArray(16).also { java.nio.ByteBuffer.wrap(it).putInt(i + 1) }, key)
            now += 40L * 24 * 60 * 60 * 1000
            CencKeys.keyFor(recent) // played from the cache
            // Restart a day later.
            now += 1L * 24 * 60 * 60 * 1000
            CencKeys.clear()
            CencKeys.clock = { now }
            CencKeys.init(dir)
            CencKeys.register(ByteArray(16) { 0x44 }, key) // pushes the store over the cap
            assertArrayEquals("the recently used key outlived the restart and the eviction", key, CencKeys.keyFor(recent))
        } finally {
            CencKeys.clear()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a saved flac from a source with an unknown length is seekable`() {
        // The fixture with its dfLa STREAMINFO total zeroed: "unknown".
        val bytes = resource("sine-cenc.mp4").readBytes()
        val dfla = indexOf(bytes, "dfLa")
        // dfLa: type (4), version/flags (4), block header (4), STREAMINFO;
        // the total is the low nibble of body byte 13 and bytes 14-17.
        val total = dfla + 4 + 4 + 4 + 13
        bytes[total] = (bytes[total].toInt() and 0xF0).toByte()
        for (i in 1..4) bytes[total + i] = 0
        val unknown = File.createTempFile("unknown-length", ".mp4").apply { writeBytes(bytes) }
        val saved = File.createTempFile("saved", ".flac")
        try {
            saved.outputStream().use { CencFlacFile.write(unknown, it) { if (it.contentEquals(kid)) key else null } }
            val extractor = androidx.media3.extractor.flac.FlacExtractor()
            var seekMap: androidx.media3.extractor.SeekMap? = null
            extractor.init(object : androidx.media3.extractor.ExtractorOutput {
                override fun track(id: Int, type: Int) = androidx.media3.extractor.DiscardingTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(map: androidx.media3.extractor.SeekMap) { seekMap = map }
            })
            val all = saved.readBytes()
            var position = 0
            val reader = object : androidx.media3.common.DataReader {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= all.size) return C.RESULT_END_OF_INPUT
                    val n = minOf(length, all.size - position)
                    System.arraycopy(all, position, buffer, offset, n)
                    position += n
                    return n
                }
            }
            var input = androidx.media3.extractor.DefaultExtractorInput(reader, 0, all.size.toLong())
            val holder = androidx.media3.extractor.PositionHolder()
            while (seekMap == null) {
                when (extractor.read(input, holder)) {
                    androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT -> break
                    androidx.media3.extractor.Extractor.RESULT_SEEK -> {
                        position = holder.position.toInt()
                        input = androidx.media3.extractor.DefaultExtractorInput(reader, holder.position, all.size.toLong())
                    }
                }
            }
            val map = requireNotNull(seekMap) { "no seek map" }
            assertTrue("the saved file can be seeked", map.isSeekable)
            assertEquals(1_200_000L, map.durationUs)
        } finally {
            unknown.delete()
            saved.delete()
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
    fun `block sizes are read from where each frame header puts them`() {
        fun frame(code: Int, vararg tail: Int) =
            byteArrayOf(0xFF.toByte(), 0xF8.toByte(), (code shl 4 or 0x9).toByte(), 0x08) + tail.map { it.toByte() }.toByteArray()
        // Fixed codes.
        assertEquals(192, CencFlacFile.blockSize(frame(1, 0x00), 5))
        assertEquals(4608, CencFlacFile.blockSize(frame(5, 0x00), 5))
        assertEquals(4096, CencFlacFile.blockSize(frame(12, 0x00), 5))
        // Code 6: an 8-bit size after the coded frame number.
        assertEquals(100, CencFlacFile.blockSize(frame(6, 0x05, 99), 6))
        assertEquals(256, CencFlacFile.blockSize(frame(6, 0xC2, 0x80, 255), 7)) // two-byte frame number
        // Code 7: a 16-bit size after it.
        assertEquals(1153, CencFlacFile.blockSize(frame(7, 0x05, 0x04, 0x80), 7))
        assertEquals(65536, CencFlacFile.blockSize(frame(7, 0xE1, 0x80, 0x80, 0xFF, 0xFF), 9)) // three-byte frame number
        // Truncated before the size field, or not a frame at all.
        assertEquals(0, CencFlacFile.blockSize(frame(6, 0x05), 5))
        assertEquals(0, CencFlacFile.blockSize(frame(7, 0xC2, 0x80, 0x01), 7))
        assertEquals(0, CencFlacFile.blockSize(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x00), 5))
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
