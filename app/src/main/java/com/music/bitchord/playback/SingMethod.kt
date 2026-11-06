package com.music.bitchord.playback

/**
 * The vocal removers Sing has shipped, kept side by side because they fail in
 * opposite directions and there is no single right answer for every record.
 *
 * All three read the same slider; they differ in what they refuse to damage.
 */
enum class SingMethod {
    /**
     * The original, restored from v2.1.2 (`8c93099b`).
     *
     * Full-spectrum centre cut with makeup gain — the hardware karaoke box, and
     * the fastest of the three: nothing is filtered, so there is no phase shift
     * and no ring. It also cuts everything panned centre, which on a modern
     * mix means the kick, the bass and the snare. Correct for a track recorded
     * like 1975, muddy for anything with a drum kit in it.
     */
    ORIGINAL,

    /**
     * Band-limited cut with transient bypass, as shipped in v2.1.11
     * (`7b146fe6`).
     *
     * Cues only the 150 Hz–10 kHz band and ducks out of the way for drum
     * transients, so bass and air survive. The mid band is computed as a
     * subtraction residue rather than a true bandpass, which leaves faint phase
     * ghosts across the band — audible as a slight thinning, and the reason the
     * next method exists.
     */
    BAND_LIMITED,

    /**
     * The current engine, since v2.1.12 (`f8ae56d8`).
     *
     * The same band-limited split with the mid rebuilt as a real bandpass, a
     * gradual crest trigger instead of a hard switch, a voice-activity gate so
     * instrumental passages are left alone, and side makeup to replace the
     * energy the cut removes. The most moving parts, and the only one of the
     * three that lets a solo through untouched.
     *
     * No longer offered in the sheet — Studio has taken its place — but kept
     * here because an install that chose it keeps running it until the
     * listener picks something else, and because the engine behind it is
     * still the one that handles a mono track.
     */
    VOICE_GATE,

    /**
     * Studio: a neural separator that takes the lead vocal out with a model
     * that has learned what a voice is.
     *
     * The three filters above look for the vocal in the middle of the stereo
     * image and take out whatever is there. This one is taught: it hears the
     * song and writes back the part that is singing, and the song is played
     * without it. Nothing else in the chain can do that — a snare and a vocal
     * sit in the same place, and only the network knows which is which.
     *
     * The model is not part of the app: it is downloaded from Settings, and
     * until one is on the phone there is nothing for this to run on.
     */
    NEURAL,
    ;

    companion object {
        fun fromName(value: String?): SingMethod =
            entries.firstOrNull { it.name == value } ?: VOICE_GATE

        /**
         * The methods the Sing sheet offers, in the order it shows them.
         *
         * Studio stands where Smart used to: it is the one that does the job
         * properly, and the two filters beside it are there for the songs it
         * has not been asked about yet.
         */
        val offered: List<SingMethod> = listOf(ORIGINAL, BAND_LIMITED, NEURAL)

        /**
         * Whether Studio is being asked to separate anything right now.
         *
         * The method alone is not enough: the slider says how much voice to
         * take out, and a slider at zero asks for no voice to be taken out.
         * A model left open for a method that is asking for nothing is a
         * phone running at full speed for no reason -- ONNX Runtime's
         * threads spin rather than sleep while a session is open.
         *
         * This is what decides whether the model is opened at all.
         */
        fun studioAskedFor(method: SingMethod, level: Float): Boolean =
            method == NEURAL && level > 0.001f
    }
}

/** The one shape all three engines answer to, so the processor can swap them. */
interface SingEngine {
    fun configure(sampleRate: Int)
    fun processSpan(samples: FloatArray, sampleCount: Int, cut: Float)
    fun processFrame(left: Float, right: Float, cut: Float): FloatArray
    fun reset()
}

internal fun engineFor(method: SingMethod): SingEngine = when (method) {
    SingMethod.ORIGINAL -> VocalCutOriginal()
    SingMethod.BAND_LIMITED -> VocalCutBandLimited()
    // Studio is not a filter: it is a network, and it runs in its own
    // processor further down the chain. The filter that stands in for it
    // here does nothing at all.
    SingMethod.VOICE_GATE, SingMethod.NEURAL -> VocalCutDsp()
}
