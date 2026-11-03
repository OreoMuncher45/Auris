package com.music.bitchord.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a downloaded playlist whole.
 *
 * A playlist downloaded whole is a promise about a *moving* thing. The
 * listener likes another song, or adds one to a playlist, and the copy on the
 * device is now short of what the playlist is. Nothing noticed, because
 * nothing looked: the download was a one-off event, and the record it left
 * behind said which tracks it had — not that it should go on having them.
 *
 * So this looks, and takes what is missing.
 *
 * Liked Music is the same thing under another id: `VLLM` is the auto-playlist
 * the Liked Songs page *is*, and downloading it records a collection under
 * that id like any other, so the newest like is picked up by exactly the
 * same walk.
 *
 * ## What it does not do
 *
 * It never deletes. A song taken *out* of a playlist stays on the device: the
 * listener asked for that file to be here, and a playlist's contents are not
 * the listener's filing system. Only additions are acted on.
 *
 * It never runs offline. A sync is a network walk, and the whole point of the
 * offline switch is that nothing goes out — see
 * [com.music.bitchord.data.NetworkPolicy], which is where that promise is
 * kept.
 */
object DownloadedPlaylistSync {

    private const val TAG = "BitChord"

    /**
     * How long between passes.
     *
     * Long enough that a listener who opens the app twenty times a day is
     * not walked twenty times, and short enough that a song liked this
     * morning is on the device by the evening.
     */
    private const val PERIOD_MS = 2 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One pass at a time: two walks would fetch the same pages twice. */
    private val mutex = Mutex()

    /** When the last pass ran, from [SystemClock.elapsedRealtime]. */
    @Volatile
    private var lastRunAt = 0L

    /**
     * Runs a pass, if one is due and the device is allowed to talk to the
     * network.
     *
     * Cheap to call from anywhere: the throttling and the offline check are
     * both here, so no caller has to remember them.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (AppSettings.effectiveOffline.value) return@launch
            if (!AppSettings.downloadsAllowedNow) return@launch
            mutex.withLock {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRunAt < PERIOD_MS) return@withLock
                lastRunAt = now
                runCatching { sync(app) }
                    .onFailure { Log.w(TAG, "the downloaded-playlist sync failed: ${it.message}") }
            }
        }
    }

    /**
     * The pass itself: every downloaded release, looked at, and what is
     * missing taken.
     */
    suspend fun sync(context: Context) {
        val collections = Downloads.savedReleases()
        if (collections.isEmpty()) return
        val onDisk = Downloads.saved.value
        for (collection in collections) {
            val remote = YtMusicRepository.browseSongsPaged(collection.id).getOrNull()
            if (remote.isNullOrEmpty()) continue
            val known = collection.videoIds.toSet()
            val missing = remote.filter { song ->
                song.videoId !in known && song.videoId !in onDisk && !song.isDeviceFile()
            }
            if (missing.isEmpty()) continue
            Log.i(
                TAG,
                "${collection.title}: ${missing.size} new track(s) to download",
            )
            Downloads.markRequested(collection.id, missing.map { it.videoId })
            Downloads.enqueueAll(context, missing, collection.title)
            // The record grows with what was asked for, so the next pass knows
            // these are not new any more — whether or not the download itself
            // finished.
            Downloads.rememberCollection(
                DownloadTarget(
                    id = collection.id,
                    title = collection.title,
                    subtitle = collection.subtitle,
                    thumbnailUrl = collection.thumbnailUrl,
                    playlist = collection.playlist,
                ),
                missing,
            )
        }
    }

    /** Whether this song is a file of the listener's own rather than a track to fetch. */
    private fun Song.isDeviceFile(): Boolean =
        videoId.startsWith("file://") || videoId.startsWith("content://")
}
