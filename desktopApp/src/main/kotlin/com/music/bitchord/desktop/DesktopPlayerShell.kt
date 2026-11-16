package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.player.NowPlayingScreen
import com.music.bitchord.ui.player.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the player sheet can ask the window to do; each is the window's function of the same name. */
internal class DesktopPlayerActions(
    val toggleLike: (Song) -> Unit,
    val togglePlayPauseFromUser: () -> Unit,
    val playNext: () -> Unit,
    val playPrevious: () -> Unit,
    val seekPlayer: (Long) -> Unit,
    val setShuffle: (Boolean) -> Unit,
    val setAutoplay: (Boolean) -> Unit,
    val playQueueIndex: (Int) -> Unit,
    val removeFromQueue: (Int) -> Unit,
    val moveInQueue: (from: Int, to: Int) -> Unit,
    val clearQueue: () -> Unit,
    val openAlbum: (String) -> Unit,
    val openArtist: (browseId: String, name: String) -> Unit,
    val playerSongActions: (Song) -> DesktopSongActions,
)

/**
 * The player shell: the full-window player sheet over the page, its "…" menu and its message
 * toast.
 *
 * Lifted out of [BitChordDesktopApp] as it was. The local functions at the top keep the moved
 * code reading as it did, each one a call into [actions].
 */
