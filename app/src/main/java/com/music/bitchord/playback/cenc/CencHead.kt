package com.music.bitchord.playback.cenc

/**
 * What the opening of an MP4 says about its encryption.
 *
 * Read from the first bytes of a file before it is played, which is where a
 * streamed MP4 keeps its `moov`: the key id the samples are encrypted under,
 * the scheme (`cenc` or `cbcs`), and whether the file is fragmented — the one
 * layout Media3's extractor decrypts.
 *
 * The boxes are walked as declared, the way the extractor walks them: `mvex`
 * as a child of `moov`, and `schm` and `tenc` from the protection scheme of
 * the same encrypted audio sample entry (`trak/mdia/minf/stbl/stsd/enca/
 * sinf`). Bytes that merely look like a box elsewhere are never read.
 */
object CencHead {

    class Info(val kid: ByteArray, val scheme: String, val fragmented: Boolean)

    sealed interface Result {
        /** The `moov` is complete in the bytes given and its audio is encrypted. */
        class Encrypted(val info: Info) : Result
        /** The `moov` is complete and its audio is not encrypted. */
        data object Clear : Result
        /** The `moov` is not complete yet; read further. */
        data object NeedMore : Result
        /** Not an MP4 this can read. */
        data object NotMp4 : Result
    }

    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    fun parse(head: ByteArray, length: Int = head.size): Result {
        var at = 0
        var first = true
        while (true) {
            if (length - at < 8) return Result.NeedMore
            val size = u32(head, at)
            val type = fourCc(head, at + 4)
            if (!type.all { it in ' '..'~' }) return if (first) Result.NotMp4 else Result.NeedMore
            if (first && type !in LEADING) return Result.NotMp4
            first = false
            val header: Int
            val declared: Long
            when (size) {
                1L -> {
                    if (length - at < 16) return Result.NeedMore
                    declared = u64(head, at + 8)
                    header = 16
                }
                0L -> {
                    // Runs to the end of the file, so it cannot be skipped from here.
                    return if (type == "moov") Result.NeedMore else Result.NotMp4
                }
                else -> {
                    declared = size
                    header = 8
                }
            }
            if (declared < header) return Result.NotMp4
            // Compared against what is left before it is added, so a vast size
            // cannot wrap the cursor.
            if (declared > (length - at).toLong()) return Result.NeedMore
            val end = at + declared.toInt()
            if (type == "moov") return inMoov(head, Box(type, at, at + header, end))
            at = end
        }
    }

    private fun inMoov(data: ByteArray, moov: Box): Result {
        val fragmented = children(data, moov).any { it.type == "mvex" }
        for (trak in children(data, moov).filter { it.type == "trak" }) {
            val stsd = path(data, trak, "mdia", "minf", "stbl", "stsd") ?: continue
            // stsd: version/flags (4), entry count (4), then the sample entries.
            if (stsd.end - stsd.body < 8) continue
            for (entry in boxes(data, stsd.body + 8, stsd.end)) {
                if (entry.type != "enca") continue
                val sinf = boxes(data, entry.body + audioEntryFields(data, entry), entry.end)
                    .firstOrNull { it.type == "sinf" } ?: continue
                val schm = children(data, sinf).firstOrNull { it.type == "schm" }
                val tenc = children(data, sinf).firstOrNull { it.type == "schi" }
                    ?.let { schi -> children(data, schi).firstOrNull { it.type == "tenc" } } ?: continue
                // tenc: version/flags (4), reserved (1), pattern or reserved (1),
                // isProtected (1), per-sample IV size (1), key id (16).
                val kidAt = tenc.body + 8
                if (kidAt + 16 > tenc.end) continue
                // schm: version/flags (4), scheme type (4).
                val scheme = schm?.takeIf { it.body + 8 <= it.end }?.let { fourCc(data, it.body + 4) } ?: "cenc"
                return Result.Encrypted(Info(data.copyOfRange(kidAt, kidAt + 16), scheme, fragmented))
            }
        }
        return Result.Clear
    }

    /**
     * The fixed fields of an audio sample entry before its child boxes: 28
     * bytes, plus 16 for a QuickTime version-1 sound description or 36 for
     * version 2 — the same reading Media3 makes.
     */
    private fun audioEntryFields(data: ByteArray, entry: Box): Int {
        if (entry.end - entry.body < 28) return entry.end - entry.body
        val version = (data[entry.body + 8].toInt() and 0xFF shl 8) or (data[entry.body + 9].toInt() and 0xFF)
        return 28 + when (version) {
            1 -> 16
            2 -> 36
            else -> 0
        }
    }

    private fun path(data: ByteArray, from: Box, vararg types: String): Box? {
        var box = from
        for (type in types) box = children(data, box).firstOrNull { it.type == type } ?: return null
        return box
    }

    private fun children(data: ByteArray, parent: Box) = boxes(data, parent.body, parent.end)

    /** The well-formed boxes laid end to end in a range; stops at the first that is not. */
    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (to - at >= 8) {
            var size = u32(data, at)
            var header = 8
            if (size == 1L) {
                if (to - at < 16) break
                size = u64(data, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header || size > (to - at).toLong()) break
            out += Box(fourCc(data, at + 4), at, at + header, at + size.toInt())
            at += size.toInt()
        }
        return out
    }

    private val LEADING = setOf("ftyp", "styp", "moov", "free", "skip", "sidx", "wide", "pdin")

    private fun fourCc(data: ByteArray, at: Int) = String(data, at, 4, Charsets.ISO_8859_1)

    private fun u32(data: ByteArray, at: Int): Long =
        (data[at].toLong() and 0xFF shl 24) or (data[at + 1].toLong() and 0xFF shl 16) or
            (data[at + 2].toLong() and 0xFF shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)
}
