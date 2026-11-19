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

    /** @return how many bytes were written. */
    fun write(input: File, sink: OutputStream, keyFor: (ByteArray) -> ByteArray? = CencKeys::keyFor): Long {
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
        sink.flush()
        return flac.written
    }

    private class FileReader(private val file: RandomAccessFile) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val read = file.read(buffer, offset, length)
            return if (read < 0) C.RESULT_END_OF_INPUT else read
        }
    }

    /** The FLAC stream: the header once, then every frame as it arrives. */
    private class FlacSink(private val out: OutputStream) : TrackOutput {
        var started = false
        var written = 0L
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) {
            if (started) return
            if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) {
                throw ParserException.createForMalformedContainer("the audio is ${format.sampleMimeType}, not FLAC", null)
            }
            val header = format.initializationData.firstOrNull()
                ?.takeIf { it.size > 4 && String(it, 0, 4, Charsets.US_ASCII) == "fLaC" }
                ?: throw ParserException.createForMalformedContainer("the FLAC track has no stream header", null)
            out.write(header)
            written += header.size
            started = true
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            out.write(scratch, 0, read)
            written += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            out.write(data.data, data.position, length)
            data.skipBytes(length)
            written += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!started) throw ParserException.createForMalformedContainer("FLAC frames before the stream header", null)
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
