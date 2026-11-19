package com.music.bitchord.playback.cenc

import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor

/**
 * The stock extractors, with fragmented MP4 able to play Common Encryption.
 *
 * Only [FragmentedMp4Extractor] is wrapped: it is the extractor that reads the
 * encryption boxes, and a clear fragmented file — every YouTube `m4a` — passes
 * through [CencTrackOutput] without a copy.
 */
@UnstableApi
class CencExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val keyFor: (ByteArray) -> ByteArray? = CencKeys::keyFor,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor.underlyingImplementation is FragmentedMp4Extractor) CencExtractor(extractor, keyFor) else extractor
}

/** One extractor whose tracks decrypt — see [CencTrackOutput]. */
@UnstableApi
internal class CencExtractor(
    private val inner: Extractor,
    private val keyFor: (ByteArray) -> ByteArray?,
) : Extractor {

    private val tracks = HashMap<Int, CencTrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun getSniffFailureDetails() = inner.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                tracks.getOrPut(id) { CencTrackOutput(output.track(id, type), keyFor) }

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        tracks.values.forEach { it.reset() }
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}
