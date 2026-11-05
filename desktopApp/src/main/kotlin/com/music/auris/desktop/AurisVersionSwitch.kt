package com.music.auris.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song

/**
 * Which recording of a track is playing — the phone's version switch, on the desktop.
 *
 * A row can be a video upload with the catalogue's own song behind it, or the catalogue's
 * song with a music video behind it. The phone lets the listener pick between the two from
 * the player's menu, and this is that pick: it asks the same catalogue the phone asks, and
 * it answers the same way.
 *
 * The catalogue is asked only when the listener asks. A video plays its own audio by
 * default, because a video upload can be another recording altogether — see
 * [YtMusicRepository.resolveAudio].
 */
internal object AurisVersionSwitch {

    /**
     * A track that was swapped for its other version, and the row it was swapped from.
     *
     * The way back: the phone keeps the original MediaItem in the queue so the menu can
     * offer the version that was left behind, and this is the desktop's copy of that.
     */
    data class Swap(val from: Song, val to: Song)

    /** The catalogue's own audio row for [song], or [song] itself when there is none. */
    suspend fun toAudio(song: Song): Song =
        runCatching { YtMusicRepository.resolveAudio(song) }.getOrDefault(song)

    /** The music video for [song], or null when the catalogue has none. */
    suspend fun toVideo(song: Song): Song? =
        runCatching { YtMusicRepository.resolveVideo(song) }.getOrNull()

    /** Whether the other version of [song] exists at all. */
    suspend fun hasAlternate(song: Song): Boolean =
        if (song.isVideo) toAudio(song).videoId != song.videoId else toVideo(song) != null

    /**
     * The other recording of [song], or null when there is none.
     *
     * A track that was already swapped goes back to the row it came from — the phone's
     * `keepVideoId` / `convertedFromAudio` path — and anything else is looked up afresh:
     * the catalogue's audio row for a video, the music video for a song.
     */
    suspend fun theOtherVersion(song: Song, swap: Swap?): Song? {
        if (swap != null && swap.to.videoId == song.videoId) return swap.from
        return if (song.isVideo) {
            toAudio(song).takeIf { it.videoId != song.videoId }
        } else {
            toVideo(song)
        }
    }
}
