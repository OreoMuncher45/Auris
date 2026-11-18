package com.music.bitchord.playback

import com.music.bitchord.data.RatingTarget
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Finds the YouTube id that should seed AutoPlay for a song.
 *
 * Spotify rows and source-backed rows do not carry YouTube ids, so they are
 * matched on YouTube before the radio request. The same resolution path rates
 * such a row through the recording behind it, so the radio and the heart
 * always agree on which YouTube recording the row names.
 */
suspend fun youtubeSeedFor(song: Song): String? =
    youtubeSeedFor(song) { RatingTarget.resolve(it) }

/** [youtubeSeedFor], with the row resolution supplied so the radio seed can be tested. */
internal suspend fun youtubeSeedFor(
    song: Song,
    resolve: suspend (Song) -> String?,
): String? {
    if (!RatingTarget.isRowId(song.videoId)) return song.videoId
    return resolve(song)
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): Result<List<Song>> = loadAutoplayTracks(
    existing = existing,
    seedSong = seedSong,
    limit = limit,
    radio = { YtMusicRepository.radio(it) },
    resolve = { RatingTarget.resolve(it) },
)

/** [loadAutoplayTracks], with the catalogue calls supplied so the radio boundary can be tested. */
internal suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int,
    radio: suspend (String) -> Result<List<Song>>,
    resolve: suspend (Song) -> String?,
): Result<List<Song>> {
    // One algorithm, whichever catalogue the listener browses: YouTube
    // Music's radio, seeded from the song that is playing. A Spotify track is
    // matched to something YouTube carries first, so a row that came from
    // Spotify still starts a station that knows what to play next.
    //
    // The seed song is unchanged — a Spotify-provider session still continues
    // from the song that is playing — and the queue-entry mapping below is
    // shared, so the batch lands in the queue looking exactly like any other.
    val seed = youtubeSeedFor(seedSong, resolve) ?: return Result.success(emptyList())
    val related = radio(seed).getOrElse { return Result.failure(it) }
    val extra = QueueBuilder.extend(existing, related, limit)
        .filterNot(com.music.bitchord.data.settings.AppSettings::isSongBlocked)
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}
