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
 * ## The switch is per playlist
 *
 * Downloading a playlist is what turns this on for it — see
 * [SavedCollection.autoDownload], which is written with the record and shown as
 * a switch on the playlist's own page. A playlist with the switch off is not
 * walked at all: the listener said "these files, not this feed", and a sync
 * that went looking anyway would be answering a question they had already
 * answered. Switching it back on fetches everything added in the meantime,
 * because the walk compares the playlist with the disk rather than with a
 * memory of where it left off.
 *
 * Liked Music is the same thing under another id: `VLLM` is the auto-playlist
 * the Liked Songs page *is*, and downloading it records a collection under
 * that id like any other, so the newest like is picked up by exactly the same
 * walk.
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
 * It never brings back a track the listener deleted by hand. That deletion is
 * the more specific instruction and it was made later, so it outranks this
 * path — see [Downloads.deletedByHand] and [missingFromPlaylist].
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
     * Long enough that a listener who opens the app twenty times a day is not
     * walked twenty times, and short enough that a song added this morning is
     * on the device by the time they look. It used to be half an hour, which
     * with a persisted clock meant a song added just after a pass waited until
     * the next one — the whole of the "new songs never arrive" complaint.
     */
    private const val PERIOD_MS = 5 * 60 * 1000L

    /**
     * How long one playlist is left alone after it has been looked at.
     *
     * The walk to the end of a playlist is a handful of requests, so it is not
     * something to do every time a page is opened. The global pass picks the
     * rest up within [PERIOD_MS].
     */
    private const val REFRESH_COOLDOWN_MS = 2 * 60 * 1000L

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
     * promise to keep, and fetching it would be a request for nothing. Neither
     * is one whose switch is off — see [Downloads.syncableCollections].
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
            // Wi-Fi only on mobile data: [Downloads.enqueueAll] would refuse
            // every track, and the pass would still write them into the
            // record as asked for, so the next pass on Wi-Fi took them for
            // handled and they never arrived. Left for that pass instead.
            if (!AppSettings.downloadsAllowedNow) return@launch
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
     * The pass itself: every downloaded playlist whose switch is on, looked
     * at, and what is missing taken.
     *
     * @param only one playlist to look at, or null for all of them.
     */
    suspend fun sync(context: Context, only: String? = null) {
        // The index is read off disk on a background thread, and until it is
        // there the app answers "nothing has been downloaded" — which is
        // exactly the answer that makes this pass a no-op. So the pass waits
        // for the answer it is going to act on.
        Downloads.awaitIndexReady()
        val collections = Downloads.syncableCollections()
            .filter { only == null || samePlaylist(it.id, only) }
        if (collections.isEmpty()) return
        // What is really on the disk, not what the record claims: a file
        // deleted from a file manager leaves its record behind, and a track
        // whose file is gone must be fetched again rather than counted as
        // already here.
        val onDisk = Downloads.getDownloadedSongs(context).map { it.videoId }.toSet()
        // The tracks the listener deleted by hand. They are not missing — they
        // were asked for and taken away again — and a sync that put them back
        // would be undoing a decision the listener made on purpose.
        val deleted = Downloads.deletedByHandIds()
        for (collection in collections) {
            val result = YtMusicRepository.browseSongsPaged(collection.id)
            val remote = result.getOrNull()
            if (remote.isNullOrEmpty()) {
                // Named, not swallowed: an empty answer and a failed request
                // both mean "nothing to do", and the difference is the whole
                // of whether the next report can be acted on.
                Log.w(
                    TAG,
                    "could not read ${collection.title}: " +
                        (result.exceptionOrNull()?.message ?: "the playlist is empty"),
                )
                continue
            }
            val missing = missingFromPlaylist(remote, onDisk, deleted, collection.videoIds.toSet())
            if (missing.isEmpty()) continue
            Log.i(TAG, "${collection.title}: ${missing.size} new track(s) to download")
            Downloads.markRequested(collection.id, missing.map { it.videoId })
            Downloads.enqueueAll(context, missing, collection.title, automatic = true)
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
}

/**
 * The tracks of [remote] that are not on the device yet and may be fetched.
 *
 * Three rules, and each one is a complaint:
 *
 *  - A track already on the disk is not fetched again.
 *  - A track of the listener's own is not fetched at all: their own music is
 *    already on the device, which is what "local" means.
 *  - A track they deleted by hand is never fetched again, however clearly the
 *    playlist still names it. The deletion is the more specific instruction
 *    and it came later, so it wins over "this playlist is downloaded whole".
 *
 * What the collection already asked for also counts, and this is the rule
 * that stopped the queue refilling itself: the on-disk set is a projection,
 * and it is not complete. A cold start can read it before the index is ready,
 * and a file saved under a fetched id does not necessarily list the id the
 * playlist asked for. Judged on disk alone, every one of those tracks looked
 * new again on the next launch, so completed downloads — and ones the
 * listener had cleared from the queue — were re-enqueued on every restart.
 * [alreadyRequested] is the collection's own record, which survives a restart
 * and says these were already taken care of. A hand-deleted track is still
 * covered by [deletedByHand], which is the more specific instruction.
 *
 * Split out as plain arguments so the rules can be tested without a device, a
 * network or a playlist: they are the whole of what makes this correct.
 */
internal fun missingFromPlaylist(
    remote: List<Song>,
    onDisk: Set<String>,
    deletedByHand: Set<String>,
    alreadyRequested: Set<String> = emptySet(),
): List<Song> = remote.filter { song ->
    song.videoId !in onDisk &&
        song.videoId !in deletedByHand &&
        song.videoId !in alreadyRequested &&
        !song.isDeviceFile()
}

/** Whether two ids name the same playlist, with or without YouTube's `VL`. */
internal fun samePlaylist(a: String, b: String): Boolean = a.removePrefix("VL") == b.removePrefix("VL")

/** Whether this song is a file of the listener's own rather than a track to fetch. */
internal fun Song.isDeviceFile(): Boolean =
    videoId.startsWith("file://") || videoId.startsWith("content://")
