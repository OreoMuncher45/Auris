package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A bounded queue snapshot for restoring playback after process death.
 *
 * Queue contents are serialized only when Media3 reports that the playlist
 * changed. The frequently changing index and position are stored separately as
 * primitives, so progress sampling never walks or serializes the song list.
 *
 * The routine writes ([saveQueue], [savePlaybackState]) are encoded and handed
 * to SharedPreferences on one background thread, in the order they were made.
 * They are called from the service's Media3 listener on the main looper, and a
 * queue edit — every neighbour crossed in a drag is one — used to encode up to
 * 76 tracks to JSON there before the next frame could be drawn. The explicit
 * boundaries ([saveQueueImmediately], [clear], [clearImmediately]) stay
 * synchronous; each one moves [generation] on, so a routine write queued before
 * a boundary can never land on top of it.
 */
object LastPlayed {

    class Snapshot(val songs: List<Song>, val index: Int, val positionMs: Long)

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    /** One thread, so routine writes reach prefs in the order they were made. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LastPlayed-writer").apply { isDaemon = true }
    }

    /** Guards [generation] and every write to [prefs]. */
    private val lock = Any()

    /** Moved on by each synchronous boundary; a queued write from before it is dropped. */
    private var generation = 0L

    private fun enqueue(write: () -> Unit) {
        val queuedAt = synchronized(lock) { generation }
        writer.execute {
            synchronized(lock) {
                if (generation == queuedAt) write()
            }
        }
    }

    private inline fun boundary(write: () -> Unit) {
        synchronized(lock) {
            generation++
            write()
        }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun window(size: Int, index: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val safeIndex = index.coerceIn(0, size - 1)
        val start = (safeIndex - MAX_QUEUE_HISTORY).coerceAtLeast(0)
        return start until (safeIndex + 1 + KEEP_AHEAD).coerceAtMost(size)
    }

    /** Persist at most 25 previous entries, the current one, and 50 upcoming entries. */
    fun saveQueue(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) {
            clear()
            return
        }
        val safeIndex = index.coerceIn(songs.indices)
        val bounds = window(songs.size, safeIndex)
        // The Song -> StoredTrack copy stays on the caller's thread: it is cheap,
        // and it means the writer never touches a list the caller may change.
        val stored = StoredQueue(
            tracks = songs.subList(bounds.first, bounds.last + 1).map { StoredTrack.from(it) },
        )
        val storedIndex = safeIndex - bounds.first
        val queuedAt = synchronized(lock) { generation }
        writer.execute {
            val encoded = runCatching {
                json.encodeToString(StoredQueue.serializer(), stored)
            }.getOrNull() ?: return@execute
            synchronized(lock) {
                if (generation != queuedAt) return@execute
                prefs.edit()
                    .putString(KEY_QUEUE, encoded)
                    .putInt(KEY_INDEX, storedIndex)
                    .apply()
            }
        }
    }

