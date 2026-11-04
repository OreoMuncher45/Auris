package com.music.auris.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.AurisIcons

/**
 * One open tab: a place in the app, and the road that led there.
 *
 * The phone has one screen at a time and the back stack is the only way back. A window has room
 * for more than one, and the road behind each is its own: a tab left on an album keeps that album
 * open while another tab goes looking for something else.
 */
internal data class AurisTab(
    /** Stable across the tab's life, so the strip does not reshuffle when it is renamed. */
    val id: String,
    /** What the strip shows: the page's own name, or the thing open on it. */
    val title: String,
    /** The place this tab is on. */
    val entry: AurisNavEntry,
    /** The places behind it, oldest first — the tab's own back stack. */
    val history: List<AurisNavEntry>,
)

/**
 * The strip of open tabs above the page.
 *
 * A window's own strip, in the way a browser's is: the tab in front is the bright one, the rest
 * recede, and every tab carries its own close button. The row scrolls sideways when there are more
 * tabs than there is room for, because a window can be narrower than the work in it.
 */
@Composable
internal fun AurisTabStrip(
    tabs: List<AurisTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
) {
    val scroll = rememberScrollState()
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))
        tabs.forEachIndexed { index, tab ->
            AurisTabItem(
                tab = tab,
                active = index == activeIndex,
                onSelect = { onSelect(index) },
                onClose = { onClose(index) },
            )
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                AurisStrings["new_tab", "New tab"],
                tint = AurisSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** One tab in the strip. */
@Composable
private fun AurisTabItem(
    tab: AurisTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    val background = when {
        active -> Color.White.copy(alpha = 0.14f)
        hovering -> Color.White.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            tab.icon(),
            tab.title,
            tint = if (active) Color.White else AurisSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            tab.title,
            color = if (active) Color.White else AurisSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(132.dp),
        )
        // The close button is there for the tab in front and for whichever one the pointer is
        // over; the rest keep their place in the strip without a row of crosses to read through.
        if (active || hovering) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    AurisStrings["close_tab", "Close tab"],
                    tint = AurisSecondary,
                    modifier = Modifier.size(13.dp),
                )
            }
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}

/** The glyph a tab carries: whatever kind of place it is on. */
private fun AurisTab.icon(): ImageVector = when (entry.destination) {
    AurisDestination.LISTEN_NOW -> AurisIcons.Home
    AurisDestination.EXPLORE -> AurisIcons.Explore
    AurisDestination.RADIO -> AurisIcons.MusicNote
    AurisDestination.PODCASTS -> AurisIcons.LyricsQuote
    AurisDestination.LIBRARY -> AurisIcons.Library
    AurisDestination.SEARCH -> AurisIcons.Search
    AurisDestination.HISTORY -> AurisIcons.Clock
    AurisDestination.DOWNLOADS -> AurisIcons.Download
    AurisDestination.LOCAL_MUSIC -> AurisIcons.GridView
    AurisDestination.SETTINGS -> AurisIcons.MusicNote
}

/**
 * The keyboard the app answers to, written down.
 *
 * A window is driven by the keyboard, and a shortcut nobody can find is a shortcut nobody uses.
 * This is the list: what every key does, in the order a hand meets them.
 */
internal data class AurisShortcut(
    val keys: String,
    val what: String,
)

/** Every shortcut the desktop has, for the help sheet. */
internal val AurisShortcutMap: List<AurisShortcut> = listOf(
    AurisShortcut("Space", "Play or pause"),
    AurisShortcut("Ctrl + ← / →", "Previous or next track"),
    AurisShortcut("Ctrl + ↑ / ↓", "Volume up or down"),
    AurisShortcut("Ctrl + F", "Search"),
    AurisShortcut("Ctrl + L", "Focus the search field"),
    AurisShortcut("Ctrl + T", "New tab"),
    AurisShortcut("Ctrl + W", "Close the tab in front"),
    AurisShortcut("Ctrl + Tab / Ctrl + Shift + Tab", "Next or previous tab"),
    AurisShortcut("Ctrl + 1 … 9", "Go to the tab at that position"),
    AurisShortcut("Alt + 1 … 9", "Go to the page at that position"),
    AurisShortcut("Alt + ←", "Back"),
    AurisShortcut("Ctrl + ,", "Settings"),
    AurisShortcut("Ctrl + /", "This list"),
    AurisShortcut("Ctrl + Q", "Quit"),
    AurisShortcut("F11", "Full screen"),
)

/**
 * The shortcut list, as a sheet over the app.
 *
 * Opened from the keyboard or from the button at the foot of the sidebar; the app keeps playing
 * underneath it, because a sheet is an overlay and not a page.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AurisShortcutsSheet(onDismiss: () -> Unit) {
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(AurisSurface)
                .padding(22.dp),
        ) {
            androidx.compose.foundation.layout.Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        null,
                        tint = AurisSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AurisStrings["keyboard_shortcuts", "Keyboard shortcuts"],
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(14.dp))
                AurisShortcutMap.forEach { shortcut ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            shortcut.keys,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.width(230.dp),
                        )
                        Text(
                            shortcut.what,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurisSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        AurisStrings["close", "Close"],
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
