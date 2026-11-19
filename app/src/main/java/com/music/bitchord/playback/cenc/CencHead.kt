package com.music.bitchord.playback.cenc

/**
 * What the opening of an MP4 says about its encryption.
 *
 * Read from the first bytes of a file before it is played, which is where a
 * streamed MP4 keeps its `moov`: the key id the samples are encrypted under
 * (`tenc`), the scheme (`schm`: `cenc` or `cbcs`), and whether the file is
 * fragmented (`mvex`) — the one layout Media3's extractor decrypts.
 */
object CencHead {

    class Info(val kid: ByteArray, val scheme: String, val fragmented: Boolean)

    sealed interface Result {
        /** The `moov` is complete in the bytes given and names a key id. */
        class Encrypted(val info: Info) : Result
        /** The `moov` is complete and nothing in it is encrypted. */
        data object Clear : Result
        /** The `moov` is not complete yet; read further. */
        data object NeedMore : Result
        /** Not an MP4 this can read. */
        data object NotMp4 : Result
    }

    fun parse(head: ByteArray, length: Int = head.size): Result {
        var at = 0L
        var sawBox = false
        while (at + 8 <= length) {
            val position = at.toInt()
            var size = u32(head, position)
            val type = fourCc(head, position + 4)
            var header = 8
            if (size == 1L) {
                if (at + 16 > length) return Result.NeedMore
                size = u64(head, position + 8)
                header = 16
            } else if (size == 0L) {
                size = Long.MAX_VALUE - at
            }
            if (size < header || !type.all { it in ' '..'~' }) return if (sawBox) Result.NeedMore else Result.NotMp4
            if (!sawBox && type != "ftyp" && type != "styp" && type != "moov" && type != "free" && type != "skip") {
                return Result.NotMp4
            }
            sawBox = true
            if (type == "moov") {
                if (at + size > length) return Result.NeedMore
                return inMoov(head, position + header, (at + size).toInt())
            }
            at += size
        }
        return if (sawBox) Result.NeedMore else Result.NotMp4
    }

    private fun inMoov(data: ByteArray, from: Int, to: Int): Result {
        val tenc = find(data, from, to, "tenc") ?: return Result.Clear
        // tenc: version/flags (4), reserved (1), pattern or reserved (1),
        // isProtected (1), per-sample IV size (1), key id (16).
        val kidAt = tenc + 4 + 4 + 4
        if (kidAt + 16 > to) return Result.Clear
        val kid = data.copyOfRange(kidAt, kidAt + 16)
        val scheme = find(data, from, to, "schm")?.let { schm ->
            val typeAt = schm + 4 + 4
            if (typeAt + 4 <= to) fourCc(data, typeAt) else null
        } ?: "cenc"
        val fragmented = find(data, from, to, "mvex") != null
        return Result.Encrypted(Info(kid, scheme, fragmented))
    }

    /** The offset of the four-character type of the first well-formed box called [type] in a range. */
    private fun find(data: ByteArray, from: Int, to: Int, type: String): Int? {
        val bytes = type.toByteArray(Charsets.US_ASCII)
        var at = from + 4
        while (at + 4 <= to) {
            if (data[at] == bytes[0] && data[at + 1] == bytes[1] && data[at + 2] == bytes[2] && data[at + 3] == bytes[3]) {
                val size = u32(data, at - 4)
                if (size >= 8 && at - 4 + size <= to) return at
            }
            at++
        }
        return null
    }

    private fun fourCc(data: ByteArray, at: Int) = String(data, at, 4, Charsets.ISO_8859_1)

    private fun u32(data: ByteArray, at: Int): Long =
        (data[at].toLong() and 0xFF shl 24) or (data[at + 1].toLong() and 0xFF shl 16) or
            (data[at + 2].toLong() and 0xFF shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)
}