    /**
     * Atomically replaces the restart snapshot and waits until it reaches disk.
     * Used at an explicit queue boundary, where restoring the queue from before
     * that boundary would be worse than the small cost of a synchronous write.
     */
    fun saveQueueImmediately(songs: List<Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) {
            clearImmediately()
            return
        }
        val safeIndex = index.coerceIn(songs.indices)
        val bounds = window(songs.size, safeIndex)
        val stored = StoredQueue(
            tracks = songs.subList(bounds.first, bounds.last + 1).map { StoredTrack.from(it) },
        )
        val encoded = runCatching {
            json.encodeToString(StoredQueue.serializer(), stored)
        }.getOrNull() ?: return
        boundary {
            prefs.edit()
                .putString(KEY_QUEUE, encoded)
                .putInt(KEY_INDEX, safeIndex - bounds.first)
                .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
                .commit()
        }
    }

    /** Update only cheap scalar state; this performs no queue conversion or JSON work. */
    fun savePlaybackState(index: Int, positionMs: Long) = enqueue {
        // Read on the writer, behind any queue write made just before this one,
        // so the first save of a new queue is not skipped for want of KEY_QUEUE.
        if (!prefs.contains(KEY_QUEUE)) return@enqueue
        prefs.edit()
            .putInt(KEY_INDEX, index.coerceAtLeast(0))
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun load(): Snapshot? {
        // Let any routine write already queued land first, so a load straight
        // after a save sees it. Normally empty: load runs at a cold start.
        runCatching { writer.submit {}.get() }
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val stored = runCatching { json.decodeFromString<StoredQueue>(raw) }.getOrNull()
            ?: return null
        if (stored.tracks.isEmpty()) return null
        val songs = stored.tracks.map(StoredTrack::toSong)
        val index = if (prefs.contains(KEY_INDEX)) {
            prefs.getInt(KEY_INDEX, 0)
        } else {
            stored.legacyIndex ?: 0
        }
        val positionMs = if (prefs.contains(KEY_POSITION)) {
            prefs.getLong(KEY_POSITION, 0L)
        } else {
            stored.legacyPositionMs ?: 0L
        }
        return Snapshot(
            songs = songs,
            index = index.coerceIn(songs.indices),
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }

    fun clear() = boundary {
        prefs.edit().clear().apply()
    }

    /** Remove a stale queue before another one is installed. */
    fun clearImmediately() = boundary {
        prefs.edit().clear().commit()
    }

    @Serializable
    private data class StoredQueue(
        val tracks: List<StoredTrack>,
        /** Compatibility with queue snapshots written before scalar state was separated. */
        val index: Int? = null,
        /** Compatibility with queue snapshots written before scalar state was separated. */
        val positionMs: Long? = null,
    ) {
        val legacyIndex: Int? get() = index
        val legacyPositionMs: Long? get() = positionMs
    }

    @Serializable
    private data class StoredTrack(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String? = null,
        val auto: Boolean = false,
        val tier: String? = null,
        val entryId: String? = null,
        val local: String? = null,
        val path: String? = null,
        val duration: String? = null,
        val album: String? = null,
        val explicit: Boolean? = null,
        val video: Boolean = false,
        val radio: String? = null,
        val source: String? = null,
        val sourceType: String? = null,
        val sourceId: String? = null,
    ) {
        fun toSong(): Song {
            val resolvedTier = when (tier) {
                "USER_QUEUE" -> com.music.bitchord.data.model.QueueTier.USER_QUEUE
                "CONTEXT" -> com.music.bitchord.data.model.QueueTier.CONTEXT
                "AUTOPLAY" -> com.music.bitchord.data.model.QueueTier.AUTOPLAY
                else -> if (auto) com.music.bitchord.data.model.QueueTier.AUTOPLAY else com.music.bitchord.data.model.QueueTier.CONTEXT
            }
            return Song(
                videoId = id,
                title = title,
                artist = artist,
                thumbnailUrl = artwork,
                durationText = duration,
                albumName = album,
                isExplicit = explicit,
                isVideo = video,
                queueTier = resolvedTier,
                queueEntryId = entryId,
                radioName = radio,
                playbackSource = source,
                playbackSourceType = sourceType?.let {
                    runCatching { com.music.bitchord.data.model.PlaybackSourceType.valueOf(it) }.getOrNull()
                },
                playbackSourceId = sourceId,
                localUri = local,
                localPath = path,
            )
        }

        companion object {
            fun from(song: Song) = StoredTrack(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                artwork = song.thumbnailUrl,
                auto = song.fromAutoplay,
                tier = song.queueTier.name,
                entryId = song.queueEntryId,
                local = song.localUri,
                path = song.localPath,
                duration = song.durationText,
                album = song.albumName,
                explicit = song.isExplicit,
                video = song.isVideo,
                radio = song.radioName,
                source = song.playbackSource,
                sourceType = song.playbackSourceType?.name,
                sourceId = song.playbackSourceId,
            )
        }
    }

    private const val KEEP_AHEAD = 50
    private const val PREFS_NAME = "bitchord_last_played"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
}
