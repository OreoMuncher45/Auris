package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.ExplicitSongTitle
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.theme.stableStatusBarInsets
import com.music.bitchord.ui.utils.containSheetGestures
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The queue's search bar: the row at the head of the queue that opens [PlayerQueueSearchScreen].
 *
 * A row rather than a live field. The queue is a list of what is about to be
 * played, and a field that grows a keyboard in the middle of it would take the
 * list away from the listener the moment they touched it. The row says what it
 * does and takes them somewhere that can do it properly — a screen of its own,
 * still inside the player, so the music and the queue are both a tap away.
 */
@Composable
internal fun QueueSearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(Res.string.queue_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How long the typed name has to stand still before it is searched. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** How long the confirmation under the list stays up. */
private const val NOTICE_MS = 2_000L

/** The screen's own paper: the player's black, a shade up so the rows sit on something. */
private val SearchSurface = Color(0xFF0C0C0F)

/** A field on that paper. */
private val SearchFieldFill = Color.White.copy(alpha = 0.09f)

/**
 * Searching for songs to put on the queue, without leaving the player.
 *
 * ## Why it is a screen and not a sheet
 *
 * The listener is in the queue, looking at what is about to be played. What
 * they want is to say a name and get on with it. A sheet would ask them to
 * hold a menu open with one hand and type with the other; a screen takes the
 * whole window for a moment, puts the keyboard in the right place, and hands
 * the player back when they are done. The player stays mounted underneath, so
 * the music never stops and the queue is exactly where they left it.
 *
 * ## Two answers, one list
 *
 * [PlayerPlatform.host] searches the catalogue and the device together, and
 * this screen shows one list: a song the listener has downloaded is the same
 * row as a song they have not, marked "Downloaded", and it plays from disk
 * whether or not there is a connection. That is what makes the search work on
 * a train: the device's own songs are still there when the catalogue is not,
 * and the screen says so rather than pretending the answer is empty.
 *
 * ## What a tap means
 *
 * The row itself adds to the queue — the common case, one tap. The two buttons
 * at its end are the two things a listener actually asks for: "add to queue"
 * and "play next", both one tap, both confirmed by the line at the foot of the
 * screen so the tap has an answer even when the queue list is out of sight.
 */
@Composable
internal fun PlayerQueueSearchScreen(
    onDismiss: () -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Read here, at composition, rather than inside the row's callbacks: a
    // resource lookup is a composable call, and a tap is not a composition.
    val addedLabel = stringResource(Res.string.queue_search_added)
    val willPlayLabel = stringResource(Res.string.queue_search_will_play)

    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // The catalogue could not be reached, and the list is the device's own
    // songs. Said out loud, because an empty answer and an unreachable
    // catalogue look the same otherwise.
    var catalogueDown by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeId by remember { mutableIntStateOf(0) }

    // Back leaves the search, not the player: the sheet behind it would
    // otherwise take the same press and close the whole thing.
    PlayerBackHandler(enabled = true) { onDismiss() }

    // The field is the reason the screen exists; open the keyboard for it.
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Live search: a name is typed and the answer follows, a beat after the
    // typing stops. Every keystroke cancels the one before it, so a slow
    // network can never answer an older name than the one on screen.
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.isEmpty()) {
            hits = emptyList()
            searching = false
            catalogueDown = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
        val answer = runCatching { PlayerPlatform.host.searchQueueSongs(term) }
            .getOrElse { QueueSearchResult(emptyList(), catalogueUnavailable = true) }
        hits = answer.songs
        catalogueDown = answer.catalogueUnavailable && answer.songs.isNotEmpty()
        searching = false
    }

    LaunchedEffect(noticeId) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }

    fun confirm(message: String) {
        notice = message
        noticeId += 1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SearchSurface)
            // No drag inside the search reaches the sheet the player lives
            // in: a swipe over a row, or over the space under the list, would
            // otherwise be the player being pulled down from under a screen
            // the listener is typing into.
            .containSheetGestures()
            // The player's window can hide the status bar; this keeps the
            // header clear of the cutout whether it does or not.
            .windowInsetsPadding(stableStatusBarInsets()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = PLAYER_GUTTER, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptics.play(Haptic.Tap)
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(Res.string.queue_search_back),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(Res.string.queue_search_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

        SearchInput(
            query = query,
            onQueryChange = { query = it },
            onSubmit = { keyboard?.hide() },
            focus = focus,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PLAYER_GUTTER),
        )

        Spacer(Modifier.height(10.dp))

        if (catalogueDown) {
            Text(
                text = stringResource(Res.string.queue_search_offline),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = PLAYER_GUTTER, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching && hits.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = 2.5.dp,
                )

                query.isBlank() -> Text(
                    text = stringResource(Res.string.queue_search_start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = PLAYER_GUTTER, vertical = 28.dp),
                )

                hits.isEmpty() -> Text(
                    text = stringResource(Res.string.queue_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = PLAYER_GUTTER, vertical = 28.dp),
                )

                else -> LazyColumn(contentPadding = PaddingValues(
                    start = PLAYER_GUTTER,
                    end = PLAYER_GUTTER,
                    bottom = 24.dp,
                )) {
                    items(hits, key = { it.videoId }) { song ->
                        QueueSearchRow(
                            song = song,
                            onAddToQueue = {
                                haptics.play(Haptic.Tick)
                                onAddToQueue(song)
                                confirm(addedLabel)
                            },
                            onPlayNext = {
                                haptics.play(Haptic.Tick)
                                onPlayNext(song)
                                confirm(willPlayLabel)
                            },
                        )
                    }
                }
            }
        }

        // The answer to the tap, where the tap happened: the queue itself is
        // behind this screen, so a row quietly appearing in it would be the
        // only evidence the listener ever got.
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PLAYER_GUTTER)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/** The name being typed, on the player's own paper. */
@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(SearchFieldFill)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(Res.string.queue_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.clear),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * One search hit, with the two things it can be asked to do.
 *
 * The row's own tap adds to the queue: it is the common case, and the row is
 * the biggest thing on screen. The buttons are the second and third cases, and
 * they are buttons rather than a menu because there are only two of them —
 * a menu for two choices is a second tap to answer a question the listener
 * has already answered by looking.
 */
@Composable
private fun QueueSearchRow(
    song: Song,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onAddToQueue)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = rememberRemoteArtworkUrl(song),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .thumbnailBorder(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.92f),
                qualityBadge = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.localUri != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.downloaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        SearchActionButton(
            icon = Icons.Rounded.PlaylistAdd,
            description = stringResource(Res.string.add_to_queue),
            onClick = onAddToQueue,
        )
        SearchActionButton(
            icon = Icons.Rounded.PlaylistPlay,
            description = stringResource(Res.string.play_next),
            onClick = onPlayNext,
        )
    }
}

/** One of the row's two actions: a round target at the end of the row. */
@Composable
private fun SearchActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}
