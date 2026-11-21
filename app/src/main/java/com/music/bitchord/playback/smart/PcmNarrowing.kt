package com.music.bitchord.playback.smart

/**
 * The arithmetic behind [AudioDecoder]'s narrowing of non-16-bit decoder
 * output, kept free of Android types so it can be tested on the JVM.
 *
 * The encodings are [android.media.AudioFormat]'s values, spelled out because
 * it only names the 24- and 32-bit ones from API 31, while MediaCodec reported
 * them earlier.
 */
internal object PcmNarrowing {
    const val PCM_16BIT = 2
    const val PCM_8BIT = 3
    const val PCM_FLOAT = 4
    const val PCM_24BIT_PACKED = 21
    const val PCM_32BIT = 22

    fun supports(encoding: Int): Boolean = encoding == PCM_16BIT || encoding == PCM_8BIT ||
        encoding == PCM_FLOAT || encoding == PCM_24BIT_PACKED || encoding == PCM_32BIT

    /**
     * The encoding a decoder's *output* format reports. A format without the key
     * is signed 16-bit PCM — that is what MediaCodec documents for an absent
     * `pcm-encoding` — and it is never the input track's encoding, which is only
     * what the decoder was asked for.
     */
    fun outputEncodingOf(reported: Int?): Int = reported ?: PCM_16BIT

    /** Bytes per sample of [encoding]. */
    fun widthOf(encoding: Int): Int = when (encoding) {
        PCM_FLOAT, PCM_32BIT -> 4
        PCM_24BIT_PACKED -> 3
        PCM_8BIT -> 1
        else -> 2
    }

    /**
     * Narrows [count] little-endian samples of [encoding] from [input] to
     * little-endian signed 16-bit in [out], which must hold `count * 2` bytes.
     */
    fun narrow(input: ByteArray, count: Int, encoding: Int, out: ByteArray) {
        val width = widthOf(encoding)
        var at = 0
        var to = 0
        for (index in 0 until count) {
            val value = when (encoding) {
                PCM_FLOAT -> {
                    val bits = (input[at].toInt() and 0xff) or
                        ((input[at + 1].toInt() and 0xff) shl 8) or
                        ((input[at + 2].toInt() and 0xff) shl 16) or
                        (input[at + 3].toInt() shl 24)
                    (java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 1f) * 32767f).toInt()
                }
                // The top two bytes of a little-endian sample are its 16-bit value.
                PCM_32BIT -> (input[at + 2].toInt() and 0xff) or (input[at + 3].toInt() shl 8)
                PCM_24BIT_PACKED -> (input[at + 1].toInt() and 0xff) or (input[at + 2].toInt() shl 8)
                PCM_8BIT -> ((input[at].toInt() and 0xff) - 128) shl 8
                else -> (input[at].toInt() and 0xff) or (input[at + 1].toInt() shl 8)
            }
            out[to] = value.toByte()
            out[to + 1] = (value shr 8).toByte()
            at += width
            to += 2
        }
    }
}
