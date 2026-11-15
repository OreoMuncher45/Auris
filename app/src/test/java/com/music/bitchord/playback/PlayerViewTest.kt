package com.music.bitchord.playback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A reader of one [PlayerView] property is invalidated only when that
 * property changes, not whenever the [PlayerState] behind it is replaced.
 *
 * [SnapshotStateObserver] applies the same derived-state equivalence check
 * composition does, so the counts here are the invalidations a composable
 * reading these properties would get.
 */
class PlayerViewTest {
    private val position = PlaybackPosition()
    private val state = mutableStateOf(PlayerState(position = position))
    private val view = PlayerView(state, position)
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
        val count = invalidations(read = { view.song }) {
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
        val count = invalidations(read = { view.song }) { update { copy(song = track.copy()) } }
        assertEquals(0, count)
    }

    @Test fun `song reader is invalidated on a track change`() {
        update { copy(song = track) }
        val count = invalidations(read = { view.song }) {
            update { copy(song = track.copy(videoId = "b", title = "B")) }
        }
        assertEquals(1, count)
    }

    @Test fun `isPlaying reader is invalidated on play and on pause, not on buffering`() {
        val count = invalidations(read = { view.isPlaying }) {
            update { copy(isPlaying = true) }
            update { copy(isLoading = true) }
            update { copy(isLoading = false) }
            update { copy(isPlaying = false) }
        }
        assertEquals(2, count)
    }

    @Test fun `reading the whole snapshot is invalidated by every change`() {
        val count = invalidations(read = { view.snapshot }) {
            update { copy(isPlaying = true) }
            update { copy(isLoading = true) }
            update { copy(durationMs = 1L) }
        }
        assertEquals(3, count)
    }

    @Test fun `reads outside composition see the current value`() {
        update { copy(song = track, isPlaying = true) }
        assertEquals("a", view.song?.videoId)
        assertEquals(true, view.isPlaying)
        update { copy(song = track.copy(videoId = "b"), isPlaying = false) }
        assertEquals("b", view.song?.videoId)
        assertEquals(false, view.isPlaying)
    }
}
