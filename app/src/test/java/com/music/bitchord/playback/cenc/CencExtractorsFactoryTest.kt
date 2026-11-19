package com.music.bitchord.playback.cenc

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The player's extractor chain, reading the FFmpeg-encrypted fixture the way
 * playback does: the samples that reach the track are clear, unflagged, and
 * the same bytes the clear file yields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CencExtractorsFactoryTest {

    private val key = ByteArray(16) { (0x00 + it * 0x11).toByte() }
    private val kid = ByteArray(16) { (0xA0 + it).toByte() }

    private fun resource(name: String) = File(requireNotNull(javaClass.classLoader?.getResource("cenc/$name")).toURI())

    @Test
    fun `only fragmented MP4 is wrapped`() {
        val extractors = CencExtractorsFactory(DefaultExtractorsFactory()) { null }.createExtractors()
        val wrapped = extractors.filterIsInstance<CencExtractor>()
        assertEquals(1, wrapped.size)
        assertTrue(wrapped.single().underlyingImplementation is FragmentedMp4Extractor)
        assertEquals(DefaultExtractorsFactory().createExtractors().size, extractors.size)
    }

    @Test
    fun `encrypted samples reach the player clear`() {
        val clear = samples(resource("sine-clear.mp4")) { null }
        val decrypted = samples(resource("sine-cenc.mp4")) { if (it.contentEquals(kid)) key else null }
        assertTrue(clear.data.isNotEmpty())
        assertEquals(clear.count, decrypted.count)
        assertTrue("no sample may still be flagged encrypted", decrypted.flags and C.BUFFER_FLAG_ENCRYPTED == 0)
        assertNull(decrypted.format?.drmInitData)
        assertTrue(clear.data.contentEquals(decrypted.data))
    }

    private class Collected(val data: ByteArray, val count: Int, val flags: Int, val format: Format?)

    private fun samples(file: File, keyFor: (ByteArray) -> ByteArray?): Collected {
        val extractor = CencExtractorsFactory(DefaultExtractorsFactory(), keyFor).createExtractors()
            .single { it is CencExtractor }
        val bytes = ByteArrayOutputStream()
        var count = 0
        var flags = 0
        var format: Format? = null
        val sink = object : TrackOutput {
            override fun format(f: Format) { format = f }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buffer = ByteArray(length)
                val read = input.read(buffer, 0, length)
                if (read > 0) bytes.write(buffer, 0, read)
                return read
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                bytes.write(data.data, data.position, length)
                data.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, f: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                count++
                flags = flags or f
            }
        }
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = sink
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val all = file.readBytes()
        var position = 0
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= all.size) return C.RESULT_END_OF_INPUT
                val n = minOf(length, all.size - position)
                System.arraycopy(all, position, buffer, offset, n)
                position += n
                return n
            }
        }
        var input = DefaultExtractorInput(reader, 0, all.size.toLong())
        val holder = PositionHolder()
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    position = holder.position.toInt()
                    input = DefaultExtractorInput(reader, holder.position, all.size.toLong())
                }
            }
        }
        return Collected(bytes.toByteArray(), count, flags, format)
    }
}
