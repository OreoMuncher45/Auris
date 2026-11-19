package com.music.bitchord.playback

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.runtime.structuralEqualityPolicy
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A reader of one [PlayerReads] property is invalidated only when that
 * property changes, not whenever the [PlayerState] behind it is replaced.
 *
 * [SnapshotStateObserver] applies the same derived-state equivalence check
 * composition does, so the counts here are the invalidations a composable
 * reading these properties would get.
 */
class PlayerReadsTest {
    private val position = PlaybackPosition()
    private val state = mutableStateOf(PlayerState(position = position))
    private val reads = PlayerReads(state, position)
    private val observer = SnapshotStateObserver { it() }

    @Before fun start() = observer.start()

    @After fun stop() {
        observer.stop()
        observer.clear()
    }

    /** How many times a reader of [read] is invalidated while [change] runs. */
    private fun invalidations(read: () -> Unit, change: () -> Unit): Int {
        var count = 0
        val scope = Any()
        lateinit var onChanged: (Any) -> Unit
        onChanged = { count++; observer.observeReads(scope, onChanged) { read() } }
        observer.observeReads(scope, onChanged) { read() }
        change()
        Snapshot.sendApplyNotifications()
        observer.clear(scope)
        return count
    }

    private fun update(block: PlayerState.() -> PlayerState) {
        Snapshot.withMutableSnapshot { state.value = state.value.block() }
        Snapshot.sendApplyNotifications()
    }

    private val track = Song(videoId = "a", title = "A", artist = "Artist", thumbnailUrl = null)

    @Test fun `song reader ignores play, pause, buffering and duration`() {
        update { copy(song = track) }
        val count = invalidations(read = { reads.song }) {
            update { copy(isPlaying = true) }
            update { copy(isLoading = true) }
            update { copy(isLoading = false) }
            update { copy(durationMs = 180_000L) }
            update { copy(isPlaying = false) }
        }
        assertEquals(0, count)
    }

    @Test fun `song reader ignores an equal song rebuilt from the controller`() {
        update { copy(song = track) }
        // sync() converts the current MediaItem again on every event, so the
        // song is a new instance each time; only its contents matter.
        val count = invalidations(read = { reads.song }) { update { copy(song = track.copy()) } }
        assertEquals(0, count)
    }

    @Test fun `song reader is invalidated on a track change`() {
        update { copy(song = track) }
        val count = invalidations(read = { reads.song }) {
            update { copy(song = track.copy(videoId = "b", title = "B")) }
        }
        assertEquals(1, count)
    }

    @Test fun `isPlaying reader is invalidated on play and on pause, not on buffering`() {
        val count = invalidations(read = { reads.isPlaying }) {
            update { copy(isPlaying = true) }
            update { copy(isLoading = true) }
            update { copy(isLoading = false) }
            update { copy(isPlaying = false) }
        }
        assertEquals(2, count)
    }

    @Test fun `reading the whole snapshot is invalidated by every change`() {
        val count = invalidations(read = { reads.snapshot }) {
            update { copy(isPlaying = true) }
            update { copy(isLoading = true) }
            update { copy(durationMs = 1L) }
        }
        assertEquals(3, count)
    }

    @Test fun `reads outside composition see the current value`() {
        update { copy(song = track, isPlaying = true) }
        assertEquals("a", reads.song?.videoId)
        assertEquals(true, reads.isPlaying)
        update { copy(song = track.copy(videoId = "b"), isPlaying = false) }
        assertEquals("b", reads.song?.videoId)
        assertEquals(false, reads.isPlaying)
    }

    /**
     * What BitChordApp's own body reads from the player once the busy flag and
     * the lyrics' duration are derived / watched elsewhere: only the track,
     * for its effect keys and the raised player's song. Buffering and the
     * duration settling must not reach it.
     */
    @Test fun `root body reads are not invalidated by buffering or duration`() {
        update { copy(song = track) }
        val count = invalidations(read = { reads.song?.videoId }) {
            update { copy(isLoading = true) }
            update { copy(durationMs = 180_000L) }
            update { copy(isLoading = false) }
            update { copy(durationMs = 181_000L) }
        }
        assertEquals(0, count)
        val trackChange = invalidations(read = { reads.song?.videoId }) {
            update { copy(song = track.copy(videoId = "b")) }
        }
        assertEquals(1, trackChange)
    }

    /**
     * The shape of BitChordApp's playPauseBusy: buffering or waiting for a
     * party's scheduled start. Its readers are invalidated when the combined
     * answer changes, not on every flip of either half.
     */
    @Test fun `play-pause busy is invalidated only when the combined answer changes`() {
        val awaitingStart = mutableStateOf(false)
        val busy = derivedStateOf(structuralEqualityPolicy()) { reads.isLoading || awaitingStart.value }
        val count = invalidations(read = { busy.value }) {
            update { copy(isLoading = true) } // false -> true: 1
            Snapshot.withMutableSnapshot { awaitingStart.value = true } // still true
            Snapshot.sendApplyNotifications()
            update { copy(isLoading = false) } // still true (awaiting)
            update { copy(durationMs = 1L) } // unrelated
            Snapshot.withMutableSnapshot { awaitingStart.value = false } // true -> false: 2
            Snapshot.sendApplyNotifications()
        }
        assertEquals(2, count)
    }
}