@Composable
internal fun DesktopPlayerShell(
    selectedSong: Song?,
    trackLinks: Song?,
    overlays: DesktopOverlays,
    settings: DesktopAppSettings,
    persistence: DesktopPersistence,
    scope: CoroutineScope,
    account: DesktopAccountState,
    library: DesktopLibraryData,
    activeAccount: DesktopGoogleAccount?,
    liveQueue: DesktopQueue,
    partyState: DesktopListenTogether.State,
    playback: DesktopPlaybackState,
    playbackEngine: DesktopPlaybackEngine,
    playerPosition: PlaybackPosition,
    sharedLyrics: List<LyricLine>?,
    sharedLyricsSource: LyricsSource?,
    providerStates: Map<LyricsSource, LyricsProviderState>,
    sharedLyricsUnavailable: Boolean,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    playerMenuOpen: Boolean,
    onPlayerMenuOpenChange: (Boolean) -> Unit,
    actions: DesktopPlayerActions,
) {
    fun toggleLike(song: Song) = actions.toggleLike(song)
    fun togglePlayPauseFromUser() = actions.togglePlayPauseFromUser()
    fun playNext() = actions.playNext()
    fun playPrevious() = actions.playPrevious()
    fun seekPlayer(target: Long) = actions.seekPlayer(target)
    fun setShuffle(enabled: Boolean) = actions.setShuffle(enabled)
    fun setAutoplay(enabled: Boolean) = actions.setAutoplay(enabled)
    fun playQueueIndex(target: Int) = actions.playQueueIndex(target)
    fun removeFromQueue(at: Int) = actions.removeFromQueue(at)
    fun moveInQueue(from: Int, to: Int) = actions.moveInQueue(from, to)
    fun clearQueue() = actions.clearQueue()
    fun openAlbum(browseId: String) = actions.openAlbum(browseId)
    fun openArtist(browseId: String, name: String) = actions.openArtist(browseId, name)
    fun playerSongActions(song: Song) = actions.playerSongActions(song)

    DesktopPlayerSheet(
        visible = overlays.nowPlaying && selectedSong != null,
        onDismiss = {
            DesktopWindowMode.exit()
            overlays.nowPlaying = false
        },
    ) { windowWidth, windowHeight ->
        val current = selectedSong ?: return@DesktopPlayerSheet
        val playerSong = trackLinks?.takeIf { it.videoId == current.videoId }
            ?.let { extra ->
                current.copy(
                    artistId = current.artistId ?: extra.artistId,
                    // Same as on the phone: the watch-queue lookup
                    // is what carries a channel per credited
                    // artist, so it is what makes every name in
                    // the credit line openable.
                    artists = current.artists.ifEmpty { extra.artists },
                    albumId = current.albumId ?: extra.albumId,
                    albumName = current.albumName ?: extra.albumName,
                )
            }
            ?: current
        NowPlayingScreen(
            song = playerSong,
            isPlaying = playback.isPlaying,
            isLoading = playback.isLoading,
            position = playerPosition,
            durationMs = playback.durationMs,
            audioVersionSwitching = false,
            qualityUpgraded = false,
            queue = liveQueue.songs,
            queueIndex = liveQueue.index,
            hasPrevious = liveQueue.hasPrevious,
            hasNext = liveQueue.hasNext,
            repeatMode = when (settings.repeatMode) {
                DesktopRepeatMode.OFF -> RepeatModes.OFF
                DesktopRepeatMode.ONE -> RepeatModes.ONE
                DesktopRepeatMode.ALL -> RepeatModes.ALL
            },
            shuffleEnabled = settings.shuffle,
            autoplayEnabled = DesktopAutoplay.enabled(partyState, settings.autoplay),
            signedIn = account.youtubeSignedIn,
            accountName = activeAccount?.name?.takeIf(String::isNotBlank),
            likeStatus = when (current.videoId) {
                in library.likedIds -> LikeStatus.LIKE
                in library.dislikedIds -> LikeStatus.DISLIKE
                else -> LikeStatus.INDIFFERENT
            },
            onToggleLike = { toggleLike(current) },
            onPlayPause = ::togglePlayPauseFromUser,
            onNext = ::playNext,
            onPrevious = ::playPrevious,
            onBlockedControl = {
                DesktopPlayerHost.showMessage("Only the party host can change playback")
            },
            onSeek = ::seekPlayer,
            onSeekFraction = { fraction ->
                val duration = playbackEngine.state.value.durationMs
                if (duration > 0) seekPlayer((fraction * duration).toLong())
            },
            onToggleShuffle = { setShuffle(!settings.shuffle) },
            // The phone's order: off, all, one.
            onCycleRepeat = {
                settings.repeatMode = when (settings.repeatMode) {
                    DesktopRepeatMode.OFF -> DesktopRepeatMode.ALL
                    DesktopRepeatMode.ALL -> DesktopRepeatMode.ONE
                    DesktopRepeatMode.ONE -> DesktopRepeatMode.OFF
                }
                persistence.saveString("repeat_mode", settings.repeatMode.name)
            },
            onToggleAutoplay = {
                setAutoplay(!DesktopAutoplay.enabled(DesktopListenTogether.state.value, settings.autoplay))
            },
            onJumpTo = ::playQueueIndex,
            onRemoveFromQueue = ::removeFromQueue,
            onMoveInQueue = ::moveInQueue,
            onClearQueue = ::clearQueue,
            onOpenMenu = { onPlayerMenuOpenChange(true) },
            onOpenAlbum = { id ->
                overlays.nowPlaying = false
                openAlbum(id)
            },
            // The lead credit carries the track's own channel
            // id and opens straight away; anyone else on the
            // line has no id, so the page is found from the
            // name — and only when the answer is that name
            // exactly, so a fragment like "2115" opens nothing
            // rather than White 2115.
            onOpenArtist = { id, name ->
                overlays.nowPlaying = false
                if (id != null) {
                    openArtist(id, name)
                } else {
                    scope.launch {
                        YtMusicRepository.findArtistPageId(name).getOrNull()
                            ?.let { found -> openArtist(found, name) }
                    }
                }
            },
            onOpenPlaybackSource = {
                val id = playerSong.playbackSourceId
                overlays.nowPlaying = false
                if (playerSong.playbackSourceType == PlaybackSourceType.BROWSE && id != null) {
                    openAlbum(id)
                }
            },
            onListenTogether = {
                overlays.nowPlaying = false
                overlays.listenTogether = true
            },
            lyrics = sharedLyrics,
            lyricsSource = sharedLyricsSource,
            lyricsProviderStates = providerStates,
            onSelectLyricsProvider = onSelectLyricsProvider,
            lyricsUnavailable = sharedLyricsUnavailable,
            lyricsOffsetOpen = false,
            onDismissLyricsOffset = {},
            windowWidth = windowWidth,
            windowHeight = windowHeight,
        )
        if (playerMenuOpen) {
            // Where the player's "…" sits: the right column's credits row, at
            // the right edge of the centred two-column layout.
            val columnEdge = minOf(windowWidth, 1100.dp) / 2 - 47.dp
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = columnEdge)
                    .size(34.dp),
            ) {
                DesktopSongMenuFor(
                    song = playerSong,
                    liked = current.videoId in library.likedIds,
                    actions = playerSongActions(current),
                    onToggleLike = { toggleLike(current) },
                    onRevertToOriginal = {
                        DesktopOriginalVersion.pin(current.videoId)
                        playbackEngine.reloadCurrent()
                    }.takeIf {
                        playback.streamSourceId != null &&
                            playback.streamSourceId != "youtube" &&
                            DesktopMusicSources.hasYouTubeOriginal(current) &&
                            !DesktopOriginalVersion.isPinned(current.videoId)
                    },
                    onUpgradeQuality = {
                        DesktopOriginalVersion.clear(current.videoId)
                        playbackEngine.reloadCurrent(forceSourceRefresh = true)
                    }.takeIf { DesktopOriginalVersion.isPinned(current.videoId) },
                    onDismiss = { onPlayerMenuOpenChange(false) },
                )
            }
        }
        val message by DesktopPlayerHost.messages.collectAsState()
        LaunchedEffect(message) {
            if (message != null) {
                delay(2_500)
                DesktopPlayerHost.messages.value = null
            }
        }
        message?.let { text ->
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
