package com.music.bitchord.playback

import com.music.bitchord.playback.smart.MixWaveform

/**
 * One deck's waveform for the mix popup: the bands decoded for [from]..[to]
 * seconds of [mediaId], off the copy analysis measured ([rendition], its
 * `measuredOn`). Reused only for that same copy: a track can change to a
 * different cut under the same media id, and the old cut's picture would
 * then be drawn against the new plan.
 */
internal class MixDeckWave(
    val mediaId: String,
    val rendition: String?,
    val from: Double,
    val to: Double,
    val bands: MixWaveform.Bands,
) {
    fun covers(id: String, measuredOn: String?, start: Double, end: Double): Boolean =
        id == mediaId && measuredOn == rendition && from <= start + 0.05 && to >= end - 0.05
}
