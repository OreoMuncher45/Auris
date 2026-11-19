package com.music.bitchord.playback.cenc

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * An encrypted fragmented MP4 holding FLAC, written out as a plain `.flac`.
 *
 * What the reference host does with FFmpeg — `-decryption_key K -f mov -i in
 * -c copy -f flac out` — done with the extractor the player already uses: the
 * file is read by Media3's fragmented-MP4 extractor, each sample is decrypted
 * by [CencTrackOutput], and the FLAC stream is the `fLaC` marker and metadata
 * blocks from the file's `dfLa` box followed by the frames, which in MP4 are
 * one per sample. Nothing is re-encoded.
 */
@UnstableApi
object CencFlacFile {

    /**
     * The frames go to a file beside [input] first, so the header can be
     * finished once they are all known: a stream whose STREAMINFO gives its
     * length as 0 ("unknown") gets the real count, without which a saved
     * `.flac` with no seek table cannot be seeked.
     *
     * @return how many bytes were written.
     */
    fun write(input: File, sink: OutputStream, keyFor: (ByteArray) -> ByteArray? = CencKeys::keyFor): Long {
        val frames = File.createTempFile("flac-", ".frames", input.absoluteFile.parentFile)
        try {
            val flac = frames.outputStream().buffered().use { out -> extract(input, out, keyFor) }
            val header = completed(flac.header, flac.samples)
            sink.write(header)
            frames.inputStream().use { it.copyTo(sink) }
            sink.flush()
            return header.size + flac.frameBytes
        } finally {
            frames.delete()
        }
    }

    /**
     * [header] with its STREAMINFO total filled in from [samples] when the
     * source stated none. A count the source did state is kept as it is.
     */
    internal fun completed(header: ByteArray, samples: Long): ByteArray {
        // fLaC (4), block header (4), then STREAMINFO: the total is the low
        // nibble of body byte 13 and bytes 14–17, a 36-bit count.
        if (header.size < 8 + 18 || (header[4].toInt() and 0x7F) != 0) return header
        val at = 8 + 13
        val stated = ((header[at].toLong() and 0x0F) shl 32) or
            ((header[at + 1].toLong() and 0xFF) shl 24) or ((header[at + 2].toLong() and 0xFF) shl 16) or
            ((header[at + 3].toLong() and 0xFF) shl 8) or (header[at + 4].toLong() and 0xFF)
        if (stated != 0L || samples <= 0L || samples >= (1L shl 36)) return header
        val out = header.copyOf()
        out[at] = ((out[at].toInt() and 0xF0) or ((samples shr 32).toInt() and 0x0F)).toByte()
        out[at + 1] = (samples shr 24).toByte()
        out[at + 2] = (samples shr 16).toByte()
        out[at + 3] = (samples shr 8).toByte()
        out[at + 4] = samples.toByte()
        return out
    }

    /**
     * The audio samples one FLAC frame holds, read from its header: the
     * block-size code, and the 8- or 16-bit size that follows the coded frame
     * number for codes 6 and 7. Zero when the bytes are not a frame header.
     */
    internal fun blockSize(frame: ByteArray, length: Int): Int {
        if (length < 5 || frame[0] != 0xFF.toByte() || (frame[1].toInt() and 0xFE) != 0xF8) return 0
        val code = (frame[2].toInt() shr 4) and 0x0F
        return when (code) {
            1 -> 192
            in 2..5 -> 576 shl (code - 2)
            in 8..15 -> 256 shl (code - 8)
            6, 7 -> {
                val lead = frame[4].toInt() and 0xFF
                val numberBytes = when {
                    lead and 0x80 == 0 -> 1
                    lead and 0xE0 == 0xC0 -> 2
                    lead and 0xF0 == 0xE0 -> 3
                    lead and 0xF8 == 0xF0 -> 4
                    lead and 0xFC == 0xF8 -> 5
                    lead and 0xFE == 0xFC -> 6
                    lead == 0xFE -> 7
                    else -> return 0
                }
                val at = 4 + numberBytes
                if (code == 6) {
                    if (length < at + 1) 0 else (frame[at].toInt() and 0xFF) + 1
                } else {
                    if (length < at + 2) 0 else ((frame[at].toInt() and 0xFF shl 8) or (frame[at + 1].toInt() and 0xFF)) + 1
                }
            }
            else -> 0
        }
    }

    private fun extract(input: File, sink: OutputStream, keyFor: (ByteArray) -> ByteArray?): FlacSink {
        val flac = FlacSink(sink)
        RandomAccessFile(input, "r").use { file ->
            val extractor: Extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
            extractor.init(object : ExtractorOutput {
                private var audio: TrackOutput? = null
                override fun track(id: Int, type: Int): TrackOutput =
                    if (type == C.TRACK_TYPE_AUDIO && audio == null) CencTrackOutput(flac, keyFor).also { audio = it }
                    else Discard()

                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            val reader = FileReader(file)
            var input_ = DefaultExtractorInput(reader, 0, file.length())
            val position = PositionHolder()
            while (true) {
                when (extractor.read(input_, position)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        file.seek(position.position)
                        input_ = DefaultExtractorInput(reader, position.position, file.length())
                    }
                }
            }
            extractor.release()
        }
        if (!flac.started) throw ParserException.createForMalformedContainer("no FLAC track in the file", null)
        // A header with nothing after it is not a download.
        if (flac.frames == 0) throw ParserException.createForMalformedContainer("the FLAC track has no audio", null)
        return flac
    }

    private class FileReader(private val file: RandomAccessFile) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val read = file.read(buffer, offset, length)
            return if (read < 0) C.RESULT_END_OF_INPUT else read
        }
    }

    /** The FLAC frames as they arrive, with the header kept back and the audio counted. */
    private class FlacSink(private val out: OutputStream) : TrackOutput {
        var started = false
        var header = ByteArray(0)
        var frameBytes = 0L
        var frames = 0
        var samples = 0L
        private val scratch = ByteArray(64 * 1024)
        /** The opening bytes of the frame being written, enough for its header. */
        private val opening = ByteArray(16)
        private var openingFilled = 0

        private fun note(bytes: ByteArray, offset: Int, length: Int) {
            val take = minOf(length, opening.size - openingFilled)
            if (take > 0) {
                System.arraycopy(bytes, offset, opening, openingFilled, take)
                openingFilled += take
            }
            out.write(bytes, offset, length)
            frameBytes += length
        }

        override fun format(format: Format) {
            if (started) return
            if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) {
                throw ParserException.createForMalformedContainer("the audio is ${format.sampleMimeType}, not FLAC", null)
            }
            val header = format.initializationData.firstOrNull()
                ?.takeIf { it.size > 4 && String(it, 0, 4, Charsets.US_ASCII) == "fLaC" }
                ?: throw ParserException.createForMalformedContainer("the FLAC track has no stream header", null)
            this.header = header
            started = true
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            note(scratch, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            note(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!started) throw ParserException.createForMalformedContainer("FLAC frames before the stream header", null)
            frames++
            samples += blockSize(opening, openingFilled)
            openingFilled = 0
        }
    }

    private class Discard : TrackOutput {
        private val scratch = ByteArray(8192)
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
            return read
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    }
}
