package com.music.bitchord.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueEdits

/** What the queue's selection bar can do to the ticked rows. */
enum class QueueBulkAction { REMOVE, PLAY_NEXT, PLAYLIST, DOWNLOAD }

/** What an Undo notice under the queue says was done. */
sealed interface QueueUndoKind {
    data class RemovedOne(val title: String) : QueueUndoKind
    data class RemovedMany(val count: Int) : QueueUndoKind
    data object Cleared : QueueUndoKind
}

/** One Undo offer; a new [id] restarts its lifetime even for the same words. */
class QueueUndoNotice(val id: Long, val kind: QueueUndoKind, val onUndo: () -> Unit)

/**
 * The queue screen's edits beyond the basics — swiping a row into Next in
 * queue, its long-press menu, multi-select and Undo — for a host that can
 * carry them out. Hosts that can't (the desktop) provide none, and the queue
 * keeps its plain remove-and-reorder behaviour.
 *
 * Provided through [LocalQueueEditing] rather than threaded through every
 * player layout: it is one object for the life of the player, and nothing
 * reads it but the queue. The callbacks are plain fields the host refreshes
 * as it recomposes, as [QueueDrag]'s are, so no row recomposes when they do.
 */
@Stable
class QueueEditing {
    /** Moves the row at a timeline index into Next in queue; true once done. */
    var onSwipeToQueue: (Int, Song) -> Boolean = { _, _ -> false }

    /** Opens the track menu for a held row. */
    var onSongMenu: (Int, Song) -> Unit = { _, _ -> }

    /**
     * Runs [QueueBulkAction] on the ticked rows, each with its timeline index
     * and its own song as the screen drew it — so the host can follow the
     * rows by entry if the queue has moved since.
     */
    var onBulkAction: (List<QueueEdits.Target>, QueueBulkAction) -> Unit = { _, _ -> }

    /** The Undo offer under the queue, if one is up. */
    var undo by mutableStateOf<QueueUndoNotice?>(null)
}

val LocalQueueEditing = staticCompositionLocalOf<QueueEditing?> { null }
