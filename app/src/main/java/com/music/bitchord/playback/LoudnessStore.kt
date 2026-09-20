package com.music.bitchord.playback

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Measured loudness gains kept on disk, so a track is levelled correctly from
 * its first frame on every play after the first.
 *
 * ## Why it is worth keeping
 *
 * A gain measured live can only arrive once enough of the track has been
 * heard — about three seconds — so a first play opens at the recording's own
 * level and glides to the corrected one. That is acceptable once. It is not
 * acceptable every time someone replays a song, and it is precisely what a
 * stored figure removes: [LoudnessProcessor.prime] hands the cached value in
 * before a frame is rendered and the meter never runs at all.
 *
 * ## Shape
 *
 * One small JSON map rather than [com.music.bitchord.playback.smart.AnalysisStore]'s
 * file-per-track. The two stores hold very different things: an analysis is
 * kilobytes of curves and candidates, where an entry here is a track id and a
 * number, and thousands of those still fit in a file small enough to read in
 * one go at startup. A file each would spend an inode and a filesystem round
 * trip per track to store eight bytes.
 *
 * Keyed by track id, which is deliberately not a rendition key. The whole
 * point is that a gain survives the track changing rendition underneath it: a
 * mid-track upgrade from YouTube Opus to a JioSaavn or addon FLAC is the same
 * recording at the same mastered level, and a lossy encode does not move
 * integrated loudness by an amount anyone can hear. Keying on the stream would
 * throw the measurement away at exactly the moment it is most needed.
 */
class LoudnessStore(private val context: Context) {

    /**
     * Resolved on first use rather than at construction, for the reason
     * [com.music.bitchord.playback.smart.AnalysisStore] gives: this is a field
     * initializer on the playback service, which runs before the service has a
     * base context attached.
     */
    private val file by lazy { File(context.filesDir, FILE_NAME) }

    private val json = Json { ignoreUnknownKeys = true }

    private val gains = ConcurrentHashMap<String, Float>()

    /** Whether [gains] has been filled from disk yet. */
    @Volatile
    private var loaded = false

    /** Entries written since the last save, so a quiet session does no IO. */
    @Volatile
    private var dirty = false

    /**
     * Reads the map in, so the first [gainDbFor] does not.
     *
     * Worth calling from a background context at startup: [gainDbFor] is asked
     * on the main thread at every track change, and without this the first of
     * those would do the file read.
     */
    fun preload() {
        ensureLoaded()
    }

    /** The stored gain for [trackId] in dB, or null if it has never been measured. */
    fun gainDbFor(trackId: String): Float? {
        if (trackId.isBlank()) return null
        ensureLoaded()
        return gains[trackId]
    }

    /**
     * Records [gainDb] for [trackId].
     *
     * Overwrites without hesitation. A measurement refines as a track plays —
     * thirty seconds of it describes the recording better than three do — so
     * the last figure offered for a track is the best one heard, and the point
     * of storing it is for the next play to start where this one finished.
     *
     * Ignores a gain that has not moved enough to be worth a write. The
     * service offers one of these on every progress tick, and rewriting the
     * file to change a number by a hundredth of a decibel would be the
     * feature's entire IO cost for no audible difference.
     */
    fun record(trackId: String, gainDb: Float) {
        if (trackId.isBlank() || !gainDb.isFinite()) return
        ensureLoaded()
        val existing = gains[trackId]
        if (existing != null && kotlin.math.abs(existing - gainDb) < WORTH_REWRITING_DB) return
        gains[trackId] = gainDb
        dirty = true
    }

    /**
     * Writes the map out if anything has changed.
     *
     * Called from a background context on a cadence the caller chooses rather
     * than on every [record], because the service records continuously while a
     * track plays and each write is the whole file.
     */
    fun flush() {
        if (!dirty) return
        dirty = false
        runCatching {
            prune()
            // Written aside and renamed, so being killed mid-write leaves the
            // previous map rather than a truncated one.
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(SERIALIZER, gains.toMap()))
            if (!temporary.renameTo(file)) temporary.delete()
        }.onFailure {
            Log.w(TAG, "Could not store loudness gains", it)
            // Left dirty so the next flush tries again rather than silently
            // dropping the session's measurements.
            dirty = true
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            if (!file.exists()) return
            runCatching {
                gains.putAll(json.decodeFromString(SERIALIZER, file.readText()))
            }.onFailure {
                // A half-written map costs one re-measurement per track to
                // replace, which is cheaper than reasoning about which half
                // of it survived.
                Log.w(TAG, "Discarding unreadable loudness store", it)
                gains.clear()
                file.delete()
            }
        }
    }

    /**
     * Keeps the map under [MAX_ENTRIES].
     *
     * Arbitrary rather than least-recently-used: entries carry no timestamp,
     * and adding one to make eviction fair would double the file for a
     * decision whose cost when it goes wrong is three seconds of a glide on
     * one track. The cap exists to bound the file, not to be clever.
     */
    private fun prune() {
        if (gains.size <= MAX_ENTRIES) return
        gains.keys.take(gains.size - MAX_ENTRIES).forEach(gains::remove)
    }

    companion object {
        private const val TAG = "BitChordLoudness"
        private const val FILE_NAME = "loudness_gains.json"

        /** Roughly 100 kB of JSON, and more tracks than a library tends to hold. */
        private const val MAX_ENTRIES = 5000

        /** Below this, a new measurement is not worth rewriting the file for. */
        private const val WORTH_REWRITING_DB = 0.25f

        private val SERIALIZER = MapSerializer(String.serializer(), Float.serializer())
    }
}
