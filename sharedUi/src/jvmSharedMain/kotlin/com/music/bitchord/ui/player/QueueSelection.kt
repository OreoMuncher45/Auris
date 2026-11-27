package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.sharedui.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The surface the queue's own notices and bars sit on — the app's notice card. */
private val QUEUE_BAR_COLOR = Color(0xFF282828)
private val QUEUE_BAR_SHAPE = RoundedCornerShape(14.dp)

/** How long an Undo offer stays up. */
private const val UNDO_VISIBLE_MS = 4_000L

/**
 * The queue's multi-selection. Owned by the queue rather than its rows, so
 * scrolling a row away never drops it from the selection.
 *
 * A [androidx.compose.runtime.snapshots.SnapshotStateSet], read by each row
 * through a derived state: ticking one row recomposes that row and the count,
 * not every row on screen.
 */
@Stable
internal class QueueSelectionState {
    var active by mutableStateOf(false)
        private set
    val keys = mutableStateSetOf<String>()

    /**
     * Where each ticked row without an entry id stood when it was ticked. Such
     * a row is keyed by its song and how many copies come before it, so when
     * an earlier copy leaves, the key moves on to the next copy: the tick must
     * go with the row, not pass to a copy the listener did not tick. Plain,
     * not state: only [retain] reads it.
     */
    private val pinned = HashMap<String, Int>()

    fun start() {
        keys.clear()
        pinned.clear()
        active = true
    }

    fun clear() {
        active = false
        keys.clear()
        pinned.clear()
    }

    fun toggle(row: QueueTrack) {
        if (keys.remove(row.key)) {
            pinned.remove(row.key)
        } else {
            keys.add(row.key)
            pin(row)
        }
    }

    fun selectAll(rows: List<QueueTrack>) {
        rows.forEach { keys.add(it.key); pin(it) }
    }

    private fun pin(row: QueueTrack) {
        if (row.song.queueEntryId == null) pinned[row.key] = row.timelineIndex
    }

    /**
     * Drops keys whose rows have left the queue, and an entry-less row's key
     * that now names a different copy than the one ticked.
     */
    fun retain(rows: List<QueueTrack>) {
        if (keys.isEmpty()) return
        val live = rows.associateBy { it.key }
        val gone = keys.filterTo(HashSet()) { key ->
            val row = live[key] ?: return@filterTo true
            pinned[key]?.let { it != row.timelineIndex } ?: false
        }
        if (gone.isNotEmpty()) keys.removeAll(gone)
        pinned.keys.retainAll(keys)
    }

    fun selected(rows: List<QueueTrack>): List<QueueTrack> = rows.filter { it.key in keys }
}

/** Remove, Play next, Add to playlist, Download — for the ticked rows. */
@Composable
internal fun QueueSelectionBar(
    visible: Boolean,
    enabled: Boolean,
    reduceAnimation: Boolean,
    onAction: (QueueBulkAction) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceAnimation) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reduceAnimation) ExitTransition.None else shrinkVertically() + fadeOut(),
        label = "queue-selection-bar",
    ) {
        Surface(
            color = QUEUE_BAR_COLOR,
            contentColor = Color.White,
            shape = QUEUE_BAR_SHAPE,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            // Capped so the actions stay grouped on tablets and unfolded foldables.
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BulkActionButton(Icons.Rounded.Delete, stringResource(Res.string.queue_selection_remove), enabled) {
                    onAction(QueueBulkAction.REMOVE)
                }
                BulkActionButton(Icons.Rounded.PlaylistPlay, stringResource(Res.string.play_next), enabled) {
                    onAction(QueueBulkAction.PLAY_NEXT)
                }
                BulkActionButton(Icons.Rounded.PlaylistAdd, stringResource(Res.string.queue_selection_add_to_playlist), enabled) {
                    onAction(QueueBulkAction.PLAYLIST)
                }
                BulkActionButton(Icons.Rounded.Download, stringResource(Res.string.queue_download), enabled) {
                    onAction(QueueBulkAction.DOWNLOAD)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BulkActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val labelStyle = MaterialTheme.typography.labelMedium
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        // One line on every width and font scale: shrinks a little before it ever ellipsizes.
        BasicText(
            text = label,
            style = labelStyle.copy(color = Color.White, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = labelStyle.fontSize),
        )
    }
}

/** "Removed …  Undo", under the queue, for a few seconds after a removal or Clear. */
@Composable
internal fun QueueUndoBar(editing: QueueEditing, reduceAnimation: Boolean) {
    val notice = editing.undo
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(UNDO_VISIBLE_MS)
        if (editing.undo?.id == id) editing.undo = null
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = if (reduceAnimation) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reduceAnimation) ExitTransition.None else shrinkVertically() + fadeOut(),
        label = "queue-undo",
    ) {
        // Kept through the exit, so the words don't blank as it leaves.
        val shown = remember { arrayOfNulls<QueueUndoNotice>(1) }
        if (notice != null) shown[0] = notice
        val current = shown[0] ?: return@AnimatedVisibility
        Surface(
            color = QUEUE_BAR_COLOR,
            contentColor = Color.White,
            shape = QUEUE_BAR_SHAPE,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (val kind = current.kind) {
                        is QueueUndoKind.RemovedOne -> stringResource(Res.string.queue_removed_song, kind.title)
                        is QueueUndoKind.RemovedMany -> stringResource(Res.string.queue_removed_songs, kind.count)
                        QueueUndoKind.Cleared -> stringResource(Res.string.queue_cleared)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                Text(
                    text = stringResource(Res.string.queue_undo_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable {
                            // Once: a second tap during the exit does nothing.
                            if (editing.undo === current) {
                                editing.undo = null
                                current.onUndo()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
