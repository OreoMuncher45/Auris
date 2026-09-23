package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import java.util.UUID

/**
 * Information about where a queue or track was started from in the UI.
 */
data class QueueSource(
    val title: String,
    val type: PlaybackSourceType,
    val id: String? = null,
)

/**
 * Headless orchestrator for two-tier Spotify-style queue operations.
 *
 * Owns timeline construction, tier assignment, deterministic queue identity assignment,
 * and user-queue pruning invariants.
 */
object QueueCoordinator {

    /**
     * Converts a [Song] into a queue entry belonging to [tier].
     *
     * Invariant: [Song.queueEntryId] is assigned ONCE when entering the queue and is strictly
     * immutable across all transformations, upgrades, and round-trips.
     */
    fun Song.asQueueEntry(tier: QueueTier): Song = copy(
        queueTier = tier,
        queueEntryId = queueEntryId ?: UUID.randomUUID().toString(),
    )

    /**
     * Constructs an interleaved queue for starting a Context (Album, Playlist, Artist):
     *
     * Invariant: [Selected Track] + [Preserved USER_QUEUE] + [Remaining Context Tracks].
     */
    fun buildContextQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        newContextSongs: List<Song>,
        selectedIndex: Int,
        contextSource: QueueSource,
    ): List<Song> {
        if (newContextSongs.isEmpty()) return emptyList()

        val upcomingUserQueue = if (currentIndex in currentTimeline.indices) {
            currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                .filter { it.queueTier == QueueTier.USER_QUEUE }
        } else {
            emptyList()
        }

        val contextEntries = newContextSongs.map { song ->
            song.copy(
                playbackSource = contextSource.title,
                playbackSourceType = contextSource.type,
                playbackSourceId = contextSource.id,
            ).asQueueEntry(QueueTier.CONTEXT)
        }

        val safeIndex = selectedIndex.coerceIn(contextEntries.indices)
        val selected = contextEntries[safeIndex]
        val remainingContext = contextEntries.filterIndexed { i, _ -> i != safeIndex }

        return listOf(selected) + upcomingUserQueue + remainingContext
    }

    /**
     * Constructs a queue for playing a one-off song (from Search, Home, Explore):
     *
     * Invariant: [Tapped Track] + [Preserved USER_QUEUE].
     */
    fun buildOneOffQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        tappedSong: Song,
        source: QueueSource,
    ): List<Song> {
        val upcomingUserQueue = if (currentIndex in currentTimeline.indices) {
            currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                .filter { it.queueTier == QueueTier.USER_QUEUE }
        } else {
            emptyList()
        }

        val oneOffEntry = tappedSong.copy(
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        ).asQueueEntry(QueueTier.CONTEXT)

        return listOf(oneOffEntry) + upcomingUserQueue
    }

    /**
     * Finds the insertion index for user-queued tracks.
     *
     * Invariant:
     * - "Play Next" (isNext = true) inserts at the head of USER_QUEUE (immediately after currentIndex).
     * - "Add to Queue" (isNext = false) inserts at the tail of USER_QUEUE (ahead of CONTEXT and AUTOPLAY).
     */
    fun findUserQueueInsertionIndex(
        timeline: List<Song>,
        currentIndex: Int,
        isNext: Boolean,
    ): Int {
        if (timeline.isEmpty()) return 0
        if (isNext) {
            return (currentIndex + 1).coerceIn(0, timeline.size)
        }

        val start = (currentIndex + 1).coerceIn(0, timeline.size)
        for (i in start until timeline.size) {
            if (timeline[i].queueTier != QueueTier.USER_QUEUE) {
                return i
            }
        }
        return timeline.size
    }

    /**
     * Clears only the upcoming USER_QUEUE items from the player's timeline.
     *
     * Invariant: CONTEXT and AUTOPLAY tracks are completely untouched.
     */
    fun clearUserQueue(player: Player) {
        val currentIndex = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (count == 0) return

        val userQueueIndices = mutableListOf<Int>()
        for (i in (currentIndex + 1) until count) {
            val item = player.getMediaItemAt(i)
            if (item.queueTier == QueueTier.USER_QUEUE) {
                userQueueIndices.add(i)
            }
        }

        // Remove in reverse order to preserve preceding indices during removal
        for (i in userQueueIndices.asReversed()) {
            player.removeMediaItem(i)
        }
    }

    /**
     * Prunes played USER_QUEUE items once playback has transitioned into CONTEXT.
     *
     * Invariant: Once an item leaves the USER_QUEUE block and enters CONTEXT, all preceding
     * consumed USER_QUEUE entries are safely removed so that Media3's native REPEAT_MODE_ALL
     * will only cycle through CONTEXT items.
     */
    fun consumePlayedUserQueue(player: Player) {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex <= 0) return

        val currentItem = player.currentMediaItem ?: return
        if (currentItem.queueTier != QueueTier.CONTEXT) return

        val playedIndices = mutableListOf<Int>()
        for (i in 0 until currentIndex) {
            val item = player.getMediaItemAt(i)
            if (item.queueTier == QueueTier.USER_QUEUE) {
                playedIndices.add(i)
            }
        }

        for (i in playedIndices.asReversed()) {
            player.removeMediaItem(i)
        }
    }

    /**
     * Reconstructs the upcoming queue when a listener taps an item in the queue drawer.
     *
     * Invariants:
     * 1. If [targetIndex] <= [currentIndex] or either index is out of bounds, returns `null`
     *    (indicating a backward jump or active track tap handled via standard player seek).
     * 2. [QueueTier.AUTOPLAY]: The tapped track becomes the active track promoted to [QueueTier.CONTEXT]
     *    (starting a fresh radio/station). All future [QueueTier.USER_QUEUE] items across the entire
     *    timeline are preserved immediately after it. Old context and bypassed autoplay items are discarded.
     * 3. [QueueTier.CONTEXT]: The tapped track becomes active. All future [QueueTier.USER_QUEUE] items
     *    across the entire timeline are preserved immediately after it. Context items strictly after
     *    [targetIndex] follow after the user queue. Bypassed context items are discarded.
     * 4. [QueueTier.USER_QUEUE]: Preceding user queue items between [currentIndex] + 1 and [targetIndex]
     *    were bypassed within the manual queue and are consumed. Subsequent user queue items, along with
     *    all future context and autoplay tracks, are preserved.
     */
    fun buildJumpQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        targetIndex: Int,
    ): List<Song>? {
        if (currentIndex !in currentTimeline.indices || targetIndex !in currentTimeline.indices) {
            return null
        }
        if (targetIndex <= currentIndex) {
            return null
        }

        val targetSong = currentTimeline[targetIndex]
        val allFutureUserQueue = currentTimeline.subList(currentIndex + 1, currentTimeline.size)
            .filter { it.queueTier == QueueTier.USER_QUEUE }

        return when (targetSong.queueTier) {
            QueueTier.AUTOPLAY -> {
                val sourceTitle = targetSong.playbackSource?.ifBlank { targetSong.title } ?: targetSong.title
                val promotedTarget = targetSong.copy(
                    playbackSource = sourceTitle,
                    playbackSourceType = targetSong.playbackSourceType ?: PlaybackSourceType.QUEUE,
                ).asQueueEntry(QueueTier.CONTEXT)
                listOf(promotedTarget) + allFutureUserQueue
            }
            QueueTier.CONTEXT -> {
                val remainingContext = currentTimeline.subList(targetIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.CONTEXT }
                listOf(targetSong) + allFutureUserQueue + remainingContext
            }
            QueueTier.USER_QUEUE -> {
                val subsequentUserQueue = currentTimeline.subList(targetIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.USER_QUEUE }
                val futureContext = currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.CONTEXT }
                val futureAutoplay = currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.AUTOPLAY }
                listOf(targetSong) + subsequentUserQueue + futureContext + futureAutoplay
            }
        }
    }

    /**
     * Executes a semantic queue jump on [player], preserving history up to [Player.getCurrentMediaItemIndex]
     * and avoiding unintended reshuffling.
     */
    fun jumpToQueueItem(
        player: Player,
        targetIndex: Int,
        cachedTimeline: List<Song>? = null,
    ) {
        val currentIndex = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (targetIndex !in 0 until count) return

        if (targetIndex <= currentIndex) {
            // Backward jump or same track: seek in history without modifying playlist
            player.seekTo(targetIndex, 0L)
            player.play()
            return
        }

        val currentTimeline = cachedTimeline?.takeIf { it.size == count }
            ?: (0 until count).map { player.getMediaItemAt(it).toSong() }
        val newUpcoming = buildJumpQueue(currentTimeline, currentIndex, targetIndex) ?: return

        // Retain played history up to and including currentIndex so backward navigation works
        val history = (0..currentIndex).map { player.getMediaItemAt(it) }
        val upcomingMediaItems = newUpcoming.map { it.toMediaItem() }

        val newPlaylist = history + upcomingMediaItems
        val newTargetIndex = history.size // First track of newUpcoming
        player.setMediaItems(newPlaylist, newTargetIndex, 0L)
        if (runCatching { player.playbackState }.getOrNull() == Player.STATE_IDLE) {
            player.prepare()
        }
        player.play()
    }
}
