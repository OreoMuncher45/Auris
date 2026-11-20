package com.music.bitchord.data.sources

import java.util.Locale

/**
 * What a source is about to hand the decoder, as far as the source will say.
 *
 * Every field is nullable because most of them are genuinely unknown until the
 * bytes arrive: a server that reports "flac" rarely reports the bit depth with
 * it, and one that reports a bitrate is usually describing a transcode it has
 * not performed yet. Nothing here is inferred — a null means "not stated", and
 * the player reports the decoder's own numbers over these once it has them.
 * The two are worth comparing rather than merging, because a source claiming
 * 24/192 and a sink running at 16/48 is exactly the failure this feature is
 * most likely to hide.
 */
data class StreamFormat(
    /** Container/codec as the source names it, lowercased: `flac`, `opus`, `mp3`. */
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
) {
    /**
     * Whether this is a bit-exact copy of the master the source holds.
     *
     * Decided on the codec alone. A lossless codec at any bitrate is lossless;
     * a lossy one at any bitrate is not, and no sample rate rescues it — a
     * 192kHz Opus stream is still Opus. Unknown codec means unknown, not false,
     * so callers that care have to say what they want done about it.
     */
    val isLossless: Boolean?
        get() = codec?.let { DolbyAudio.codec(it) == "truehd" || it in LOSSLESS_CODECS }

    /**
     * Whether this is the immersive Dolby mix.
     *
     * The whole E-AC-3 family, not only the joint-object-coding spelling — and
     * that is the whole point of this property existing separately from
     * [isLossless].
     *
     * A source says "eac3" far more often than it says "eac3-joc": the plain
     * spelling is what a Tidal HLS rendition, an Apple Music entry and most
     * addon backends put in their `codec` field. Only the JOC spellings were
     * recognised here, so an E-AC-3 stream was ranked as ordinary lossy audio,
     * was never refused on a device with no Dolby decoder, and — the part that
     * showed on screen — was only recognised as Dolby once the *decoder* had
     * been configured and reported its own mime type, which is seconds into
     * the track and never at all on a device that cannot decode it.
     *
     * So the family is recognised here, at the moment the source names it, and
     * the decoder's own verdict still has the last word on what is playing
     * (see [com.music.bitchord.data.NerdStats.Snapshot.isDolbyAtmos]).
     *
     * AC-3 is here for the same reason and with the same consequence: it is
     * the same family of Dolby bitstream, the decoder reports it as such, and
     * [com.music.bitchord.data.sources.DeviceCodecs] asks about a decoder for
     * it before any of this refuses a stream.
     */
    val isDolbyAtmos: Boolean
        get() = DolbyAudio.isAtmos(codec)

    /** Any Dolby coding family, including lossless TrueHD/MLP and MAT carriage. */
    val isDolby: Boolean
        get() = DolbyAudio.codec(codec) != null

    /** "24-bit · 192 kHz", "FLAC", "320 kbps" — whichever parts are known. */
    val summary: String
        get() = DolbyAudio.label(codec) ?: listOfNotNull(
            codec?.uppercase(Locale.ROOT),
            bitDepth?.let { "$it-bit" },
            sampleRateHz?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" },
            kbps?.takeIf { isLossless != true }?.let { "$it kbps" },
        ).joinToString(" · ").ifEmpty { "Unknown format" }

    private companion object {
        val LOSSLESS_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff",
            // Dolby TrueHD is MLP, and is lossless unlike the Dolby delivery
            // codecs below it. The Atmos badge still takes summary priority.
            "truehd", "mlp",
        )
    }
}

/**
 * The Dolby delivery family, normalised once for sources and playback alike.
 *
 * Amazon answers AC-4 as `audioQuality=ac4` beside the generic
 * `mimeType=audio/mp4`; Media3 later reports `audio/ac4`. Other paths publish
 * E-AC-3/JOC, AC-3, TrueHD/MLP or Dolby MAT with hyphen, underscore, MIME and
 * file-extension variations. Keeping those spellings here stops the resolver,
 * badge and upgrade loop forming three different opinions of the same bytes.
 */
object DolbyAudio {
    private val EXACT = buildMap {
        fun alias(canonical: String, vararg aliases: String) {
            put(canonical, canonical)
            aliases.forEach { put(it, canonical) }
        }
        alias("ac4", "ac-4", "ac4-ims", "ac_4", "dolby-ac4")
        alias("eac3-joc", "eac3_joc", "ec3-joc", "ec3_joc", "ec-3-joc", "dolby-atmos", "joc")
        alias("eac3", "eac-3", "e-ac-3", "ec3", "ec-3", "ddp", "dd+", "dolby-digital-plus")
        alias("ac3", "ac-3", "dd", "dolby-digital")
        alias("truehd", "true-hd", "mlp", "dolby-truehd")
        alias("mat", "dolby-mat")
    }

    private val PATTERNS = listOf(
        Regex("(?<![a-z0-9])ac-?4(?![a-z0-9])") to "ac4",
        Regex("(?<![a-z0-9])(?:eac3|ec3|ec-3)[_-]?joc(?![a-z0-9])") to "eac3-joc",
        Regex("(?<![a-z0-9])atmos(?![a-z0-9])") to "eac3-joc",
        Regex("(?<![a-z0-9])(?:e-?ac-?3|ec-?3)(?![a-z0-9])") to "eac3",
        Regex("(?<![a-z0-9])ac-?3(?![a-z0-9])") to "ac3",
        Regex("(?<![a-z0-9])(?:true-?hd|mlp)(?![a-z0-9])") to "truehd",
        Regex("(?<![a-z0-9])mat(?![a-z0-9])") to "mat",
    )

    /** Canonical Dolby codec id, or null when [raw] names something else. */
    fun codec(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        EXACT[text]?.let { return it }
        EXACT[text.substringBefore(';').trim().substringAfterLast('/')]?.let { return it }
        return PATTERNS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
    }

    /**
     * Atmos for this app's delivery paths. AC-4 is Amazon's immersive mix.
     * TrueHD/MLP and MAT remain Dolby, but do not claim Atmos on codec alone.
     */
    fun isAtmos(raw: String?): Boolean = codec(raw) in ATMOS_CODECS

    /** The user-facing codec claim, without reducing every Dolby codec to Atmos. */
    fun label(raw: String?): String? = when (codec(raw)) {
        "ac4", "eac3-joc", "eac3", "ac3" -> "Dolby Atmos"
        "truehd" -> "Dolby TrueHD"
        "mat" -> "Dolby MAT"
        else -> null
    }

    private val ATMOS_CODECS = setOf("ac4", "eac3-joc", "eac3", "ac3")

    /** Dolby filename extensions accepted when a source supplies no MIME or codec field. */
    val fileExtensions = setOf(
        "ac4", "ac-4", "eac3-joc", "ec3-joc", "eac3", "eac-3", "ec3", "ec-3",
        "ac3", "ac-3", "truehd", "true-hd", "mlp", "mat",
    )
}
