package com.music.bitchord.download

import android.content.Context
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
import java.util.concurrent.ConcurrentHashMap

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
 * ## Why it has to be told when to look
 *
 * A song added to a playlist is *appended* to it, so it sits past the first
 * page of a hundred rows — finding it means following the playlist's
 * continuations to the end. That walk is a handful of requests per playlist,
 * and doing it whenever the app so much as started would be a machine
 * hammering YouTube's API rather than a person using an app. So:
 *
 *  - The walk is throttled by a cooldown that is **written down**, not held in
 *    memory, so a cold start does not re-walk every playlist the listener
 *    owns. The old in-memory timer reset on every process start, which meant
 *    every launch paid for a full walk of everything.
 *  - The walk is also *event-driven*: [refresh] is called the moment the app
 *    itself changes a playlist, so the common case — the listener adds a song
 *    in Auris and expects it to land on the device — does not wait for a
 *    timer at all.
 *
 * ## What it does not do
 *
 * It never deletes. A song taken *out* of a playlist stays on the device: the
 * listener asked for that file to be here, and a playlist's contents are not
 * the listener's filing system. Only additions are acted on.
 *
 * It never runs offline. A sync is a network walk, and the whole point of
 * the offline switch is that nothing goes out — see
 * [com.music.bitchord.data.NetworkPolicy], which is where that promise is
 * kept.
 */
object DownloadedPlaylistSync {

    private const val TAG = "BitChord"

    /** Where the cooldown is written down, so it survives the process. */
    private const val PREFS = "bitchord_playlist_sync"
    private const val KEY_LAST_PASS = "last_pass_at"

    /**
     * How long between passes.
     *
     * Long enough that a listener who opens the app twenty times a day is
     * not walked twenty times, and short enough that a song liked this
     * morning is on the device by the evening.
     */
    private const val PERIOD_MS = 30 * 60 * 1000L

    /**
     * How long one playlist is left alone after it has been looked at.
     *
     * The walk to the end of a playlist is a handful of requests, so it is not
     * something to do every time a page is opened. The global pass picks the
     * rest up within [PERIOD_MS].
     */
    private const val REFRESH_COOLDOWN_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One pass at a time: two walks would fetch the same pages twice. */
    private val mutex = Mutex()

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
                if (!passDue(app)) return@withLock
                markPass(app)
                runCatching { sync(app) }
                    .onFailure { Log.w(TAG, "the downloaded-playlist sync failed: ${it.message}") }
            }
        }
    }

    /**
     * Looks at one playlist now, because the app knows it changed.
     *
     * Skips the *global* cooldown: the caller is not guessing — it has just
     * added a track, or opened a page whose contents moved — and waiting half
     * an hour for a thing that already happened is what made this feel broken.
     *
     * A playlist that was never downloaded whole is not walked: there is no
     * promise to keep, and fetching it would be a request for nothing.
     *
     * [force] is for the caller that knows the playlist changed *just now* —
     * a track added through the app's own picker. A page merely being opened
     * is not that: a listener flicking between playlists must not turn into a
     * machine walking each one to its end, so an unforced refresh is held to
     * [REFRESH_COOLDOWN_MS] per playlist.
     */
    fun refresh(context: Context, playlistId: String, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            if (AppSettings.effectiveOffline.value) return@launch
            if (!force && !refreshDue(playlistId)) return@launch
            mutex.withLock {
                if (!force && !refreshDue(playlistId)) return@withLock
                markRefresh(playlistId)
                runCatching { sync(app, only = playlistId) }
                    .onFailure { Log.w(TAG, "the playlist refresh failed: ${it.message}") }
            }
        }
    }

    /** When each playlist was last looked at, so one is not walked twice. */
    private val refreshedAt = ConcurrentHashMap<String, Long>()

    /** Whether this playlist was looked at recently enough to skip it. */
    private fun refreshDue(playlistId: String): Boolean {
        val last = refreshedAt[playlistId] ?: return true
        return System.currentTimeMillis() - last >= REFRESH_COOLDOWN_MS
    }

    /** Writes down that this playlist was just looked at. */
    private fun markRefresh(playlistId: String) {
        refreshedAt[playlistId] = System.currentTimeMillis()
    }

    /**
     * The pass itself: every downloaded release, looked at, and what is
     * missing taken.
     *
     * @param only one playlist to look at, or null for all of them.
     */
    suspend fun sync(context: Context, only: String? = null) {
        // The index is read off disk on a background thread, and until it is
        // there the app answers "nothing has been downloaded" — which is
        // exactly the answer that makes this pass a no-op. So the pass waits
        // for the answer it is going to act on.
        Downloads.awaitIndexReady()
        val collections = Downloads.savedReleases()
            .filter { only == null || it.id == only }
        if (collections.isEmpty()) return
        // What is really on the disk, not what the record claims: a file
        // deleted from a file manager leaves its record behind, and a track
        // whose file is gone must be fetched again rather than counted as
        // already here.
        val onDisk = Downloads.getDownloadedSongs(context).map { it.videoId }.toSet()
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

    /** Whether the last pass was long enough ago to do another one. */
    private fun passDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_PASS, 0L)
        return System.currentTimeMillis() - last >= PERIOD_MS
    }

    /** Writes down that a pass has just happened, so the next one waits. */
    private fun markPass(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PASS, System.currentTimeMillis())
            .apply()
    }

    /** Whether this song is a file of the listener's own rather than a track to fetch. */
    private fun Song.isDeviceFile(): Boolean =
        videoId.startsWith("file://") || videoId.startsWith("content://")
}
