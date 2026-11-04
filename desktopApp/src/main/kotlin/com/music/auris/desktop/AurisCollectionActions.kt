package com.music.auris.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.AurisIcons

/**
 * What can be done with the open release as a whole.
 *
 * The phone's page has its own sheet for this; the desktop draws the same list
 * of acts, because the acts themselves — downloading every track, copying the
 * link, renaming a playlist, deleting one — are the desktop's own and the
 * phone's own respectively.
 */
@Composable
internal fun AurisCollectionActionsDialog(
    songs: List<Song>,
    canShare: Boolean,
    canEdit: Boolean,
    onDownloadAll: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopBarGlass(shape)
                .padding(vertical = 16.dp),
        ) {
            Text(
                AurisStrings["d_release_actions", "Release actions"],
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
            AurisActionRow(AurisIcons.Download, AurisStrings["download_all", "Download all"]) {
                onDownloadAll()
                onDismiss()
            }
            if (canShare) {
                AurisActionRow(Icons.Rounded.Share, AurisStrings["share", "Share"]) {
                    onShare()
                    onDismiss()
                }
            }
            if (canEdit) {
                AurisActionRow(Icons.Rounded.Edit, AurisStrings["rename", "Rename"]) {
                    onRename()
                    onDismiss()
                }
                AurisActionRow(Icons.Rounded.Delete, AurisStrings["delete", "Delete"]) {
                    onDelete()
                    onDismiss()
                }
            }
        }
    }
}

/** One act in the sheet: its glyph, its name, and the whole row is the button. */
@Composable
private fun AurisActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(13.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}
