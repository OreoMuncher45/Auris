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
        get() = codec?.let { it in LOSSLESS_CODECS }

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
        get() = codec in DOLBY_ATMOS_CODECS

    /** "24-bit · 192 kHz", "FLAC", "320 kbps" — whichever parts are known. */
    val summary: String
        get() = if (isDolbyAtmos) "Dolby Atmos" else listOfNotNull(
            codec?.uppercase(Locale.ROOT),
            bitDepth?.let { "$it-bit" },
            sampleRateHz?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" },
            kbps?.takeIf { isLossless != true }?.let { "$it kbps" },
        ).joinToString(" · ").ifEmpty { "Unknown format" }

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")

        /**
         * Every spelling of the Dolby family that a source may use.
         *
         * The JOC spellings name the immersive mix outright; the plain ones
         * name the codec that carries it, with or without the object layer.
         * Both are Dolby, both are refused on a device that cannot decode them,
         * and both are badged as what they are — see [isDolbyAtmos].
         */
        val DOLBY_ATMOS_CODECS = setOf(
            "eac3-joc", "ec3-joc", "ec-3-joc", "ec3_joc", "eac3_joc", "dolby-atmos",
            "eac3", "eac-3", "ec-3", "ec3",
            "ac-3", "ac3",
        )
    }
}
