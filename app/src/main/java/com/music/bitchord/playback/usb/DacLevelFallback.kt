package com.music.bitchord.playback.usb

/**
 * What a USB output does when writing the listener's level to the DAC's
 * Feature Unit fails. The app's software gain can only scale on top of
 * whatever the DAC is left at, so the DAC goes back to its own base level
 * first; if even that write fails, the hardware path stays and the level is
 * tried again, rather than leaving a faded level on the DAC under the
 * restored software gain.
 */
internal object DacLevelFallback {
    enum class Outcome {
        /** The DAC is back at its base level: software gain from here. */
        SOFTWARE_FROM_BASE,

        /** The DAC took nothing: keep the hardware path and write the level again. */
        RETRY_HARDWARE,
    }

    fun afterFailedWrite(baseDb: Double, setDb: (Double) -> Boolean): Outcome =
        if (setDb(baseDb)) Outcome.SOFTWARE_FROM_BASE else Outcome.RETRY_HARDWARE
}
