package com.music.bitchord.playback.cenc

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * A track whose encrypted samples leave it in the clear.
 *
 * Media3's fragmented-MP4 extractor reads Common Encryption itself and hands
 * every encrypted sample over with a prefix — the signal byte, the IV and the
 * subsample table, marked [TrackOutput.SAMPLE_DATA_PART_ENCRYPTION] — for a
 * DRM session to act on. This app has no DRM session and wants none: the key
 * for an extension's file is handed over by the extension (see [CencKeys]),
 * so the sample is decrypted here and the renderers, the FFmpeg decoder and
 * the bit-perfect path all see an ordinary track.
 *
 * A clear sample is passed through untouched and uncopied. Only a sample whose
 * first bytes are an encryption prefix is held until its metadata arrives.
 *
 * @param keyFor the content key for a key id, or null when none is known — in
 *   which case the sample fails loudly rather than reaching a decoder as noise.
 */
@UnstableApi
internal class CencTrackOutput(
    private val delegate: TrackOutput,
    private val keyFor: (ByteArray) -> ByteArray?,
) : TrackOutput {

    private var buffer = ByteArray(0)
    private var buffered = 0
    /** Inside a sample, and whether that sample is being held for decryption. */
    private var inSample = false
    private var holding = false

    override fun format(format: Format) {
        // The file's own protection boxes describe a DRM system that is never
        // asked; left on, they make the player look for a session to open.
        delegate.format(if (format.drmInitData != null) format.buildUpon().setDrmInitData(null).build() else format)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!begin(sampleDataPart)) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        grow(length)
        val read = input.read(buffer, buffered, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        buffered += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!begin(sampleDataPart)) return delegate.sampleData(data, length, sampleDataPart)
        grow(length)
        data.readBytes(buffer, buffered, length)
        buffered += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!holding) {
            inSample = false
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        inSample = false
        holding = false
        if (cryptoData == null || (flags and C.BUFFER_FLAG_ENCRYPTED) == 0) {
            throw ParserException.createForMalformedContainer("an encryption prefix with no encryption metadata", null)
        }
        if (offset != 0 || size != buffered) {
            throw ParserException.createForMalformedContainer("an encrypted sample of $size bytes arrived as $buffered", null)
        }
        // Looked up per sample, by the key id the sample itself is under: a
        // fragment that names a different id than the file's default (a
        // `seig` sample group) is not decrypted with the wrong key, and fails
        // here, saying which id it needed.
        val key = keyOf(cryptoData.encryptionKey)
            ?: throw ParserException.createForMalformedContainer(
                "no key for ${CencKeys.hex(cryptoData.encryptionKey)}: the source's key is for a different key id",
                null,
            )

        // The prefix, as the extractor wrote it.
        var at = 0
        val signal = buffer[at++].toInt() and 0xFF
        val ivSize = signal and 0x7F
        val iv = buffer.copyOfRange(at, at + ivSize)
        at += ivSize
        val clearSizes: IntArray
        val encryptedSizes: IntArray
        if ((signal and 0x80) != 0) {
            val count = u16(at)
            at += 2
            clearSizes = IntArray(count)
            encryptedSizes = IntArray(count)
            for (index in 0 until count) {
                clearSizes[index] = u16(at)
                encryptedSizes[index] = u32(at + 2)
                at += 6
            }
        } else {
            clearSizes = intArrayOf(0)
            encryptedSizes = intArrayOf(buffered - at)
        }
        val sampleLength = buffered - at
        if (sampleLength < 0) throw ParserException.createForMalformedContainer("an encryption prefix longer than its sample", null)
        try {
            CencDecryptor.decrypt(
            data = buffer,
            offset = at,
            length = sampleLength,
            mode = cryptoData.cryptoMode,
            key = key,
            iv = iv,
            clearSizes = clearSizes,
            encryptedSizes = encryptedSizes,
            cryptBlocks = cryptoData.encryptedBlocks,
            skipBlocks = cryptoData.clearBlocks,
            )
        } catch (malformed: IllegalArgumentException) {
            throw ParserException.createForMalformedContainer(malformed.message, malformed)
        }
        delegate.sampleData(ParsableByteArray(buffer.copyOfRange(at, buffered)), sampleLength, TrackOutput.SAMPLE_DATA_PART_MAIN)
        buffered = 0
        delegate.sampleMetadata(timeUs, flags and C.BUFFER_FLAG_ENCRYPTED.inv(), sampleLength, 0, null)
    }

    // A track's samples are under one key id, so the lookup is made once.
    private var lastKid: ByteArray? = null
    private var lastKey: ByteArray? = null

    private fun keyOf(kid: ByteArray): ByteArray? {
        if (lastKid?.contentEquals(kid) == true) return lastKey
        return keyFor(kid)?.also { found ->
            lastKid = kid.copyOf()
            lastKey = found
        }
    }

    /** Drops a sample the extractor abandoned, as it does on a seek. */
    fun reset() {
        inSample = false
        holding = false
        buffered = 0
    }

    /** Whether this part belongs to a sample being held. The first part of a sample decides. */
    private fun begin(part: Int): Boolean {
        if (!inSample) {
            inSample = true
            holding = part == TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
            buffered = 0
        }
        return holding
    }

    private fun grow(extra: Int) {
        if (buffered + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffered + extra, buffer.size * 2, 4096))
    }

    private fun u16(at: Int) = (buffer[at].toInt() and 0xFF shl 8) or (buffer[at + 1].toInt() and 0xFF)

    private fun u32(at: Int) = (buffer[at].toInt() and 0xFF shl 24) or (buffer[at + 1].toInt() and 0xFF shl 16) or
        (buffer[at + 2].toInt() and 0xFF shl 8) or (buffer[at + 3].toInt() and 0xFF)
}
