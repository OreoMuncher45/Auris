package com.decent.usbaudio

/**
 * Gives a DAC's audio interfaces back to Android's own USB audio driver.
 *
 * Claiming an interface with `force = true` detaches the kernel driver
 * (snd-usb-audio) from it, and Android loses its USB audio output. Releasing
 * the claim, or closing the connection, does not bring that driver back:
 * Linux only rebinds on an explicit `USBDEVFS_CONNECT`. Without this step a
 * direct session that ends or fails leaves the earphones with no driver, and
 * the phone plays through its speaker.
 *
 * Order matters. snd-usb-audio binds through the AudioControl interface and
 * then claims the streaming interfaces itself, so every claim of ours is
 * released first and AudioControl is reattached before the rest.
 */
internal object KernelDriverHandback {

    /** One USB interface, as far as the handback needs to know it. */
    data class Interface(val number: Int, val audioSubclass: Int)

    /** The two usbdevfs requests the handback makes, so a test can stand in. */
    interface Port {
        /** Drops this app's claim on [number]. */
        fun release(number: Int): Boolean

        /** Asks the kernel to bind a driver to [number]; 0 on success. */
        fun connect(number: Int): Int
    }

    /** What happened, for the log. */
    data class Result(val released: List<Int>, val connected: List<Int>, val failed: Map<Int, Int>)

    const val SUBCLASS_AUDIO_CONTROL = 1

    /**
     * The interfaces to reattach, in the order to reattach them: AudioControl
     * first, then the rest, each interface number once.
     */
    fun reattachOrder(audioInterfaces: List<Interface>): List<Int> =
        audioInterfaces
            .sortedBy { if (it.audioSubclass == SUBCLASS_AUDIO_CONTROL) 0 else 1 }
            .map { it.number }
            .distinct()

    /**
     * Releases every [claimed] interface, then reattaches every audio
     * interface. A failed step never stops the ones after it: a streaming
     * interface that snd-usb-audio already took back answers EBUSY, which is
     * the outcome wanted.
     */
    fun handBack(claimed: Collection<Int>, audioInterfaces: List<Interface>, port: Port): Result {
        val released = claimed.distinct().filter { runCatching { port.release(it) }.getOrDefault(false) }
        val connected = mutableListOf<Int>()
        val failed = linkedMapOf<Int, Int>()
        for (number in reattachOrder(audioInterfaces)) {
            val code = runCatching { port.connect(number) }.getOrDefault(Int.MIN_VALUE)
            if (code == 0) connected += number else failed[number] = code
        }
        return Result(released, connected, failed)
    }
}
