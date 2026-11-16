package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the songs the listener plays.
 *
 * A stream is a thing that can be gone by the time they want it again: the
 * connection drops, the source refuses, the track is taken down. A song they
 * have actually listened to is a song they will want later, so it is saved by
 * itself — no tap, no release page, no remembering to do it.
 *
 * ## What it refuses to do
 *
 *  - **It does not fetch a track the listener deleted.** A deletion is the
 *    more specific instruction and it was made later, so it wins over every
 *    automatic path — this one and the downloaded-playlist sync alike. See
 *    [Downloads.deletedByHand].
 *  - **It does not fetch what is already here.** A track that is on the
 *    device is on the device.
 *  - **It does not fetch over a connection the listener refused.** The
 *    Wi-Fi-only switch and offline mode are read first, and nothing is
 *    queued when they say no — a queue that waits for a better connection is
 *    a queue the listener did not ask for.
 *  - **It does not fetch a file of the listener's own.** Their own music is
 *    already on the device; that is what "local" means.
 *
 * ## It waits before it fetches
 *
 * A track that was skipped after two seconds was not listened to, and saving
 * it spends data and space for nothing. So the decision is made a little
 * while after the track starts, and only if it is *still* the one playing.
 */
object AutoDownload {

    private const val TAG = "BitChord"

    /**
     * How long a track has to be playing before it is worth keeping.
     *
     * Short enough that the file is there before the listener has finished
     * with the track, long enough that a skip does not count as a listen.
     */
    const val CONFIRM_MS = 20_000L

    /**
     * Whether a track that is playing should be downloaded.
     *
     * Split out as plain arguments so the answer can be tested without a
     * device, a player or a network — the rules above are the whole of it,
     * and they are the part that has to be right.
     */
    fun shouldDownload(
        enabled: Boolean,
        downloadsAllowed: Boolean,
        alreadySaved: Boolean,
        deletedByHand: Boolean,
        localFile: Boolean,
    ): Boolean = enabled &&
        downloadsAllowed &&
        !alreadySaved &&
        !deletedByHand &&
        !localFile

    /**
     * Queues [song] for download if the rules allow it.
     *
     * Called when the track has been playing long enough to count as
     * listened to. Returns whether it was queued.
     */
    fun download(context: Context, song: Song): Boolean {
        val id = song.videoId
        if (id.isBlank()) return false
        val allowed = shouldDownload(
            enabled = AppSettings.autoDownloadSongs.value,
            downloadsAllowed = AppSettings.downloadsAllowedNow,
            alreadySaved = Downloads.saved.value.containsKey(id),
            deletedByHand = Downloads.deletedByHand(id),
            localFile = song.isOwnFile(),
        )
        if (!allowed) return false
        Log.i(TAG, "keeping $id because it is playing")
        // Automatic, so it never clears the listener's own mark: see [enqueue].
        Downloads.enqueue(context, song, from = null, automatic = true)
        return true
    }

    /**
     * Waits [CONFIRM_MS] and downloads [song] if it is still the one playing.
     *
     * [stillPlaying] is asked when the wait is over, not before it: the point
     * of the wait is that the track was listened to, and a track that was
     * skipped in the meantime was not.
     */
    fun confirmAndDownload(
        context: Context,
        scope: CoroutineScope,
        song: Song,
        stillPlaying: () -> Boolean,
    ): Job = scope.launch {
        delay(CONFIRM_MS)
        if (!stillPlaying()) return@launch
        runCatching { download(context, song) }
            .onFailure { Log.w(TAG, "the automatic download of ${song.videoId} failed: ${it.message}") }
    }

    /** Whether this track is a file of the listener's own. */
    private fun Song.isOwnFile(): Boolean =
        videoId.startsWith("file://") || videoId.startsWith("content://")
}
