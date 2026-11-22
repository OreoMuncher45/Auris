package com.music.bitchord.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.music.bitchord.data.TelemetryProvenance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.music.bitchord.playback.audio.usb.UsbDirectManager
import com.music.bitchord.playback.audio.usb.DirectUsbProbeResult
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
import com.music.bitchord.playback.audio.ContainerBitrate
import com.music.bitchord.playback.audio.DspChain
import com.music.bitchord.playback.audio.LocalBitrateReading
import com.music.bitchord.playback.audio.MeasuredBitrateMeter
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.cast.CastPlayback
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.CastStream
import com.music.bitchord.playback.audio.PrecisionAudioSink
import com.music.bitchord.playback.dsd.DsdExtractorsFactory
import com.music.bitchord.playback.audio.DirectAudioProbe
import com.music.bitchord.playback.audio.OutputNegotiator
import com.music.bitchord.playback.audio.PcmEncoding
import com.music.bitchord.playback.audio.SourceDescriptor
import com.music.bitchord.playback.audio.bluetooth.BluetoothAudioTracker
import com.music.bitchord.playback.audio.bluetooth.BluetoothTelemetry
import com.music.bitchord.MainActivity
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.partyQueueIndexOf
import com.music.bitchord.data.listentogether.partyUpcomingAfter
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.Downloads
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import com.music.bitchord.data.Http
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.discord.DiscordRPC
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.stats.ListeningRecorder
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.scrobbling.ScrobbleManager
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.smart.VersionAudioAligner
import com.music.bitchord.widget.MediaWidget
import com.music.bitchord.widget.MediaWidgetSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Locale
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody


/** Session command used by both the player UI and the media notification. */
const val ACTION_TOGGLE_AUTOPLAY = "com.music.bitchord.action.TOGGLE_AUTOPLAY"

/** Session command used to smoothly swap the current track's version (film vs release). */
const val ACTION_SWAP_VERSION = "com.music.bitchord.action.SWAP_VERSION"
const val EXTRA_SWAP_MEDIA_ITEM = "bitchord.swap.media_item"

/** Session command used by the media notification's and the 4×1 widget's Favorite button. */
const val ACTION_TOGGLE_FAVORITE = "com.music.bitchord.action.TOGGLE_FAVORITE"

/** Session command used by the media notification's and the 4×1 widget's Shuffle button. */
const val ACTION_TOGGLE_SHUFFLE = "com.music.bitchord.action.TOGGLE_SHUFFLE"

/** Session actions exposed to Android Auto for the track that is playing. */
const val ACTION_START_STATION = "com.music.bitchord.action.START_STATION"
const val ACTION_REVERT_TO_ORIGINAL = "com.music.bitchord.action.REVERT_TO_ORIGINAL"
const val ACTION_SWAP_TO_VERSION = "com.music.bitchord.action.SWAP_TO_VERSION"

/** Session commands bracketing an explicit radio queue replacement. */
const val ACTION_BEGIN_RADIO_QUEUE = "com.music.bitchord.action.BEGIN_RADIO_QUEUE"
const val ACTION_COMMIT_RADIO_QUEUE = "com.music.bitchord.action.COMMIT_RADIO_QUEUE"

/** Session command behind the player menu's "Upgrade quality". */
const val ACTION_UPGRADE_QUALITY = "com.music.bitchord.action.UPGRADE_QUALITY"

/**
 * App-side gate for explicit quality retries.
 *
 * Automatic upgrades never pass through this object. Returning the remaining
 * wait rather than a Boolean gives the log enough information to distinguish
 * a deliberate throttle from a search which failed to start.
 */
internal class ManualUpgradeThrottle(private val cooldownMs: Long) {
    private var lastAcceptedAtMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long): Long {
        val previous = lastAcceptedAtMs
        if (previous != null) {
            val remaining = cooldownMs - (nowMs - previous)
            if (remaining > 0) return remaining
        }
        lastAcceptedAtMs = nowMs
        return 0L
    }
}

/**
 * Session command carrying a rearrangement of the queue worked out by a
 * controller — see [QueueShuffle.reorderFromCommand].
 *
 * A command rather than the ordinary player call because the items a controller
 * can see have had their playback URIs stripped on the way out to it. The
 * permutation travels instead, and the session applies it to the items it holds.
 */
const val ACTION_REORDER_QUEUE = "com.music.bitchord.action.REORDER_QUEUE"

/** Where the rearrangement starts, and where each slot's new occupant stands now. */
const val EXTRA_REORDER_FROM = "bitchord.reorder.from"
const val EXTRA_REORDER_ORDER = "bitchord.reorder.order"

/**
 * Session command marking the span of a queue drag in the UI — see
 * [PartySync.beginQueueDrag]. Dragging a queue row sends a [Player.moveMediaItem]
 * per neighbour it crosses, same as before; what this brackets is only the
 * party publish those moves would otherwise trigger one at a time, so a jam
 * hears about the reorder once, when the row is dropped, not mid-drag.
 */
const val ACTION_QUEUE_DRAG = "com.music.bitchord.action.QUEUE_DRAG"
const val EXTRA_QUEUE_DRAG_ACTIVE = "bitchord.queueDrag.active"

/** A full first page for an explicitly requested station. */
private const val INITIAL_STATION_TRACKS = 24

/**
 * How long a version switch will sit on a not-yet-downloaded opening before
 * it gives up on the head — [com.music.bitchord.playback.AudioCache.awaitAnalysisHead]
 * both starts the fetch and waits on it, so this is the wait a listener sees
 * with the bar running. Shared by the two heads, which are fetched in
 * parallel, so it bounds the pair rather than being paid twice.
 */
private const val ALIGNMENT_HEAD_TIMEOUT_MS = 20_000L

/**
 * How long the cross-correlation itself may take once both heads are on disk.
 * It is pure computation over two forty-five second windows, so a run that
 * takes this long has run into contention rather than hard work, and the
 * caller swaps without a shift instead of holding the track any longer.
 *
 * The margin exists because a result that arrives *after* this deadline is
 * discarded whole: the field log showed a measurement succeed 10 ms past a
 * tighter budget, and the swap still went out with no shift even though the
 * offset had been computed and cached. Better a longer bar than a wrong
 * second.
 */
private const val ALIGNMENT_MEASURE_TIMEOUT_MS = 30_000L

private fun Song.canStartStation(): Boolean =
    videoId.isNotBlank() &&
        !videoId.startsWith("content://") &&
        !videoId.startsWith("file://")

/**
 * Background playback via Media3. A [MediaLibraryService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry a `bitchord://watch?v=<videoId>` URI. The actual stream
 * URL is resolved lazily by [ResolvingDataSource] the moment ExoPlayer opens
 * the item — stream URLs expire after a few hours, so resolving at play time
 * (on Media3's loader thread, hence runBlocking is safe) keeps queues valid.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val searchResults = ConcurrentHashMap<String, List<Song>>()
    private val searchBrowseResults = ConcurrentHashMap<String, List<BrowseItem>>()
    private val spotifySearchTracks = ConcurrentHashMap<String, List<com.music.bitchord.data.spotify.SpotifyTrack>>()
    private val spotifySearchPlaylists = ConcurrentHashMap<String, List<com.music.bitchord.data.spotify.SpotifyPlaylist>>()
    private val spotifySearchAlbums = ConcurrentHashMap<String, List<com.music.bitchord.data.spotify.SpotifyPlaylist>>()
    private val spotifyAlbumTracksCache = ConcurrentHashMap<String, List<com.music.bitchord.data.spotify.SpotifyTrack>>()
    private val spotifyApiJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val spotifyGqlMediaType = "application/json; charset=utf-8".toMediaType()
    private val songCache = ConcurrentHashMap<String, Song>()

    /**
     * [YtMusicRepository.recents] cached for Android Auto's browse tree.
     */
    private var cachedRecents: Pair<Long, List<Song>>? = null
    private val recentsMutex = Mutex()
    private val RECENTS_CACHE_TTL_MS = 30_000L

    private suspend fun cachedRecentsSongs(): List<Song> = recentsMutex.withLock {
        cachedRecents?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < RECENTS_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.recents().getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedRecents = SystemClock.elapsedRealtime() to songs
            songs.forEach { songCache[it.videoId] = it }
        }
        songs
    }

    /**
     * [YtMusicRepository.quickPicks] cached for Android Auto's browse tree.
     * Excludes listening history songs to guarantee strictly unique data.
     */
    private var cachedQuickPicks: Pair<Long, List<Song>>? = null
    private val quickPicksMutex = Mutex()
    private val QUICK_PICKS_CACHE_TTL_MS = 60_000L

    private suspend fun cachedQuickPicksSongs(): List<Song> = quickPicksMutex.withLock {
        cachedQuickPicks?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < QUICK_PICKS_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val recentsIds = cachedRecents?.second?.mapTo(HashSet()) { it.videoId } ?: emptySet()
        val songs = try {
            withTimeoutOrNull(5000L) {
                YtMusicRepository.quickPicks(excludeSongIds = recentsIds).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedQuickPicks = SystemClock.elapsedRealtime() to songs
            songs.forEach { songCache[it.videoId] = it }
        }
        songs
    }

    /**
     * [YtMusicRepository.browseSongs] for Liked Music cached for Android Auto's browse tree.
     */
    private var cachedLiked: Pair<Long, List<Song>>? = null
    private var cachedLikedCookie: String? = null
    private val likedMutex = Mutex()
    private val LIKED_CACHE_TTL_MS = 60_000L

    private suspend fun cachedLikedSongs(): List<Song> = likedMutex.withLock {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        if (cookie == null) {
            cachedLiked = null
            cachedLikedCookie = null
            return emptyList()
        }
        if (cachedLikedCookie != null && cachedLikedCookie != cookie) {
            cachedLiked = null
        }
        cachedLiked?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < LIKED_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC).getOrNull()?.songs?.ifEmpty { null }
                    ?: YtMusicRepository.browseSongs("LM").getOrNull()?.songs?.ifEmpty { null }
                    ?: run {
                        val libPlaylists = YtMusicRepository.libraryPlaylists().getOrNull()
                        val likedCard = libPlaylists?.firstOrNull {
                            it.browseId == "VLLM" || it.title.contains("liked", ignoreCase = true)
                        }
                        val likedBrowseId = likedCard?.browseId
                        if (likedBrowseId != null) {
                            YtMusicRepository.browseSongs(likedBrowseId).getOrNull()?.songs
                        } else null
                    }
                    ?: YtMusicRepository.browseSongs("FEmusic_liked_videos").getOrNull()?.songs
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedLiked = SystemClock.elapsedRealtime() to songs
            cachedLikedCookie = cookie
            songs.forEach { songCache[it.videoId] = it }
            LikeState.seedLiked(songs.mapTo(HashSet()) { it.videoId })
        }
        songs
    }

    private var cachedHome: Pair<Long, com.music.bitchord.data.model.HomeFeed>? = null
    private data class HomeBundle(
        val daylist: com.music.bitchord.data.spotify.SpotifyPlaylist?,
        val madeForYou: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
        val spRecent: List<com.music.bitchord.data.spotify.SpotifyTrack>,
        val spLibrary: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
        val quickPicks: List<Song>,
        val feed: com.music.bitchord.data.model.HomeFeed?,
    )

    private val homeMutex = Mutex()
    private val HOME_CACHE_TTL_MS = 60_000L
    private val HOME_NETWORK_TIMEOUT_MS = 6_000L

    private suspend fun cachedHomeFeed(): com.music.bitchord.data.model.HomeFeed? = homeMutex.withLock {
        cachedHome?.let { (at, feed) ->
            if (SystemClock.elapsedRealtime() - at < HOME_CACHE_TTL_MS) return feed
        }
        val feed = try {
            withTimeoutOrNull(HOME_NETWORK_TIMEOUT_MS) {
                YtMusicRepository.home().getOrNull()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        cachedHome = SystemClock.elapsedRealtime() to feed
        feed
    }

    private var cachedPlaylistCards: Pair<Long, List<ShelfItem>>? = null
    private var cachedPlaylistCookie: String? = null
    private val playlistsMutex = Mutex()
    private val PLAYLISTS_CACHE_TTL_MS = 60_000L

    private suspend fun fetchPlaylistCards(): List<ShelfItem> = try {
        withTimeoutOrNull(3_000L) {
            YtMusicRepository.libraryPlaylists().getOrNull()
                ?: YtMusicRepository.userPlaylists().getOrNull()?.map {
                    ShelfItem(
                        title = it.title,
                        subtitle = it.subtitle,
                        thumbnailUrl = it.thumbnailUrl,
                        videoId = null,
                        browseId = "VL${it.playlistId}",
                    )
                }
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun cachedPlaylistCards(): List<ShelfItem> = playlistsMutex.withLock {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        if (cookie == null) {
            cachedPlaylistCards = null
            cachedPlaylistCookie = null
            return emptyList()
        }
        if (cachedPlaylistCookie != null && cachedPlaylistCookie != cookie) {
            cachedPlaylistCards = null
        }
        cachedPlaylistCards?.let { (at, cards) ->
            if (SystemClock.elapsedRealtime() - at < PLAYLISTS_CACHE_TTL_MS && cards.isNotEmpty()) return cards
        }
        val cards = fetchPlaylistCards()
        if (cards.isNotEmpty()) {
            cachedPlaylistCards = SystemClock.elapsedRealtime() to cards
            cachedPlaylistCookie = cookie
        }
        cards
    }

    private fun snapshotRecents(): List<Song> = cachedRecents?.second.orEmpty()
    private fun snapshotQuickPicks(): List<Song> = cachedQuickPicks?.second.orEmpty()
    private fun snapshotLiked(): List<Song> {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        return if (cookie != null && cookie == cachedLikedCookie) cachedLiked?.second.orEmpty() else emptyList()
    }
    private fun snapshotPlaylistCards(): List<ShelfItem> {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        return if (cookie != null && cookie == cachedPlaylistCookie) cachedPlaylistCards?.second.orEmpty() else emptyList()
    }
    private fun snapshotHome(): com.music.bitchord.data.model.HomeFeed? = cachedHome?.second

    private fun isFresh(at: Long, ttlMs: Long): Boolean =
        SystemClock.elapsedRealtime() - at < ttlMs

    private fun isRecentsFresh(): Boolean =
        cachedRecents?.let { (at, songs) -> songs.isNotEmpty() && isFresh(at, RECENTS_CACHE_TTL_MS) } ?: false

    private fun isQuickPicksFresh(): Boolean =
        cachedQuickPicks?.let { (at, songs) -> songs.isNotEmpty() && isFresh(at, QUICK_PICKS_CACHE_TTL_MS) } ?: false

    private fun isLikedFresh(): Boolean =
        snapshotLiked().isNotEmpty() && cachedLiked?.let { (at, _) -> isFresh(at, LIKED_CACHE_TTL_MS) } == true

    private fun isPlaylistsFresh(): Boolean =
        snapshotPlaylistCards().isNotEmpty() && cachedPlaylistCards?.let { (at, _) -> isFresh(at, PLAYLISTS_CACHE_TTL_MS) } == true

    private fun isHomeFresh(): Boolean =
        cachedHome?.let { (at, _) -> isFresh(at, HOME_CACHE_TTL_MS) } ?: false

    private var cachedSpotifyPlaylists: Pair<Long, List<com.music.bitchord.data.spotify.SpotifyPlaylist>>? = null
    private var cachedSpotifyToken: String? = null
    private val spotifyPlaylistsMutex = Mutex()
    private val SPOTIFY_CACHE_TTL_MS = 60_000L
    private val spotifyTrackCache = ConcurrentHashMap<String, com.music.bitchord.data.spotify.SpotifyTrack>()
    private val spotifyPlaylistTracksCache = ConcurrentHashMap<String, List<com.music.bitchord.data.spotify.SpotifyTrack>>()
    private val spotifyMatchedCache = ConcurrentHashMap<String, Song>()

    private suspend fun cachedSpotifyPlaylists(): List<com.music.bitchord.data.spotify.SpotifyPlaylist> =
        spotifyPlaylistsMutex.withLock {
            val token = AppSettings.spotifySpdcToken.value
            if (token.isBlank()) {
                cachedSpotifyPlaylists = null
                cachedSpotifyToken = null
                return emptyList()
            }
            if (cachedSpotifyToken != null && cachedSpotifyToken != token) {
                cachedSpotifyPlaylists = null
            }
            cachedSpotifyPlaylists?.let { (at, list) ->
                if (SystemClock.elapsedRealtime() - at < SPOTIFY_CACHE_TTL_MS && list.isNotEmpty()) return list
            }
            val fetched = try {
                withTimeoutOrNull(4_000L) {
                    com.music.bitchord.data.spotify.SpotifyLibrary.playlists()
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (fetched.isNotEmpty()) {
                cachedSpotifyPlaylists = SystemClock.elapsedRealtime() to fetched
                cachedSpotifyToken = token
            }
            fetched
        }

    private fun snapshotSpotifyPlaylists(): List<com.music.bitchord.data.spotify.SpotifyPlaylist> {
        val token = AppSettings.spotifySpdcToken.value
        return if (token.isNotBlank() && token == cachedSpotifyToken) cachedSpotifyPlaylists?.second.orEmpty() else emptyList()
    }

    private fun isSpotifyFresh(): Boolean =
        snapshotSpotifyPlaylists().isNotEmpty() && cachedSpotifyPlaylists?.let { (at, _) -> isFresh(at, SPOTIFY_CACHE_TTL_MS) } == true

    private suspend fun loadSpotifyPlaylistTracks(playlistId: String): List<com.music.bitchord.data.spotify.SpotifyTrack> {
        spotifyPlaylistTracksCache[playlistId]?.takeIf { it.isNotEmpty() }?.let { return it }
        var partial = emptyList<com.music.bitchord.data.spotify.SpotifyTrack>()
        val full = try {
            withTimeoutOrNull(4_000L) {
                com.music.bitchord.data.spotify.SpotifyLibrary.tracks(playlistId) { pageTracks ->
                    partial = pageTracks
                }
            } ?: partial
        } catch (_: Exception) {
            partial
        }
        if (full.isNotEmpty()) {
            spotifyPlaylistTracksCache[playlistId] = full
            full.forEach { spotifyTrackCache[it.id] = it }
        }
        return full
    }

    private var cachedSpotifyDaylist: Pair<Long, com.music.bitchord.data.spotify.SpotifyPlaylist>? = null
    private val spotifyDaylistMutex = Mutex()
    private val SPOTIFY_DAYLIST_ID = "37i9dQZF1EP6YuccBxUcC1"
    private val SPOTIFY_DAYLIST_TTL_MS = 180_000L

    private var cachedSpotifyMadeForYou: Pair<Long, List<com.music.bitchord.data.spotify.SpotifyPlaylist>>? = null
    private val spotifyMadeForYouMutex = Mutex()
    private val SPOTIFY_MADE_FOR_YOU_TTL_MS = 180_000L

    private var cachedSpotifyRecentTracks: Pair<Long, List<com.music.bitchord.data.spotify.SpotifyTrack>>? = null
    private val spotifyRecentMutex = Mutex()
    private val SPOTIFY_RECENT_TTL_MS = 60_000L

    private suspend fun spotifyApiHeaders(): Map<String, String>? {
        if (AppSettings.spotifySpdcToken.value.isBlank()) return null
        val token = try {
            com.music.bitchord.data.canvas.SpotifyToken.accessToken()
        } catch (_: Exception) {
            null
        } ?: return null
        return buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", com.music.bitchord.data.canvas.CANVAS_UA)
            try {
                com.music.bitchord.data.canvas.SpotifyToken.clientToken()?.let { put("Client-Token", it) }
            } catch (_: Exception) {}
        }
    }

    private fun spotifyGetJson(url: String, headers: Map<String, String>): JsonObject? {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val payload = Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        if (payload.isBlank()) return null
        return runCatching { spotifyApiJson.parseToJsonElement(payload).jsonObject }.getOrNull()
    }

    private fun JsonObject.spObj(key: String): JsonObject? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.spArr(key: String): JsonArray? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

    private fun JsonObject.spStr(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.spInt(key: String): Int? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull

    private fun parseSpotifyWebApiTrack(obj: JsonObject): com.music.bitchord.data.spotify.SpotifyTrack? {
        val id = obj.spStr("id") ?: return null
        val title = obj.spStr("name")?.takeIf { it.isNotBlank() } ?: return null
        val artist = obj.spArr("artists").orEmpty().mapNotNull { a ->
            a.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }?.spStr("name")
        }.joinToString(", ")
        val albumObj = obj.spObj("album")
        val albumName = albumObj?.spStr("name")
        val imageUrl = albumObj?.spArr("images")
            ?.firstOrNull()?.takeIf { it !is JsonNull }
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.spStr("url")
        val durationMs = obj.spInt("duration_ms") ?: 0
        return com.music.bitchord.data.spotify.SpotifyTrack(
            id = id,
            title = title,
            artist = artist,
            album = albumName,
            durationMs = durationMs,
            imageUrl = imageUrl,
        )
    }

    private fun parseSpotifyWebApiPlaylist(obj: JsonObject): com.music.bitchord.data.spotify.SpotifyPlaylist? {
        val id = obj.spStr("id") ?: return null
        val name = obj.spStr("name")?.takeIf { it.isNotBlank() } ?: return null
        val owner = obj.spObj("owner")?.spStr("display_name")
        val imageUrl = obj.spArr("images")
            ?.firstOrNull()?.takeIf { it !is JsonNull }
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.spStr("url")
        return com.music.bitchord.data.spotify.SpotifyPlaylist(
            id = id,
            name = name,
            owner = owner,
            imageUrl = imageUrl,
        )
    }

    private suspend fun cachedSpotifyDaylist(): com.music.bitchord.data.spotify.SpotifyPlaylist? =
        spotifyDaylistMutex.withLock {
            if (AppSettings.spotifySpdcToken.value.isBlank()) {
                cachedSpotifyDaylist = null
                return null
            }
            cachedSpotifyDaylist?.let { (at, pl) ->
                if (SystemClock.elapsedRealtime() - at < SPOTIFY_DAYLIST_TTL_MS) return pl
            }
            val headers = spotifyApiHeaders() ?: return null
            val fetched = try {
                withTimeoutOrNull(4_000L) {
                    val body = buildJsonObject {
                        putJsonObject("variables") {
                            put("uri", "spotify:playlist:$SPOTIFY_DAYLIST_ID")
                            put("offset", 0)
                            put("limit", 1)
                            put("enableWatchFeedEntrypoint", false)
                        }
                        put("operationName", "fetchPlaylist")
                        putJsonObject("extensions") {
                            putJsonObject("persistedQuery") {
                                put("version", 1)
                                put("sha256Hash", "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763")
                            }
                        }
                    }
                    val req = Request.Builder()
                        .url("https://api-partner.spotify.com/pathfinder/v2/query")
                        .post(body.toString().toRequestBody(spotifyGqlMediaType))
                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                        .build()
                    val text = Http.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withTimeoutOrNull null
                        resp.body?.string().orEmpty()
                    }
                    val pl = runCatching { spotifyApiJson.parseToJsonElement(text).jsonObject }
                        .getOrNull()?.spObj("data")?.spObj("playlistV2")
                        ?: return@withTimeoutOrNull null
                    val name = pl.spStr("name")?.takeIf { it.isNotBlank() } ?: "daylist"
                    val img = pl.spObj("images")?.spArr("items")?.firstOrNull()
                        ?.let { runCatching { it.jsonObject }.getOrNull() }
                        ?.spArr("sources")
                        ?.mapNotNull { s ->
                            val o = runCatching { s.jsonObject }.getOrNull() ?: return@mapNotNull null
                            val u = o.spStr("url") ?: return@mapNotNull null
                            u to (o.spInt("width") ?: 0)
                        }
                        ?.maxByOrNull { it.second }
                        ?.first
                    com.music.bitchord.data.spotify.SpotifyPlaylist(
                        id = SPOTIFY_DAYLIST_ID,
                        name = name,
                        owner = "Spotify",
                        imageUrl = img,
                    )
                }
            } catch (_: Exception) {
                null
            } ?: com.music.bitchord.data.spotify.SpotifyPlaylist(
                id = SPOTIFY_DAYLIST_ID,
                name = "daylist",
                owner = "Spotify",
                imageUrl = null,
            )
            cachedSpotifyDaylist = SystemClock.elapsedRealtime() to fetched
            fetched
        }

    private suspend fun cachedSpotifyMadeForYou(): List<com.music.bitchord.data.spotify.SpotifyPlaylist> =
        spotifyMadeForYouMutex.withLock {
            if (AppSettings.spotifySpdcToken.value.isBlank()) {
                cachedSpotifyMadeForYou = null
                return emptyList()
            }
            cachedSpotifyMadeForYou?.let { (at, list) ->
                if (SystemClock.elapsedRealtime() - at < SPOTIFY_MADE_FOR_YOU_TTL_MS && list.isNotEmpty()) return list
            }
            val headers = spotifyApiHeaders() ?: return emptyList()
            val queries = listOf("Daily Mix", "Discover Weekly", "Release Radar", "On Repeat")
            val collected = try {
                withTimeoutOrNull(5_000L) {
                    kotlinx.coroutines.coroutineScope {
                        queries.map { q ->
                            async(Dispatchers.IO) {
                                val enc = URLEncoder.encode(q, "UTF-8")
                                val root = spotifyGetJson(
                                    "https://api.spotify.com/v1/search?q=$enc&type=playlist&limit=10",
                                    headers,
                                ) ?: return@async emptyList()
                                root.spObj("playlists")?.spArr("items").orEmpty().mapNotNull { el ->
                                    val obj = el.takeIf { it !is JsonNull }
                                        ?.let { runCatching { it.jsonObject }.getOrNull() }
                                        ?: return@mapNotNull null
                                    val pl = parseSpotifyWebApiPlaylist(obj) ?: return@mapNotNull null
                                    val isSpotifyOwner = pl.owner.equals("Spotify", ignoreCase = true)
                                    val matchesMix = pl.name.contains("Daily Mix", ignoreCase = true) ||
                                        pl.name.contains("Discover Weekly", ignoreCase = true) ||
                                        pl.name.contains("Release Radar", ignoreCase = true) ||
                                        pl.name.contains("On Repeat", ignoreCase = true) ||
                                        pl.name.contains("Repeat Rewind", ignoreCase = true) ||
                                        pl.name.contains("daylist", ignoreCase = true)
                                    if (isSpotifyOwner && matchesMix) pl else null
                                }
                            }
                        }.flatMap { it.await() }.distinctBy { it.id }
                    }
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (collected.isNotEmpty()) {
                cachedSpotifyMadeForYou = SystemClock.elapsedRealtime() to collected
            }
            collected
        }

    private suspend fun cachedSpotifyRecentTracks(): List<com.music.bitchord.data.spotify.SpotifyTrack> =
        spotifyRecentMutex.withLock {
            if (AppSettings.spotifySpdcToken.value.isBlank()) {
                cachedSpotifyRecentTracks = null
                return emptyList()
            }
            cachedSpotifyRecentTracks?.let { (at, list) ->
                if (SystemClock.elapsedRealtime() - at < SPOTIFY_RECENT_TTL_MS && list.isNotEmpty()) return list
            }
            val headers = spotifyApiHeaders() ?: return emptyList()
            val tracks = try {
                withTimeoutOrNull(4_000L) {
                    val root = spotifyGetJson(
                        "https://api.spotify.com/v1/me/player/recently-played?limit=15",
                        headers,
                    ) ?: return@withTimeoutOrNull emptyList()
                    root.spArr("items").orEmpty().mapNotNull { el ->
                        val trackObj = el.takeIf { it !is JsonNull }
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?.spObj("track") ?: return@mapNotNull null
                        parseSpotifyWebApiTrack(trackObj)
                    }.distinctBy { it.id }
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (tracks.isNotEmpty()) {
                cachedSpotifyRecentTracks = SystemClock.elapsedRealtime() to tracks
                tracks.forEach { spotifyTrackCache[it.id] = it }
            }
            tracks
        }

    private suspend fun loadSpotifyAlbumTracks(albumId: String): List<com.music.bitchord.data.spotify.SpotifyTrack> {
        spotifyAlbumTracksCache[albumId]?.takeIf { it.isNotEmpty() }?.let { return it }
        val headers = spotifyApiHeaders() ?: return emptyList()
        val tracks = try {
            withTimeoutOrNull(4_500L) {
                val root = spotifyGetJson("https://api.spotify.com/v1/albums/$albumId", headers)
                    ?: return@withTimeoutOrNull emptyList()
                val albumName = root.spStr("name")
                val imageUrl = root.spArr("images")
                    ?.firstOrNull()?.takeIf { it !is JsonNull }
                    ?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?.spStr("url")
                root.spObj("tracks")?.spArr("items").orEmpty().mapNotNull { el ->
                    val obj = el.takeIf { it !is JsonNull }
                        ?.let { runCatching { it.jsonObject }.getOrNull() }
                        ?: return@mapNotNull null
                    val id = obj.spStr("id") ?: return@mapNotNull null
                    val title = obj.spStr("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val artist = obj.spArr("artists").orEmpty().mapNotNull { a ->
                        a.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }?.spStr("name")
                    }.joinToString(", ")
                    val durationMs = obj.spInt("duration_ms") ?: 0
                    com.music.bitchord.data.spotify.SpotifyTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = albumName,
                        durationMs = durationMs,
                        imageUrl = imageUrl,
                    )
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (tracks.isNotEmpty()) {
            spotifyAlbumTracksCache[albumId] = tracks
            tracks.forEach { spotifyTrackCache[it.id] = it }
        }
        return tracks
    }

    private suspend fun fetchSpotifyTrackById(trackId: String): com.music.bitchord.data.spotify.SpotifyTrack? {
        spotifyTrackCache[trackId]?.let { return it }
        val headers = spotifyApiHeaders() ?: return null
        val track = try {
            withTimeoutOrNull(4_000L) {
                val root = spotifyGetJson("https://api.spotify.com/v1/tracks/$trackId", headers)
                    ?: return@withTimeoutOrNull null
                parseSpotifyWebApiTrack(root)
            }
        } catch (_: Exception) {
            null
        }
        if (track != null) {
            spotifyTrackCache[track.id] = track
        }
        return track
    }

    private fun refreshFolderInBackground(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
        fetch: suspend () -> Int,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val freshCount = fetch()
                if (freshCount > 0) {
                    session.notifyChildrenChanged(browser, parentId, freshCount, params)
                }
            } catch (_: Exception) {
                // The existing snapshot remains usable when revalidation fails.
            }
        }
    }

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val bluetoothTracker by lazy { BluetoothAudioTracker(this) }
    private var currentAudioInputFormat: Format? = null
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            AudioRouting.forget()
            requestOutputReconfiguration()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { it.id == AudioRouting.selectedId.value }) {
                AudioRouting.forget()
            }
            requestOutputReconfiguration()
        }
    }

    private var player: ExoPlayer? = null
    private var spare: ExoPlayer? = null
    private var crossfade: CrossfadeController? = null
    private var configuredFloatOutput = false
    private var outputReconfigureJob: Job? = null

    private val spatialAudioProcessorA = SpatialAudioProcessor()
    private val spatialAudioProcessorB = SpatialAudioProcessor()
    private val equalizerProcessorA = EqualizerProcessor()
    private val equalizerProcessorB = EqualizerProcessor()
    private val transitionFilterA = TransitionFilterProcessor()
    private val transitionFilterB = TransitionFilterProcessor()

    private val loudnessA = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private val loudnessB = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private var loudnessRetryJob: Job? = null

    private val singProcessorA = VocalCutProcessor()
    private val singProcessorB = VocalCutProcessor()

    private var advertisedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var activeTrackIsDolbyAtmos = false

    private var activeFilter: TransitionFilterProcessor = transitionFilterA
    private var spareFilter: TransitionFilterProcessor = transitionFilterB

    private fun activeLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessA else loudnessB

    private fun spareLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessB else loudnessA

    private val trackAnalyzer = com.music.bitchord.playback.smart.TrackAnalyzer(this, AudioCache)
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private var lastPositionSeconds = 0L
    private var trackSelectedAt: Long? = null
    private var audibleMediaId: String? = null
    private var consecutiveErrorSkips = 0

    private var scrobbleManager: ScrobbleManager? = null
    private var listenBrainzSong: Song? = null
    private var listenBrainzStartMs: Long = 0L
    private var listenBrainzDurationMs: Long? = null

    private var discordRpc: DiscordRPC? = null
    private var discordUpdateJob: Job? = null
    private var discordPresenceUp = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val localBitrateCache = ConcurrentHashMap<String, LocalBitrateReading>()
    private val measuredBitrate = MeasuredBitrateMeter()
    private var streamResolver: ResolvingDataSource.Resolver? = null

    private val castPlayback = CastPlayback(
        scope = scope,
        localPlayer = { player },
        resolve = ::resolveForCast,
        say = { message -> Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() },
    )

    private var partySync: PartySync? = null

    private val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val autoplayCommand = SessionCommand(ACTION_TOGGLE_AUTOPLAY, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val startStationCommand = SessionCommand(ACTION_START_STATION, Bundle.EMPTY)
    private val revertToOriginalCommand = SessionCommand(ACTION_REVERT_TO_ORIGINAL, Bundle.EMPTY)
    private val swapToVersionCommand = SessionCommand(ACTION_SWAP_TO_VERSION, Bundle.EMPTY)
    private val beginRadioQueueCommand = SessionCommand(ACTION_BEGIN_RADIO_QUEUE, Bundle.EMPTY)
    private val commitRadioQueueCommand = SessionCommand(ACTION_COMMIT_RADIO_QUEUE, Bundle.EMPTY)
    private val upgradeQualityCommand = SessionCommand(ACTION_UPGRADE_QUALITY, Bundle.EMPTY)
    private val swapVersionCommand = SessionCommand(ACTION_SWAP_VERSION, Bundle.EMPTY)
    private val reorderQueueCommand = SessionCommand(ACTION_REORDER_QUEUE, Bundle.EMPTY)
    private val queueDragCommand = SessionCommand(ACTION_QUEUE_DRAG, Bundle.EMPTY)

    private var favoriteActionJob: Job? = null
    private var stationActionJob: Job? = null
    private var autoplayLoadJob: Job? = null
    private val manualUpgradeThrottle = ManualUpgradeThrottle(MANUAL_UPGRADE_COOLDOWN_MS)
    private var preferredPrefetchJob: Job? = null
    private var preferredPrefetchRequest: Pair<Boolean, List<String>>? = null
    private var autoplaySeed: String? = null
    private var persistedQueueStart = 0
    private var repeatAllStash: List<MediaItem> = emptyList()
    private var repeatAllStashSeed: String? = null
    private var lastRepeatMode = Player.REPEAT_MODE_OFF
    private val sessionSongHistory = mutableListOf<Song>()

    private var serviceLyricsJob: Job? = null
    private var serviceLyrics: List<LyricLine>? = null
    private var lastPublishedSubtitle: String? = null
    private var lyricsTickerJob: Job? = null

    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val activePlayer = this@PlaybackService.player ?: return
            if (player === activePlayer) updateAudioEffectSession(activePlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val exoPlayer = player ?: return
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "BitChord",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
                audibleMediaId = exoPlayer.currentMediaItem?.mediaId
                consecutiveErrorSkips = 0
            }
            if (isPlaying) registerCurrentPlay()
            if (isPlaying) prefetchAround(exoPlayer) else cancelPrefetch()
            if (isPlaying) lookForBetterCopy(exoPlayer)
            savePlaybackState(exoPlayer)
            publishWidgetState()

            val song = exoPlayer.currentMediaItem?.toSong()
            val durationMs = exoPlayer.duration.takeIf { it > 0 }
            scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)

            if (!isPlaying) ListeningRecorder.onStopped()

            if (isPlaying && song != null) {
                if (listenBrainzSong?.videoId != song.videoId || listenBrainzStartMs == 0L) {
                    listenBrainzSong = song
                    listenBrainzStartMs = System.currentTimeMillis()
                    listenBrainzDurationMs = durationMs
                } else if (listenBrainzDurationMs == null) {
                    listenBrainzDurationMs = durationMs
                }
                submitListenBrainzPlayingNow(song, exoPlayer.currentPosition, durationMs)
            }

            if (isPlaying) {
                pushDiscordPresence(exoPlayer)
                startLyricsTicker()
            } else {
                clearDiscordPresence()
                stopLyricsTicker()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) player?.let(castPlayback::onLocalStartedPlaying)
            publishWidgetState(playing = playWhenReady)
            partySync?.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)
                updateLyricSubtitle()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exoPlayer = player ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem?.mediaId != null &&
                mediaItem.mediaId == swappingMediaId
            ) {
                swappingMediaId = null
                return
            }

            castPlayback.onLocalTransition(mediaItem, reason)

            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
            QueueCoordinator.consumePlayedUserQueue(exoPlayer)

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                partySync?.onLocalIntent()
            }
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
            loadLyricsForCurrentTrack()
            if (exoPlayer.isPlaying) startLyricsTicker()
            refreshCustomLayouts()
        }

        override fun onPlayerError(error: PlaybackException) {
            val exoPlayer = player ?: return
            recoverFrom(error, exoPlayer)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val exoPlayer = player ?: return
            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                PlaybackTracker.onPlaybackFinished(lastPositionSeconds)
                lastPositionSeconds = 0
                val lastSong = listenBrainzSong
                if (lastSong != null && listenBrainzStartMs > 0L) {
                    val lastStart = listenBrainzStartMs
                    val lastDuration = listenBrainzDurationMs
                        ?: exoPlayer.duration.takeIf { it > 0 }
                    submitListenBrainzFinished(lastSong, lastStart, lastDuration)
                }
                listenBrainzSong = null
                listenBrainzStartMs = 0L
                listenBrainzDurationMs = null
                refreshAutoplayIfQueueEmpty()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val previous = lastRepeatMode
            lastRepeatMode = repeatMode
            castPlayback.onLocalRepeatChanged(repeatMode)
            player?.let { activeLoudness().nextMediaId = nextMediaIdOf(it) }
            when {
                repeatMode == Player.REPEAT_MODE_ALL -> stashAutoplayTracks()
                previous == Player.REPEAT_MODE_ALL -> restoreAutoplayTracks()
            }
            loadAutoplayForCurrentTrack()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val exoPlayer = player ?: return
            if (exoPlayer.isPlaying) prefetchAround(exoPlayer)
            activeLoudness().nextMediaId = nextMediaIdOf(exoPlayer)
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                castPlayback.onLocalQueueChanged()
                saveQueueSnapshot(exoPlayer)
                refreshCustomLayouts()
                refreshAutoplayIfQueueEmpty()
            }
        }
    }

    private val formatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            audioFormatFor = eventTime.mediaId()
            val measured = format.measure()
            val khz = measured.sampleRateHz
                ?.let { "%.1fkHz".format(Locale.ROOT, it / 1000.0) } ?: "?kHz"
            val kbps = format.bitrate.takeIf { it > 0 }
                ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
            val depth = measured.bitDepth?.let { "${it}-bit" } ?: "?-bit"
            val channels = measured.channels?.let { "${it}ch" } ?: "?ch"
            TrackLog.i(
                "DECODE",
                "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth $channels",
                about = audioFormatFor,
            )
            activeTrackIsDolbyAtmos = NerdStats.isDolbyAtmosMime(format.sampleMimeType)
            applySpatialAudioEnabled()
            publishNerdStats()
            currentAudioInputFormat = format
            applyOutputRoute()
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            swapCutAt = null
            TrackLog.d(
                "BitChord",
                "swap seam: ${SystemClock.elapsedRealtime() - cutAt}ms of silence",
                about = eventTime.mediaId(),
            )
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            val cutAt = swapCutAt ?: return
            if (state == Player.STATE_READY) {
                TrackLog.d(
                    "BitChord",
                    "swap leg: ready ${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                    about = eventTime.mediaId(),
                )
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            AudioOutputStatus.publishDecoder(decoderName)
            TrackLog.i(
                "AUDIO_OUT",
                "decoder=$decoderName initialized in ${initializationDurationMs}ms",
                about = eventTime.mediaId(),
            )
            val cutAt = swapCutAt ?: return
            TrackLog.d(
                "BitChord",
                "swap leg: $decoderName stood up in ${initializationDurationMs}ms, " +
                    "${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                about = eventTime.mediaId(),
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            AudioOutputStatus.publishAudioTrack(
                encoding = audioTrackConfig.encoding,
                sampleRateHz = audioTrackConfig.sampleRate,
                bufferSize = audioTrackConfig.bufferSize,
            )
            TrackLog.i(
                "AUDIO_OUT",
                "AudioTrack ${audioTrackConfig.sampleRate}Hz encoding=${audioTrackConfig.encoding}",
                about = eventTime.mediaId(),
            )
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA &&
                mediaLoadData.dataType != C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
            ) {
                return
            }
            measuredBitrate.onBytesLoaded(
                mediaId = eventTime.mediaId(),
                bytes = loadEventInfo.bytesLoaded,
                mediaStartMs = mediaLoadData.mediaStartTimeMs,
                mediaEndMs = mediaLoadData.mediaEndTimeMs,
            )
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            measuredBitrate.onDiscontinuity(eventTime.mediaId())
        }
    }

    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId

    override fun onCreate() {
        super.onCreate()

        if (com.music.bitchord.data.innertube.Innertube.cookie == null) {
            com.music.bitchord.data.innertube.Innertube.cookie = com.music.bitchord.auth.AuthStore(this).cookie
        }

        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrate.forget()
        QualityUpgrade.forgetLastSession()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        scope.launch {
            QueueShuffle.enabled
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            LikeState.overrides
                .map { it[player?.currentMediaItem?.mediaId] }
                .distinctUntilChanged()
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }

        bluetoothTracker.start()
        scope.launch {
            bluetoothTracker.telemetry.collect {
                applyOutputRoute()
            }
        }

        val streamResolver = ResolvingDataSource.Resolver { dataSpec ->
            val about = TrackLog.about(mediaIdIn(dataSpec.uri))
            if (dataSpec.uri.authority == "source") {
                val directYouTube =
                    dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1"
                val stream = runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) {
                        if (directYouTube) {
                            SourceResolver.youtubeFallback(SourceResolver.targetIn(dataSpec.uri))
                        } else {
                            SourceResolver.resolve(dataSpec.uri)
                        }
                    }
                } ?: throw java.io.IOException(
                    if (directYouTube) {
                        "No YouTube audio fallback matched ${dataSpec.uri.getQueryParameter("n")}"
                    } else {
                        "No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}"
                    },
                )
                val configId = dataSpec.uri.getQueryParameter("s")
                val sourceName = if (directYouTube) {
                    "YouTube"
                } else {
                    stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                        ?: configId?.let { SourceRegistry.config(it)?.displayName }
                        ?: "Source"
                }
                val trackParam = dataSpec.uri.getQueryParameter("t")
                val fullMediaId = mediaIdIn(dataSpec.uri)
                NerdStats.onSourceStream(fullMediaId ?: trackParam, stream.format, sourceName)
                if (fullMediaId != null) {
                    NerdStats.recordSource(fullMediaId, sourceName)
                }
                NerdStats.recordSource(trackParam, sourceName)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setHttpRequestHeaders(
                        if (directYouTube) StreamResolver.mediaHeadersFor(stream.url)
                        else stream.headers,
                    )
                    .build()
            }
            val videoId = dataSpec.uri.getQueryParameter("v")
                ?: return@Resolver dataSpec
            if (dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1") {
                QualityUpgrade.forget(videoId)
                StreamChoice.forget(videoId)
                NerdStats.clearDeclared(videoId)
                NerdStats.recordSource(videoId, "YouTube")
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Direct YouTube resolution timed out for $videoId", e)
                }
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                TrackLog.d("BitChord", "serving original YouTube version for $videoId", about = videoId)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            if (AutomixAnalysisSource.requestsYouTubeOpus(
                    dataSpec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
                )
            ) {
                NerdStats.recordSource(videoId, "YouTube")
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Automix Opus resolution timed out for $videoId", e)
                }
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
                val proving = QualityUpgrade.isAuditioning(videoId)
                val sourceName = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Upgrade"
                if (!proving) {
                    NerdStats.onSourceStream(videoId, upgraded.format, sourceName)
                    NerdStats.recordSource(videoId, sourceName)
                }
                TrackLog.d(
                    "BitChord",
                    "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                        "from ${Uri.parse(upgraded.url).host} " +
                        "at ${dataSpec.position} (${upgraded.format.summary})",
                    about = videoId,
                )
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(upgraded.url))
                    .setHttpRequestHeaders(upgraded.headers)
                    .build()
            }
            StreamChoice.of(videoId)?.let { serving ->
                val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
                if (serving.format != StreamFormat()) {
                    NerdStats.onSourceStream(videoId, serving.format, sourceName)
                }
                NerdStats.recordSource(videoId, sourceName)
                if (StreamChoice.isSubstitute(videoId) &&
                    serving.format.isLossless != true &&
                    QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)
                ) {
                    val pending = QualityUpgrade.settledForLess(
                        mediaId = videoId,
                        target = SourceResolver.targetIn(dataSpec.uri),
                        playing = serving.format,
                        servedBy = serving.sourceConfigId,
                    )
                    if (!pending) NerdStats.onLosslessRaceEnd(videoId)
                }
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
            if (!SourceResolver.canSubstituteForYouTube()) {
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Stream resolution timed out for $videoId", e)
                }
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                NerdStats.recordSource(videoId, "YouTube")
                StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            val won = runBlocking(about) {
                resolveWithModulePriority(
                    videoId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                )
            }
            when (won) {
                is Resolved.Module -> {
                    val sourceName = won.stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                        ?: "Module"
                    NerdStats.onSourceStream(videoId, won.stream.format, sourceName)
                    NerdStats.recordSource(videoId, sourceName)
                    StreamChoice.remember(videoId, won.stream, substituted = true)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.stream.url))
                        .setHttpRequestHeaders(won.stream.headers)
                        .build()
                }
                is Resolved.YouTube -> {
                    NerdStats.recordSource(videoId, "YouTube")
                    val headers = StreamResolver.mediaHeadersFor(won.url)
                    StreamChoice.remember(videoId, SourceStream(won.url, headers = headers), substituted = false)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.url))
                        .setHttpRequestHeaders(headers)
                        .build()
                }
            }
        }

        this.streamResolver = streamResolver

        val resolvingFactory = ResolvingDataSource.Factory(
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { dataSpec ->
            streamResolver.resolveDataSpec(dataSpec).also { resolved ->
                mediaIdIn(dataSpec.uri)?.let { StreamContainer.served(it, resolved.uri.toString()) }
                AudioCache.recordServed(dataSpec, resolved.uri.toString())
            }
        }
        val defaultDataSourceFactory = SmbDataSource.RoutingFactory(
            DefaultDataSource.Factory(this, resolvingFactory),
        )
        AudioCache.setUpstream(defaultDataSourceFactory)
        mediaSourceFactory = DefaultMediaSourceFactory(
            AudioCache.playbackFactory(defaultDataSourceFactory),
            DsdExtractorsFactory(),
        )
            .setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy())

        configuredFloatOutput = shouldEnableFloatOutput()
        val exoPlayer = buildPlayer(
            singProcessorA,
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val sparePlayer = buildPlayer(
            singProcessorB,
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = exoPlayer
        spare = sparePlayer
        sparePlayer.audioSessionId = exoPlayer.audioSessionId
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null)
        applyOutputRoute()

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        observeSettings()
        observeScrobbling()
        observeDiscord()
        watchSleepTimer()
        val restored = if (PartyPersonalQueueStash.hasStash()) {
            val stashed = PartyPersonalQueueStash.load()
            PartyPersonalQueueStash.clear()
            if (stashed != null && stashed.songs.isNotEmpty()) {
                persistedQueueStart = 0
                exoPlayer.setMediaItems(stashed.songs.map { it.toMediaItem() }, stashed.index, stashed.positionMs)
                true
            } else {
                restoreLastQueue(exoPlayer)
            }
        } else {
            restoreLastQueue(exoPlayer)
        }
        if (restored) {
            publishWidgetState()
        } else {
            MediaWidgetSnapshot.save(this, MediaWidgetSnapshot.EMPTY)
            MediaWidget.refresh(this)
        }
        lastRepeatMode = exoPlayer.repeatMode
        exoPlayer.addListener(playbackListener)
        partySync = PartySync(scope) { player }.also { it.start() }
        scope.launch {
            ListenTogether.state
                .map { state -> Triple(state.code, state.playback.autoplayEnabled, autoplaySupplierId(state)) }
                .distinctUntilChanged()
                .collectLatest { (code, enabled, supplierId) ->
                    if (code != null && enabled && supplierId == ListenTogether.state.value.you?.memberId) {
                        autoplayLoadJob?.cancel()
                        autoplayLoadJob = null
                        autoplaySeed = null
                        loadAutoplayForCurrentTrack()
                    }
                }
        }
        loadAutoplayForCurrentTrack()

        exoPlayer.addAnalyticsListener(formatListener)
        reportProgress()

        val controller = createCrossfadeController()
        crossfade = controller
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            newSessionPlayer(exoPlayer, controller),
            MediaLibraryCallback(),
        )
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()
        refreshCustomLayouts()

        CastController.ensureStarted(this)
        CastController.attach(castPlayback)
    }

    private fun newSessionPlayer(player: Player, crossfade: CrossfadeController) = SessionPlayer(
        player,
        crossfade,
        onUserIntent = { partySync?.onLocalIntent() },
        deferPlayToParty = { partySync?.shouldDeferPlay() == true },
        lockedTransport = { playing -> partySync?.onLockedTransport(playing) == true },
        cast = castPlayback,
    ) { lastPublishedSubtitle }

    private suspend fun resolveForCast(item: MediaItem): CastStream? = withContext(Dispatchers.IO) {
        val uri = item.localConfiguration?.uri ?: return@withContext null
        val resolver = streamResolver ?: return@withContext null
        val resolved = resolver.resolveDataSpec(DataSpec(uri)).uri
        if (resolved.scheme != "http" && resolved.scheme != "https") {
            null
        } else {
            CastStream(resolved.toString(), CastPlayback.mimeTypeOf(resolved))
        }
    }

    private fun createCrossfadeController() = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    spareFilter.setCutoffs(lowPassHz, highPassHz)

                override fun incomingLeadMs(): Long = activeFilter.leadUs / 1000

                override fun outgoingLeadMs(): Long = spareFilter.leadUs / 1000

                override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    activeFilter.setEcho(delaySeconds, send, dry)

                override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    spareFilter.setEcho(delaySeconds, send, dry)

                override fun parkEchoes() {
                    transitionFilterA.parkEcho()
                    transitionFilterB.parkEcho()
                }
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
            onArmIncoming = { item, nextId -> spareLoudness().track(item.mediaId, nextId) },
            onBlendHeadroom = { trim ->
                loudnessA.setBlendTrim(trim)
                loudnessB.setBlendTrim(trim)
            },
            versionSwapActive = { versionSwapJob?.isActive == true },
        )

    private fun notificationButtons(): List<CommandButton> {
        val favorite = CommandButton.Builder(
            if (LikeState.overrides.value[player?.currentMediaItem?.mediaId] == LikeStatus.LIKE) {
                CommandButton.ICON_HEART_FILLED
            } else {
                CommandButton.ICON_HEART_UNFILLED
            },
        )
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val shuffleEnabled = QueueShuffle.enabled.value
        val shuffle = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOf(favorite, shuffle)
    }

    private fun carButtons(): List<CommandButton> {
        val current = player?.currentMediaItem?.toSong()
        val station = current
            ?.takeIf { it.canStartStation() }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setSessionCommand(startStationCommand)
                    .setDisplayName(getString(R.string.start_radio))
                    .build()
            }
        val revert = current
            ?.takeIf {
                it.hasYouTubeOriginal() &&
                    it.localUri == null &&
                    !OriginalVersion.isPinned(it.videoId)
            }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_SYNC)
                    .setSessionCommand(revertToOriginalCommand)
                    .setDisplayName(getString(R.string.revert_to_original))
                    .build()
            }
        return notificationButtons() + listOfNotNull(station, revert)
    }

    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    private fun refreshCustomLayouts() {
        val session = mediaSession ?: return
        val car = carButtons()
        session.setCustomLayout(car)
        session.getMediaNotificationControllerInfo()?.let {
            session.setCustomLayout(it, if (carControllers.isEmpty()) notificationButtons() else car)
        }
    }

    private fun startStationFromSession() {
        val originalPlayer = player ?: return
        val currentItem = originalPlayer.currentMediaItem ?: return
        val seed = currentItem.toSong().takeIf { it.canStartStation() } ?: return
        val originalManualQueue = (0 until originalPlayer.mediaItemCount)
            .map { originalPlayer.getMediaItemAt(it) }
            .filterNot { it.fromAutoplay }
            .map { it.mediaId }

        stationActionJob?.cancel()
        stationActionJob = scope.launch {
            val radioSeed = seed.copy(radioName = seed.title)
            val related = loadAutoplayTracks(
                existing = listOf(radioSeed),
                seedSong = radioSeed,
                limit = INITIAL_STATION_TRACKS,
            ).getOrElse {
                TrackLog.w(
                    "BitChord",
                    "Android Auto station failed: ${it.message}",
                    about = seed.videoId,
                )
                return@launch
            }
            if (related.isEmpty()) return@launch

            val activePlayer = player ?: return@launch
            val activeManualQueue = (0 until activePlayer.mediaItemCount)
                .map { activePlayer.getMediaItemAt(it) }
                .filterNot { it.fromAutoplay }
                .map { it.mediaId }
            if (activePlayer.currentMediaItem?.mediaId != currentItem.mediaId ||
                activeManualQueue != originalManualQueue
            ) {
                return@launch
            }

            beginRadioQueue()
            val currentIndex = activePlayer.currentMediaItemIndex
            if (currentIndex + 1 < activePlayer.mediaItemCount) {
                activePlayer.removeMediaItems(currentIndex + 1, activePlayer.mediaItemCount)
            }
            if (currentIndex > 0) activePlayer.removeMediaItems(0, currentIndex)
            activePlayer.addMediaItems(1, related.map { it.toMediaItem() })
            saveQueueSnapshotImmediately(activePlayer)
        }
    }

    private var versionSwapJob: Job? = null
    private var versionSwapGeneration = 0

    private fun smoothSwapCurrentTrackVersion(
        targetMediaItem: MediaItem,
        onSwapCommitted: (() -> Unit)? = null,
    ) {
        val activePlayer = player ?: return
        val currentIndex = activePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until activePlayer.mediaItemCount) return
        val currentItem = activePlayer.currentMediaItem ?: return
        if (ListenTogether.state.value.inParty) {
            TrackLog.d("BitChord", "version swap ignored: in a Listen Together party", about = currentItem.mediaId)
            return
        }
        if (crossfade?.isTransitioning() == true) {
            TrackLog.d("BitChord", "version swap deferred: Automix is mid-transition", about = currentItem.mediaId)
            return
        }

        val sourceSong = currentItem.toSong()
        val targetSong = targetMediaItem.toSong()
        val smartAlignEnabled = AppSettings.smartVersionAlignment.value

        versionSwapJob?.cancel()
        val generation = ++versionSwapGeneration
        versionSwapJob = scope.launch(Dispatchers.Main) {
            val mediaId = targetMediaItem.mediaId
            swappingMediaId = mediaId
            if (smartAlignEnabled) {
                AppSettings.versionAlignmentInProgress.value = true
            }
            try {
                val startPosition = activePlayer.currentPosition
                val alignmentOffsetMs = if (smartAlignEnabled) {
                    prepareVersionAlignment(sourceSong, targetSong, startPosition)
                } else {
                    0L
                }

                if (player !== activePlayer ||
                    activePlayer.currentMediaItemIndex != currentIndex ||
                    activePlayer.currentMediaItem?.mediaId != sourceSong.videoId
                ) {
                    swappingMediaId = null
                    return@launch
                }

                val standbyPlayer = spare
                val canCrossfade = standbyPlayer != null &&
                    (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_READY)

                if (!canCrossfade || standbyPlayer == null) {
                    val pos = (activePlayer.currentPosition + alignmentOffsetMs).coerceAtLeast(0L)
                    val wasPlaying = activePlayer.playWhenReady
                    activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                    activePlayer.seekTo(currentIndex, pos)
                    if (wasPlaying) activePlayer.play()
                    onSwapCommitted?.invoke()
                    refreshCustomLayouts()
                    swappingMediaId = null
                    return@launch
                }

                val newItems = (0 until activePlayer.mediaItemCount).map { i ->
                    if (i == currentIndex) targetMediaItem else activePlayer.getMediaItemAt(i)
                }
                val wasPlaying = activePlayer.playWhenReady
                val alignedStartPos = (activePlayer.currentPosition + alignmentOffsetMs).coerceAtLeast(0L)

                standbyPlayer.stop()
                standbyPlayer.clearMediaItems()
                standbyPlayer.skipSilenceEnabled = activePlayer.skipSilenceEnabled
                standbyPlayer.repeatMode = activePlayer.repeatMode
                standbyPlayer.shuffleModeEnabled = activePlayer.shuffleModeEnabled
                standbyPlayer.setPlaybackSpeed(AppSettings.playbackSpeed.value)
                standbyPlayer.volume = 0f
                standbyPlayer.setMediaItems(newItems, currentIndex, alignedStartPos)
                spareLoudness().track(mediaId, nextMediaIdOf(standbyPlayer))
                standbyPlayer.playWhenReady = false
                standbyPlayer.prepare()

                val timeoutAt = SystemClock.elapsedRealtime() + 7_000L
                var isReady = false
                while (isActive && SystemClock.elapsedRealtime() < timeoutAt) {
                    if (player !== activePlayer ||
                        activePlayer.currentMediaItemIndex != currentIndex
                    ) {
                        standbyPlayer.stop()
                        standbyPlayer.clearMediaItems()
                        standbyPlayer.volume = 1f
                        swappingMediaId = null
                        return@launch
                    }

                    if (standbyPlayer.playbackState == Player.STATE_READY) {
                        isReady = true
                        break
                    }
                    if (standbyPlayer.playbackState == Player.STATE_IDLE && standbyPlayer.playerError != null) {
                        break
                    }
                    delay(20)
                }

                if (!isReady) {
                    TrackLog.w("BitChord", "version swap standby player failed to prepare, applying directly", about = mediaId)
                    standbyPlayer.stop()
                    standbyPlayer.clearMediaItems()
                    standbyPlayer.volume = 1f
                    swappingMediaId = null
                    val pos = (activePlayer.currentPosition + alignmentOffsetMs).coerceAtLeast(0L)
                    activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                    activePlayer.seekTo(currentIndex, pos)
                    if (wasPlaying) activePlayer.play()
                    onSwapCommitted?.invoke()
                    onTrackBecameCurrent(
                        targetMediaItem,
                        previousEnded = false,
                        reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                        alreadyAudible = true,
                    )
                    refreshCustomLayouts()
                    return@launch
                }

                val livePos = activePlayer.currentPosition
                val targetLivePos = (livePos + alignmentOffsetMs).coerceAtLeast(0L)
                standbyPlayer.seekTo(currentIndex, targetLivePos)

                val willPlay = wasPlaying && activePlayer.playWhenReady
                if (willPlay) {
                    standbyPlayer.play()
                    val runningTimeout = SystemClock.elapsedRealtime() + 600L
                    while (isActive && !standbyPlayer.isPlaying && SystemClock.elapsedRealtime() < runningTimeout) {
                        delay(10)
                    }
                    if (standbyPlayer.isPlaying) {
                        standbyPlayer.seekTo(
                            currentIndex,
                            (activePlayer.currentPosition + alignmentOffsetMs).coerceAtLeast(0L),
                        )
                        val resyncTimeout = SystemClock.elapsedRealtime() + 300L
                        while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < resyncTimeout) {
                            delay(10)
                        }
                    }
                } else {
                    val seekTimeout = SystemClock.elapsedRealtime() + 400L
                    while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < seekTimeout) {
                        delay(10)
                    }
                }

                val swapCrossfadeMs = 550L
                val fadeStart = SystemClock.elapsedRealtime()
                if (!smartAlignEnabled) {
                    AppSettings.smartMixInProgress.value = true
                }

                try {
                    while (isActive) {
                        val elapsed = SystemClock.elapsedRealtime() - fadeStart
                        val progress = (elapsed.toFloat() / swapCrossfadeMs).coerceIn(0f, 1f)

                        if (player !== activePlayer || activePlayer.currentMediaItemIndex != currentIndex) {
                            standbyPlayer.stop()
                            standbyPlayer.clearMediaItems()
                            standbyPlayer.volume = 1f
                            activePlayer.volume = 1f
                            swappingMediaId = null
                            return@launch
                        }

                        if (standbyPlayer.playWhenReady != activePlayer.playWhenReady) {
                            standbyPlayer.playWhenReady = activePlayer.playWhenReady
                        }

                        val inGain = sin(progress * (PI / 2.0)).toFloat()
                        val outGain = cos(progress * (PI / 2.0)).toFloat()
                        standbyPlayer.volume = inGain
                        activePlayer.volume = outGain
                        val swapTrim = 1f / sqrt((inGain + outGain).coerceAtLeast(1f))
                        loudnessA.setBlendTrim(swapTrim)
                        loudnessB.setBlendTrim(swapTrim)

                        if (progress >= 1f) break
                        delay(16)
                    }

                    adoptPlayerForVersionSwap(outgoing = activePlayer, incoming = standbyPlayer)
                    onSwapCommitted?.invoke()
                } finally {
                    loudnessA.setBlendTrim(1f)
                    loudnessB.setBlendTrim(1f)
                    if (!smartAlignEnabled) {
                        AppSettings.smartMixInProgress.value = false
                    }
                }
            } finally {
                if (generation == versionSwapGeneration) {
                    AppSettings.versionAlignmentInProgress.value = false
                }
            }
        }
    }

    private val versionAlignmentMeasurements = ConcurrentHashMap<Pair<String, String>, Deferred<Long?>>()

    private fun measureVersionAlignmentAsync(sourceSong: Song, targetSong: Song): Deferred<Long?> {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId
        val key = sourceId to targetId
        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { return CompletableDeferred(it) }
        versionAlignmentMeasurements[key]?.let { return it }
        val deferred = scope.async(Dispatchers.Main) {
            try {
                val sourceUri = Uri.parse(sourceSong.localUri ?: AutomixAnalysisSource.opusUri(sourceId))
                val targetUri = Uri.parse(targetSong.localUri ?: AutomixAnalysisSource.opusUri(targetId))
                withContext(Dispatchers.IO) {
                    val sourceHead = async {
                        sourceSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(sourceUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val targetHead = async {
                        targetSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(targetUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val sourceReady = sourceHead.await()
                    val targetReady = targetHead.await()
                    if (!sourceReady || !targetReady) {
                        TrackLog.w(
                            "BitChord",
                            "version alignment head short on disk (source=$sourceReady, " +
                                "target=$targetReady) for '${sourceSong.title}'",
                            sourceId,
                        )
                    }
                }
                withTimeoutOrNull(ALIGNMENT_MEASURE_TIMEOUT_MS) {
                    VersionAudioAligner.findOffsetMs(
                        context = this@PlaybackService,
                        sourceSong = sourceSong,
                        targetSong = targetSong,
                    )
                }
            } finally {
                versionAlignmentMeasurements.remove(key)
            }
        }
        versionAlignmentMeasurements[key] = deferred
        return deferred
    }

    private suspend fun prepareVersionAlignment(
        sourceSong: Song,
        targetSong: Song,
        currentPosMs: Long,
    ): Long {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId

        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { cached ->
            val cachedToken = VersionAudioAligner.beginAttempt(sourceId, targetId)
            VersionAudioAligner.publishPhase(
                cachedToken,
                VersionAudioAligner.CutPhase.ALIGNED,
                cached,
                fromCache = true,
            )
            return cached
        }

        val token = VersionAudioAligner.beginAttempt(sourceId, targetId)
        try {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FETCHING)
            val deferred = measureVersionAlignmentAsync(sourceSong, targetSong)
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.MEASURING)
            val measured = deferred.await()
            if (measured == null) {
                TrackLog.w(
                    "BitChord",
                    "version alignment found nothing to measure for '${sourceSong.title}'; switching with no shift",
                    sourceId,
                )
                VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
                return 0L
            }
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.ALIGNED, measured)
            return measured
        } catch (e: CancellationException) {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
            throw e
        }
    }

    private fun adoptPlayerForVersionSwap(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)

        player = incoming
        spare = outgoing

        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter

        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))

        incoming.volume = 1f
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f

        swappingMediaId = null
        refreshCustomLayouts()

        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = false,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            alreadyAudible = true,
        )
    }

    private fun revertCurrentToOriginal() {
        val activePlayer = player ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return
        if (!song.hasYouTubeOriginal() ||
            song.localUri != null ||
            OriginalVersion.isPinned(song.videoId)
        ) {
            return
        }

        OriginalVersion.pin(song.videoId)
        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        swappingMediaId = song.videoId
        activePlayer.replaceMediaItem(index, song.toDirectYouTubeMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshCustomLayouts()
    }

    private fun swapCurrentToVersion(args: Bundle) {
        val activePlayer = player ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return

        val targetVideoId = args.getString("targetVideoId")
        val targetIsVideo = args.getBoolean("targetIsVideo", false)

        if (targetVideoId == null || targetVideoId == song.videoId) return

        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        swappingMediaId = song.videoId

        val targetSong = song.copy(
            videoId = targetVideoId,
            isVideo = targetIsVideo,
            isVideoOrigin = if (targetIsVideo) false else song.isVideoOrigin,
        )

        activePlayer.replaceMediaItem(index, targetSong.toMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshCustomLayouts()
    }

    private fun toggleShuffleFromSession() {
        player?.let(QueueShuffle::toggle)
        refreshCustomLayouts()
    }

    private fun toggleAutoplayFromNotification() {
        val party = ListenTogether.state.value
        val enabled = if (party.inParty) !party.playback.autoplayEnabled else !AppSettings.autoplay.value
        AppSettings.setAutoplay(enabled)
        if (party.inParty) {
            ListenTogether.setAutoplay(enabled)
            refreshCustomLayouts()
            return
        }
        if (enabled) {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
        } else {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            dropAutoplayTracksFromQueue()
            repeatAllStash = emptyList()
            repeatAllStashSeed = null
        }
        refreshCustomLayouts()
    }

    private fun loadAutoplayForCurrentTrack() {
        val exoPlayer = player ?: return
        val party = ListenTogether.state.value
        if (!autoplayEnabled(party) ||
            exoPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
            (party.inParty && autoplaySupplierId(party) != party.you?.memberId)
        ) {
            return
        }
        val current = exoPlayer.currentMediaItem?.toSong() ?: return
        if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += current
        val queuedAutoplay = partyAutoplayWaiting(party, current.videoId)
            ?: (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
                .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        val needed = MAX_QUEUED_AUTOPLAY - queuedAutoplay
        if (needed <= 0) return
        if (autoplaySeed == current.videoId) return
        autoplaySeed = current.videoId
        autoplayLoadJob = scope.launch {
            var remaining = needed
            var emptyRefreshesRemaining = MAX_AUTOPLAY_EMPTY_REFRESHES
            while (isActive) {
                val activePlayer = player ?: return@launch
                val activeParty = ListenTogether.state.value
                if (!autoplayEnabled(activeParty) ||
                    activePlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    activePlayer.currentMediaItem?.mediaId != current.videoId ||
                    (activeParty.inParty && autoplaySupplierId(activeParty) != activeParty.you?.memberId)
                ) {
                    return@launch
                }
                val queueSongs = (0 until activePlayer.mediaItemCount)
                    .map { activePlayer.getMediaItemAt(it).toSong() } +
                    activeParty.queue.items.takeIf { activeParty.inParty }.orEmpty().map { it.toSong() }
                val existing = if (AppSettings.dontRepeatSuggestions.value) {
                    queueSongs + sessionSongHistory
                } else {
                    queueSongs
                }
                val result = loadAutoplayTracks(existing, current, remaining)
                val resolved = result.getOrElse {
                    TrackLog.w(
                        "BitChord",
                        "notification autoplay failed: ${it.message}",
                        about = current.videoId,
                    )
                    emptyList()
                }
                val latestPlayer = player ?: return@launch
                val latestParty = ListenTogether.state.value
                if (!autoplayEnabled(latestParty) ||
                    latestPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    latestPlayer.currentMediaItem?.mediaId != current.videoId ||
                    (latestParty.inParty && autoplaySupplierId(latestParty) != latestParty.you?.memberId)
                ) {
                    return@launch
                }
                if (resolved.isNotEmpty()) {
                    if (latestParty.inParty) {
                        val landed = topUpPartyAutoplay(current.videoId, resolved)
                        if (!landed) {
                            TrackLog.w(
                                "BitChord",
                                "party did not take AutoPlay's tracks; releasing the seed to retry",
                                about = current.videoId,
                            )
                            autoplaySeed = null
                            return@launch
                        }
                    } else {
                        latestPlayer.addMediaItems(resolved.map { it.toMediaItem() })
                    }
                    if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += resolved
                    return@launch
                }

                val at = latestPlayer.currentMediaItemIndex
                if (at < 0 || at != latestPlayer.mediaItemCount - 1) return@launch
                if (emptyRefreshesRemaining-- <= 0) return@launch
                delay(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
                remaining = MAX_QUEUED_AUTOPLAY
            }
        }
    }

    private fun partyAutoplayWaiting(party: ListenTogether.State, videoId: String): Int? {
        if (!party.inParty) return null
        if (partyQueueIndexOf(party.queue, party.playback, videoId) < 0) return null
        return partyUpcomingAfter(party.queue, party.playback, videoId).count { it.fromAutoplay }
    }

    private val partyAutoplayLock = Mutex()

    private suspend fun topUpPartyAutoplay(currentId: String, suggestions: List<Song>): Boolean =
        partyAutoplayLock.withLock {
            withContext(NonCancellable) {
                val party = ListenTogether.state.value
                val waiting = partyUpcomingAfter(party.queue, party.playback, currentId)
                val waitingIds = waiting.mapTo(HashSet()) { it.videoId }
                val room = MAX_QUEUED_AUTOPLAY - waiting.count { it.fromAutoplay }
                val fresh = suggestions.filterNot { it.videoId in waitingIds }.take(room.coerceAtLeast(0))
                if (fresh.isEmpty()) return@withContext true
                ListenTogether.queueAdd(fresh.map { it.toPartyTrack(0L) })
                val added = fresh.first().videoId
                withTimeoutOrNull(PARTY_QUEUE_ECHO_TIMEOUT_MS) {
                    ListenTogether.state.first { state -> state.queue.items.any { it.videoId == added } }
                } != null
            }
        }

    private fun refreshAutoplayIfQueueEmpty() {
        val exoPlayer = player ?: return
        if (!autoplayQueueNeedsRefresh(
                enabled = autoplayEnabled(ListenTogether.state.value),
                repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
                currentIndex = exoPlayer.currentMediaItemIndex,
                itemCount = exoPlayer.mediaItemCount,
                loadInProgress = autoplayLoadJob?.isActive == true,
            )
        ) {
            return
        }
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }

    private fun autoplayEnabled(party: ListenTogether.State): Boolean =
        autoplayEnabledFor(party, AppSettings.autoplay.value)

    private fun dropAutoplayTracksFromQueue(): List<MediaItem> {
        val exoPlayer = player ?: return emptyList()
        val dropped = mutableListOf<MediaItem>()
        for (index in exoPlayer.mediaItemCount - 1 downTo exoPlayer.currentMediaItemIndex + 1) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!item.fromAutoplay) continue
            dropped += item
            exoPlayer.removeMediaItem(index)
        }
        return dropped.reversed()
    }

    private fun stashAutoplayTracks() {
        val exoPlayer = player ?: return
        if (repeatAllStash.isNotEmpty()) return
        val dropped = dropAutoplayTracksFromQueue()
        if (dropped.isEmpty()) return
        repeatAllStash = dropped
        repeatAllStashSeed = exoPlayer.currentMediaItem?.mediaId
    }

    private fun restoreAutoplayTracks() {
        val exoPlayer = player ?: return
        val stashed = repeatAllStash
        val seed = repeatAllStashSeed
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (stashed.isEmpty() || !autoplayEnabled(ListenTogether.state.value)) return
        if (exoPlayer.currentMediaItem?.mediaId != seed) return
        val present = (0 until exoPlayer.mediaItemCount)
            .mapTo(mutableSetOf()) { exoPlayer.getMediaItemAt(it).mediaId }
        val restored = stashed.filter { it.mediaId !in present }
        if (restored.isEmpty()) return
        exoPlayer.addMediaItems(restored)
    }

    private fun toggleFavoriteFromNotification(videoId: String) {
        favoriteActionJob?.cancel()
        val previous = LikeState.overrides.value[videoId] ?: LikeStatus.INDIFFERENT
        val target = if (previous == LikeStatus.LIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.LIKE
        }

        LikeState.set(videoId, target)
        refreshCustomLayouts()
        favoriteActionJob = scope.launch {
            YtMusicRepository.rate(videoId, target)
                .onFailure {
                    LikeState.set(videoId, previous)
                    refreshCustomLayouts()
                    TrackLog.w("BitChord", "notification favorite failed: ${it.message}", about = videoId)
                }
        }
    }

    private fun buildPlayer(
        sing: VocalCutProcessor,
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        filter: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(sing, spatial, equalizer, filter, loudness))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)
        player = incoming
        spare = outgoing
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))

        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )

        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }

    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    private fun updateAudioEffectSession(activePlayer: ExoPlayer) {
        val sessionId = activePlayer.audioSessionId
        val shouldAdvertise = activePlayer.playWhenReady &&
            (activePlayer.playbackState == Player.STATE_BUFFERING ||
                activePlayer.playbackState == Player.STATE_READY) &&
            sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0

        if (!shouldAdvertise) {
            closeAudioEffectSession()
            return
        }
        if (advertisedAudioEffectSessionId == sessionId) return

        closeAudioEffectSession()
        advertisedAudioEffectSessionId = sessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        val sessionId = advertisedAudioEffectSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        advertisedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun registerCurrentPlay() {
        player?.currentMediaItem?.mediaId?.let(PlaybackTracker::onPlaying)
    }

    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        currentAudioInputFormat = null
        NerdStats.onTrackTransition()
        AudioOutputStatus.onTrackTransition()
        mediaItem?.let { AudioCache.notePlayed(it.toSong(), it.localConfiguration?.uri) }
        scheduleForeignCachePurge()
        mediaItem?.mediaId?.let { id ->
            resolveLocalBitrate(id, mediaItem)
        }

        activeTrackIsDolbyAtmos = NerdStats.declaredFormat(mediaItem?.mediaId)?.isDolbyAtmos == true
        applySpatialAudioEnabled()

        setupLoudness(mediaItem?.mediaId)
        scheduleLoudnessRetry(mediaItem?.mediaId)

        val expiredHistory = queueHistoryTrimCount(exoPlayer.currentMediaItemIndex)
        if (expiredHistory > 0) {
            if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
                repeat(expiredHistory) {
                    exoPlayer.moveMediaItem(0, exoPlayer.mediaItemCount - 1)
                }
            } else {
                exoPlayer.removeMediaItems(0, expiredHistory)
            }
        }
        val becameCurrent = mediaItem?.mediaId
        if (becameCurrent == null || becameCurrent != retryingMediaId) {
            recoveries.clear()
        }
        retryingMediaId = null

        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "BitChord",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem?.mediaId,
            )
            audibleMediaId = mediaItem?.mediaId
            consecutiveErrorSkips = 0
        }
        mediaItem?.mediaId?.let(TrackLog::onTrackStarted)
        mediaItem?.mediaId?.let {
            QualityUpgrade.onPlaybackStarted(
                it,
                mediaItem.localConfiguration?.uri?.let(QualityUpgrade::cacheTag),
            )
        }
        TrackLog.d(
            "BitChord",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
        )

        PlaybackTracker.onTrackChanged(lastPositionSeconds)
        lastPositionSeconds = 0

        scrobbleManager?.onSongStop()
        ListeningRecorder.onStopped()
        val newSong = mediaItem?.toSong()
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        if (exoPlayer.isPlaying) {
            scrobbleManager?.onSongStart(newSong, durationMs)
        }

        val ended = previousEnded
        val prevSong = listenBrainzSong
        val prevStart = listenBrainzStartMs
        if (prevSong != null && ended && prevStart > 0L) {
            submitListenBrainzFinished(prevSong, prevStart, listenBrainzDurationMs)
        }
        listenBrainzSong = newSong
        listenBrainzStartMs = if (exoPlayer.isPlaying) System.currentTimeMillis() else 0L
        listenBrainzDurationMs = durationMs
        if (newSong != null && exoPlayer.isPlaying) {
            submitListenBrainzPlayingNow(newSong, 0L, durationMs)
        }

        NerdStats.current.value = null

        if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)

        if (ended && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        if (exoPlayer.isPlaying) registerCurrentPlay()
        savePlaybackState(exoPlayer)
        prefetchAround(exoPlayer)
        cancelStaleUpgradeJob()
        lookForBetterCopy(exoPlayer)
        publishWidgetState()
        loadLyricsForCurrentTrack()
        if (exoPlayer.isPlaying) startLyricsTicker()
    }

    private var upgradeJob: Job? = null
    private var upgradeFor: String? = null

    private fun offlineBlocksUpgrades(): Boolean {
        if (!AppSettings.effectiveOffline.value) return false
        TrackLog.d("BitChord", "offline mode: skipping the second look")
        return true
    }

    private fun cancelStaleUpgradeJob() {
        if (QualityUpgrade.isAuditioning(upgradeFor)) return
        upgradeJob?.cancel()
    }

    private val recoveries = mutableMapOf<String, Int>()
    private var retryingMediaId: String? = null

    private class PermanentAwareLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (isPermanent(loadErrorInfo.exception)) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        private fun isPermanent(error: Throwable?): Boolean {
            var cause = error
            var depth = 0
            while (cause != null && depth++ < CAUSE_DEPTH) {
                if (cause is StreamResolver.PermanentlyUnplayableException) return true
                cause = cause.cause?.takeIf { it !== cause }
            }
            return false
        }

        private companion object {
            const val CAUSE_DEPTH = 8
        }
    }

    private class CountingBandwidthMeter(private val delegate: BandwidthMeter) : BandwidthMeter by delegate {
        val bytes = AtomicLong()

        private val counter = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferInitializing(source, dataSpec, isNetwork)
            }

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferStart(source, dataSpec, isNetwork)
            }

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                bytes.addAndGet(bytesTransferred.toLong())
                delegate.transferListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferEnd(source, dataSpec, isNetwork)
            }
        }

        override fun getTransferListener(): TransferListener = counter
    }

    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val position = player.currentPosition.coerceAtLeast(0L)
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "BitChord",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
            restreamMissingLocalFile(player, item, uri, position)
        ) {
            return
        }
        if (AppSettings.effectiveOffline.value && Downloads.verifiedSavedUri(mediaId) == null) {
            TrackLog.d(
                "BitChord",
                "$mediaId has no downloaded copy while offline; moving down the queue",
                about = mediaId,
            )
            scope.launch(TrackLog.about(mediaId)) {
                withContext(Dispatchers.Main) {
                    skipPastUnplayable(mediaId, "offline — not downloaded")
                }
            }
            return
        }
        if (fallbackFailedAlternativeToYouTube(item, uri, position)) return
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            replayAsManifest(player, item, uri, position)
        ) {
            return
        }
        val verdict = permanentReason(error)
        val givingUp = verdict != null || attempts > MAX_RECOVERIES
        val neverStarted = audibleMediaId != mediaId
        if (verdict != null) {
            TrackLog.w("BitChord", "$mediaId cannot be played: $verdict", about = mediaId)
        } else if (givingUp) {
            TrackLog.w("BitChord", "$mediaId has failed $attempts times; leaving it alone", about = mediaId)
        }
        QualityUpgrade.forget(mediaId)
        val diedOnModuleStream = uri?.let(QualityUpgrade::cacheTag)
            ?.let { it == "hifi" || it.startsWith("hifi-") } == true
        if (diedOnModuleStream) {
            QualityUpgrade.refuseUpgrades(mediaId)
        }
        NerdStats.clearDeclared(mediaId)
        uri?.getQueryParameter("v")
            ?.takeIf { diedOnModuleStream || StreamChoice.isSubstitute(it) }
            ?.let { videoId ->
                StreamChoice.refuseSubstitutes(videoId)
                TrackLog.w(
                    "BitChord",
                    "$videoId broke on a substituted stream; YouTube serves it for now",
                    about = mediaId,
                )
                QualityUpgrade.refuseUpgrades(videoId)
            }
        uri?.getQueryParameter("v")?.let(StreamChoice::forget)
        scope.launch(TrackLog.about(mediaId)) {
            delay(RECOVERY_DELAY_MS)
            uri?.let { withContext(Dispatchers.IO) { AudioCache.discard(it) } }
            if (givingUp) {
                skipReason(verdict, error, neverStarted, attempts)?.let { reason ->
                    withContext(Dispatchers.Main) { skipPastUnplayable(mediaId, reason) }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = this@PlaybackService.player ?: return@withContext
                if (player.currentMediaItem?.mediaId != mediaId) return@withContext
                TrackLog.d("BitChord", "retrying $mediaId from ${position}ms")
                if (attempts == 1 && fallbackOnlineStreamToFile(player, mediaId, position)) return@withContext
                retryingMediaId = mediaId
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
            }
        }
    }

    private fun fallbackFailedAlternativeToYouTube(
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val playbackUri = uri ?: return false
        val videoId = playbackUri.getQueryParameter("v")
        val substituted = videoId?.let(StreamChoice::isSubstitute) == true
        if (!PlaybackFallback.isAlternative(playbackUri.toString(), substituted)) return false
        val fallback = item.toYouTubeFallbackMediaItem() ?: return false
        val mediaId = item.mediaId

        QualityUpgrade.forget(mediaId)
        QualityUpgrade.refuseUpgrades(mediaId)
        videoId?.let {
            StreamChoice.refuseSubstitutes(it)
            StreamChoice.forget(it)
        }
        NerdStats.clearDeclared(mediaId)
        recoveries.remove(mediaId)
        TrackLog.w(
            "BitChord",
            "$mediaId failed on a higher-quality source; falling back to YouTube audio",
            about = mediaId,
        )

        scope.launch(TrackLog.about(mediaId)) {
            delay(RECOVERY_DELAY_MS)
            withContext(Dispatchers.IO) { AudioCache.discard(playbackUri) }
            withContext(Dispatchers.Main) {
                val live = this@PlaybackService.player ?: return@withContext
                if (live.currentMediaItem?.mediaId != mediaId) return@withContext
                swappingMediaId = mediaId
                live.replaceMediaItem(live.currentMediaItemIndex, fallback)
                live.seekTo(live.currentMediaItemIndex, position)
                live.prepare()
            }
        }
        return true
    }

    private fun fallbackOnlineStreamToFile(player: ExoPlayer, mediaId: String, position: Long): Boolean {
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return false
        if (current.mediaId != mediaId) return false
        val scheme = current.localConfiguration?.uri?.scheme
        if (scheme == "file" || scheme == "content") return false
        if (Downloads.verifiedSavedUri(mediaId) == null) return false
        val fileItem = current.toSong().copy(localUri = null, localPath = null).toMediaItem()
        val fileScheme = fileItem.localConfiguration?.uri?.scheme
        if (fileScheme != "file" && fileScheme != "content") return false
        TrackLog.w(
            "BitChord",
            "$mediaId stream failed while the file is on disk; playing the file",
            about = mediaId,
        )
        swappingMediaId = mediaId
        player.replaceMediaItem(index, fileItem)
        player.seekTo(index, position)
        player.prepare()
        return true
    }

    private fun restreamMissingLocalFile(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val scheme = uri?.scheme
        if (scheme != "file" && scheme != "content") return false
        val mediaId = item.mediaId

        Downloads.forgetMissing(mediaId)
        val restreamed = item.toSong().copy(localUri = null, localPath = null).toMediaItem()
        if (restreamed.localConfiguration?.uri == uri) {
            TrackLog.w(
                "BitChord",
                "$mediaId is a local file that is gone and has no stream behind it",
                about = mediaId,
            )
            return false
        }

        TrackLog.w(
            "BitChord",
            "$mediaId was downloaded but $uri is gone; streaming it instead",
            about = mediaId,
        )
        recoveries.remove(mediaId)
        player.replaceMediaItem(player.currentMediaItemIndex, restreamed)
        player.seekTo(player.currentMediaItemIndex, position)
        player.prepare()
        return true
    }

    private fun replayAsManifest(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val mediaId = item.mediaId
        if (item.localConfiguration?.mimeType != null) return false
        val mime = StreamContainer.manifestServing(mediaId) ?: return false
        val playbackUri = uri ?: return false
        val reopenedUri = StreamContainer.markedForManifestReopen(playbackUri.toString(), mime)
        if (reopenedUri == playbackUri.toString()) return false

        TrackLog.w(
            "BitChord",
            "$mediaId was served a manifest through a progressive source; reopening it as $mime",
            about = mediaId,
        )
        recoveries.remove(mediaId)
        val declared = item.buildUpon()
            .setUri(reopenedUri)
            .setMimeType(mime)
            .build()
        scope.launch(TrackLog.about(mediaId)) {
            withContext(Dispatchers.IO) { AudioCache.discard(playbackUri) }
            withContext(Dispatchers.Main) {
                val live = this@PlaybackService.player ?: return@withContext
                if (live.currentMediaItem?.mediaId != mediaId) return@withContext
                live.replaceMediaItem(live.currentMediaItemIndex, declared)
                live.seekTo(live.currentMediaItemIndex, position)
                live.prepare()
            }
        }
        return true
    }

    private fun skipReason(
        verdict: String?,
        error: PlaybackException,
        neverStarted: Boolean,
        attempts: Int,
    ): String? {
        if (verdict != null) return verdict
        if (!neverStarted) return null
        if (consecutiveErrorSkips >= MAX_CONSECUTIVE_SKIPS) {
            TrackLog.w(
                "BitChord",
                "$MAX_CONSECUTIVE_SKIPS tracks in a row failed to start; " +
                    "this is the queue or the connection, not the track — stopping here",
            )
            return null
        }
        consecutiveErrorSkips++
        return "failed to start after $attempts attempts (${error.errorCodeName})"
    }

    private fun skipPastUnplayable(mediaId: String, reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) return
        if (!exoPlayer.hasNextMediaItem()) {
            TrackLog.w("BitChord", "$reason — and nothing after it in the queue", about = mediaId)
            return
        }
        TrackLog.w("BitChord", "$reason — skipping to the next track", about = mediaId)
        exoPlayer.seekToNextMediaItem()
        exoPlayer.prepare()
    }

    private fun permanentReason(error: Throwable?): String? {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < PERMANENT_CAUSE_DEPTH) {
            if (cause is StreamResolver.PermanentlyUnplayableException) {
                return cause.message ?: "This track cannot be played"
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return null
    }

    private var swappingMediaId: String? = null
    private var swapCutAt: Long? = null
    private var audioFormatFor: String? = null

    private fun lookForBetterCopy(player: ExoPlayer) {
        if (offlineBlocksUpgrades()) return
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val alreadyPending = QualityUpgrade.isPending(mediaId)
        val shelved = QualityUpgrade.shelvedFor(mediaId)
        if (shelved == null && !alreadyPending && !QualityUpgrade.couldStillUpgrade(mediaId, uri)) return
        if (upgradeJob?.isActive == true) {
            if (upgradeFor == mediaId) return
            cancelStaleUpgradeJob()
        }
        upgradeFor = mediaId
        if (alreadyPending) {
            TrackLog.d("BitChord", "looking again for a better copy of $mediaId", about = mediaId)
        }
        upgradeJob = scope.launch(TrackLog.about(mediaId)) {
            if (shelved != null) {
                TrackLog.d("BitChord", "re-offering the upgrade already proved for $mediaId")
                NerdStats.onLosslessRaceStart(mediaId)
                try {
                    swapIn(mediaId, shelved)
                } finally {
                    NerdStats.onLosslessRaceEnd(mediaId)
                }
                return@launch
            }
            val playingSeconds = withTimeoutOrNull(DURATION_SETTLE_MS) {
                while (true) {
                    val ms = withContext(Dispatchers.Main) {
                        this@PlaybackService.player
                            ?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                            ?.duration
                            ?: 0L
                    }
                    if (ms > 0) return@withTimeoutOrNull (ms / 1000).toInt()
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (!QualityUpgrade.isPending(mediaId) &&
                (uri == null || !adoptCachedTrack(mediaId, uri, playingSeconds))
            ) {
                return@launch
            }
            val better = withContext(Dispatchers.IO) {
                QualityUpgrade.lookAgain(mediaId, playingSeconds)
            } ?: return@launch
            try {
                swapIn(mediaId, better)
            } finally {
                NerdStats.onLosslessRaceEnd(mediaId)
            }
        }
    }

    private fun upgradeQualityNow() {
        val player = player ?: return
        if (offlineBlocksUpgrades()) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId in NerdStats.racingLossless.value ||
            (upgradeJob?.isActive == true && upgradeFor == mediaId)
        ) {
            TrackLog.d("BitChord", "manual upgrade ignored for $mediaId: upgrade already running", about = mediaId)
            return
        }
        val remaining = manualUpgradeThrottle.tryAcquire(SystemClock.elapsedRealtime())
        if (remaining > 0) {
            TrackLog.d(
                "BitChord",
                "manual upgrade ignored for $mediaId: ${remaining}ms cooldown remaining",
                about = mediaId,
            )
            return
        }
        OriginalVersion.unpin(mediaId)
        SourceRegistry.clearCompletedAddonTrackCalls()
        QualityUpgrade.askByHand(mediaId)
        TrackLog.d("BitChord", "upgrade asked for by hand for $mediaId", about = mediaId)
        lookForBetterCopy(player)
    }

    private suspend fun adoptCachedTrack(mediaId: String, uri: Uri, durationSec: Int?): Boolean {
        val format = withContext(Dispatchers.Main) {
            player
                ?.takeIf { it.currentMediaItem?.mediaId == mediaId && audioFormatFor == mediaId }
                ?.audioFormat
        } ?: return false
        val mime = format.sampleMimeType ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val downloaded = com.music.bitchord.download.Downloads.savedUri(this, videoId) != null
        if (downloaded) return false
        val target = SourceResolver.targetIn(uri)
        val expectedSec = target.durationSec
        if (TrackMatcher.isSevereMismatch(expectedSec, durationSec)) {
            TrackLog.w(
                "BitChord",
                "cached rendition duration mismatch: playing ${durationSec}s vs catalogue ${expectedSec}s for $videoId; discarding cache rendition",
                about = mediaId,
            )
            withContext(Dispatchers.IO) { AudioCache.discardRendition(uri) }
        }
        return QualityUpgrade.adoptUnresolved(
            mediaId = mediaId,
            uri = uri,
            target = target,
            playingMime = mime,
            playing = withContext(Dispatchers.IO) { cachedFloor(uri, format, durationSec) },
        )
    }

    private fun cachedFloor(uri: Uri, format: Format, durationSec: Int?): StreamFormat? {
        format.bitrate.takeIf { it != Format.NO_VALUE && it > 0 }?.let {
            return StreamFormat(kbps = it / 1000)
        }
        val seconds = durationSec?.takeIf { it > 0 } ?: return null
        val bytes = AudioCache.contentLengthOf(uri).takeIf { it > 0 } ?: return null
        return StreamFormat(kbps = (bytes * 8 / seconds / 1000).toInt())
    }

    private class SwapPoint(
        val item: MediaItem,
        val uri: String,
        val position: Long,
        val duration: Long,
    )

    private suspend fun swapIn(mediaId: String, stream: SourceStream) {
        val at = withContext(Dispatchers.Main) { swapPointFor(mediaId) } ?: return
        if (at.duration > 0 && at.duration - at.position < UPGRADE_MIN_REMAINING_MS) {
            TrackLog.d("BitChord", "upgrade abandoned: only ${at.duration - at.position}ms of the track left")
            return
        }

        val upgradedUri = QualityUpgrade.upgradedUri(at.uri)
        val alreadyFilled = QualityUpgrade.forcedStream(Uri.parse(upgradedUri))?.url == stream.url
        QualityUpgrade.force(mediaId, stream)
        val warmedThrough = auditionUpgrade(mediaId, at, upgradedUri, stream, alreadyFilled)
        if (warmedThrough == null) {
            QualityUpgrade.forget(mediaId)
            withContext(Dispatchers.IO) { AudioCache.discardRendition(Uri.parse(upgradedUri)) }
            return
        }

        var waitedForCrossfade = 0L
        while (withContext(Dispatchers.Main) { crossfade?.isTransitioning() } == true &&
            waitedForCrossfade < UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS
        ) {
            delay(UPGRADE_CROSSFADE_POLL_MS)
            waitedForCrossfade += UPGRADE_CROSSFADE_POLL_MS
        }

        withContext(Dispatchers.Main) { crossfade?.msSinceTransition() }?.let { since ->
            if (since < UPGRADE_AFTER_CROSSFADE_MS) {
                val settle = UPGRADE_AFTER_CROSSFADE_MS - since
                TrackLog.d("BitChord", "upgrade for $mediaId holding ${settle}ms; a transition just ended")
                delay(settle)
            }
        }

        withContext(Dispatchers.Main) {
            val now = swapPointFor(mediaId)
            if (crossfade?.isTransitioning() == true) {
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("BitChord", "upgrade for $mediaId shelved: a crossfade was still running")
                return@withContext
            }
            if (now == null) {
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("BitChord", "upgrade for $mediaId proved but the queue moved on; shelved")
                return@withContext
            }
            val player = player ?: return@withContext
            if (now.duration > 0 && now.duration - now.position < UPGRADE_MIN_REMAINING_MS) {
                TrackLog.d("BitChord", "upgrade abandoned: only ${now.duration - now.position}ms of the track left")
                QualityUpgrade.forget(mediaId)
                return@withContext
            }
            if (QualityUpgrade.forcedStream(Uri.parse(upgradedUri)) == null) {
                TrackLog.d("BitChord", "upgrade abandoned: its stream was dropped while it was being proved")
                return@withContext
            }
            if (now.position > warmedThrough) {
                TrackLog.d(
                    "BitChord",
                    "upgrade landing at ${now.position}ms, past the ${warmedThrough}ms warmed for it",
                )
            }

            val previousFormat = NerdStats.declaredFormat(mediaId)
            swappingMediaId = mediaId
            swapCutAt = SystemClock.elapsedRealtime()
            val upgradedMetadata = now.item.mediaMetadata.buildUpon()
                .setExtras(Bundle(now.item.mediaMetadata.extras ?: Bundle()).apply {
                    putBoolean(EXTRA_QUALITY_UPGRADED, true)
                })
                .build()
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                now.item.buildUpon()
                    .setUri(upgradedUri)
                    .setMediaMetadata(upgradedMetadata)
                    .withResolvedStreamType(stream.url)
                    .build(),
            )
            val upgradedSourceName = stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: "Upgrade"
            NerdStats.onSourceStream(mediaId, stream.format, upgradedSourceName)
            NerdStats.recordSource(mediaId, upgradedSourceName)
            player.seekTo(player.currentMediaItemIndex, now.position)
            player.prepare()
            QualityUpgrade.unshelve(mediaId)
            TrackLog.d("BitChord", "upgraded to ${stream.format.summary} at ${now.position}ms ($upgradedSourceName)")
            watchUpgrade(mediaId, now.uri, now.position, now.duration, previousFormat)
            if (QualityUpgrade.continueAfterLossySwap(mediaId)) {
                val firstPass = upgradeJob
                scope.launch {
                    firstPass?.join()
                    lookForBetterCopy(player)
                }
            }
            if (!ListenTogether.state.value.inParty) {
                launch(Dispatchers.IO) {
                    AudioCache.warmRange(Uri.parse(upgradedUri), 0, ANALYSIS_HEAD_BYTES)
                }
            }
        }
    }

    private fun swapPointFor(mediaId: String): SwapPoint? {
        val player = player ?: return null
        val item = player.currentMediaItem ?: return null
        if (item.mediaId != mediaId) return null
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        if (uri.contains("${QualityUpgrade.MARKER}=hifi-")) return null
        if (OriginalVersion.isPinned(mediaId)) {
            TrackLog.d("BitChord", "no swap for $mediaId: it is held on the original", about = mediaId)
            return null
        }
        return SwapPoint(item, uri, player.currentPosition, player.duration)
    }

    private suspend fun auditionUpgrade(
        mediaId: String,
        at: SwapPoint,
        upgradedUri: String,
        stream: SourceStream,
        renditionAlreadyFilled: Boolean,
    ): Long? {
        QualityUpgrade.beginAudition(mediaId)
        val startedAt = SystemClock.elapsedRealtime()
        val meter = CountingBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(this))
        withContext(Dispatchers.IO) {
            if (!renditionAlreadyFilled) AudioCache.discardRendition(Uri.parse(upgradedUri))
            AudioCache.warmRange(Uri.parse(upgradedUri), 0, UPGRADE_HEADER_BYTES)
        }
        val audition = withContext(Dispatchers.Main) {
            buildAuditionPlayer(meter).apply {
                setMediaItem(
                    at.item.buildUpon()
                        .setUri(upgradedUri)
                        .withResolvedStreamType(stream.url)
                        .build(),
                )
                seekTo(at.position)
                prepare()
            }
        }
        val warmedThrough: Long?
        try {
            warmedThrough = withTimeoutOrNull(UPGRADE_AUDITION_MS) {
                while (true) {
                    val verdict = withContext(Dispatchers.Main) {
                        auditionVerdict(audition, at.duration, stream, meter.bytes.get())
                    }
                    when (verdict) {
                        is Audition.Ready -> return@withTimeoutOrNull verdict.bufferedTo
                        is Audition.Rejected -> {
                            TrackLog.w("BitChord", "upgrade dropped before it was heard: ${verdict.why}")
                            return@withTimeoutOrNull null
                        }
                        Audition.Waiting -> delay(UPGRADE_PROVE_STEP_MS)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { audition.release() }
            QualityUpgrade.endAudition(mediaId)
        }
        val took = SystemClock.elapsedRealtime() - startedAt
        if (warmedThrough == null) {
            TrackLog.d("BitChord", "upgrade for $mediaId never proved itself in ${took}ms")
            return null
        }
        TrackLog.d(
            "BitChord",
            "upgrade to ${stream.format.summary} proved in ${took}ms, buffered through ${warmedThrough}ms",
        )
        delay(AUDITION_RELEASE_MS)
        TrackLog.d("BitChord", AudioCache.cachedSummary(Uri.parse(upgradedUri)))
        return warmedThrough
    }

    private fun MediaItem.Builder.withResolvedStreamType(streamUrl: String): MediaItem.Builder =
        StreamContainer.manifestMimeOf(streamUrl)?.let(::setMimeType) ?: this

    private sealed interface Audition {
        data object Waiting : Audition
        class Ready(val bufferedTo: Long) : Audition
        class Rejected(val why: String) : Audition
    }

    private val upgradeLengthSlackMs: Long
        get() = AppSettings.upgradeLengthSlackSeconds.value * 1_000L

    private fun auditionVerdict(
        audition: ExoPlayer,
        previousDuration: Long,
        stream: SourceStream,
        bytesLoaded: Long,
    ): Audition {
        audition.playerError?.let {
            return Audition.Rejected("${it.errorCodeName} opening ${stream.format.summary}")
        }
        if (audition.playbackState == Player.STATE_ENDED) {
            return Audition.Rejected("replacement ended immediately")
        }
        if (audition.playbackState != Player.STATE_READY) return Audition.Waiting
        val length = audition.duration
        if (length <= 0) return Audition.Waiting
        if (previousDuration > 0 && abs(length - previousDuration) > upgradeLengthSlackMs) {
            return Audition.Rejected("replacement is ${length}ms against ${previousDuration}ms")
        }
        val mime = audition.audioFormat?.sampleMimeType
        if (mime != null && stream.format.isLossless == true && !NerdStats.isLosslessMime(mime)) {
            return Audition.Rejected("promised ${stream.format.summary}, decoder was handed $mime")
        }
        val buffered = audition.bufferedPosition
        val wantedThrough = (player?.currentPosition ?: 0L) + UPGRADE_PREBUFFER_MS
        if (buffered >= wantedThrough || audition.bufferedPercentage >= 100) {
            if (bytesLoaded < MIN_PROVEN_LOAD_BYTES) return Audition.Waiting
            return Audition.Ready(buffered)
        }
        return Audition.Waiting
    }

    private fun buildAuditionPlayer(meter: BandwidthMeter): ExoPlayer = ExoPlayer.Builder(this)
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setBandwidthMeter(meter)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ AUDITION_BUFFER_MS,
                    /* maxBufferMs = */ AUDITION_BUFFER_MS,
                    /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ START_PLAYBACK_MS,
                )
                .setTargetBufferBytes(AUDITION_BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = false
            volume = 0f
        }

    private fun watchUpgrade(
        mediaId: String,
        previousUri: String,
        position: Long,
        previousDuration: Long,
        previousFormat: StreamFormat?,
    ) {
        if (previousDuration <= 0) return
        scope.launch(TrackLog.about(mediaId)) {
            val agreed = withTimeoutOrNull(UPGRADE_PROVE_MS) {
                while (true) {
                    val current = player?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                        ?: return@withTimeoutOrNull false
                    val now = current.duration
                    if (now > 0) return@withTimeoutOrNull abs(now - previousDuration) <= upgradeLengthSlackMs
                    if (current.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull false
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (agreed == true) return@launch
            val player = player ?: return@launch
            val item = player.currentMediaItem ?: return@launch
            if (item.mediaId != mediaId) return@launch
            TrackLog.w(
                "BitChord",
                "upgrade reverted: replacement reports ${player.duration}ms against " +
                    "${previousDuration}ms (state=${player.playbackState}, " +
                    "buffered=${player.bufferedPosition}ms)",
            )
            QualityUpgrade.forget(mediaId)
            if (previousFormat != null) {
                val prevSource = StreamChoice.of(mediaId)?.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: if (StreamChoice.isSubstitute(mediaId)) "Module" else "YouTube"
                NerdStats.onSourceStream(mediaId, previousFormat, prevSource)
            } else {
                NerdStats.clearDeclared(mediaId)
            }
            swappingMediaId = mediaId
            val abandoned = item.localConfiguration?.uri
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon()
                    .setUri(previousUri)
                    .setMimeType(null)
                    .build(),
            )
            player.seekTo(player.currentMediaItemIndex, position)
            player.prepare()
            abandoned?.let {
                launch(Dispatchers.IO) {
                    delay(RECOVERY_DELAY_MS)
                    AudioCache.discardRendition(it)
                }
            }
        }
    }

    private sealed interface Resolved {
        data class Module(val stream: SourceStream) : Resolved
        data class YouTube(val url: String) : Resolved
    }

    private suspend fun resolveWithModulePriority(
        videoId: String,
        target: TrackMatcher.Target,
    ): Resolved {
        if (StreamChoice.substitutesRefused(videoId)) {
            return Resolved.YouTube(StreamResolver.resolve(videoId))
        }
        NerdStats.onLosslessRaceStart(videoId)
        val lookup = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
            withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) { SourceResolver.substituteForYouTube(target) }
        }
        val fallback = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
            runCatching { StreamResolver.resolve(videoId) }
        }

        val quick: SourceStream? = select {
            lookup.onAwait { it }
            fallback.onAwait { resolved -> if (resolved.isSuccess) null else lookup.await() }
        }

        if (quick != null && StreamContainer.isManifest(quick.url)) {
            val url = runCatching { fallback.await().getOrThrow() }.getOrNull()
            if (url != null) {
                TrackLog.d(
                    "BitChord",
                    "'${target.title}' was offered ${quick.format.summary} as a manifest; " +
                        "starting on YouTube and swapping to it under the music",
                    about = videoId,
                )
                val handed = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = target,
                    inFlight = CompletableDeferred(quick),
                    playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
                )
                if (!handed) NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.YouTube(url)
            }
            TrackLog.w(
                "BitChord",
                "'${target.title}' has only a manifest and YouTube cannot serve it; " +
                    "letting it fail once to declare its type",
                about = videoId,
            )
            NerdStats.onLosslessRaceEnd(videoId)
            return Resolved.Module(quick)
        }

        if (quick != null) {
            fallback.cancel()
            if (!quick.belowRequest) {
                NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.Module(quick)
            }
            val settled = QualityUpgrade.settledForLess(
                mediaId = videoId,
                target = target,
                playing = quick.format,
                servedBy = quick.sourceConfigId,
            )
            if (!settled) NerdStats.onLosslessRaceEnd(videoId)
            return Resolved.Module(quick)
        }

        val url = fallback.await().getOrThrow()
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = lookup.takeIf { lookup.isActive },
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
        return Resolved.YouTube(url)
    }

    private class Measured(
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int?,
    ) {
        val pcmBitrateKbps: Int?
            get() {
                val depth = bitDepth ?: return null
                val rate = sampleRateHz ?: return null
                val ch = channels ?: return null
                return (depth.toLong() * rate * ch / 1000).toInt().takeIf { it > 0 }
            }
    }

    private fun Format.measure(): Measured {
        val streamInfo = flacStreamInfo()
        val rendererDepth = if (pcmEncoding == C.ENCODING_PCM_FLOAT) null else bitDepthOf(pcmEncoding)
        return Measured(
            sampleRateHz = sampleRate.takeIf { it > 0 } ?: streamInfo?.sampleRateHz,
            channels = channelCount.takeIf { it > 0 } ?: streamInfo?.channels,
            bitDepth = rendererDepth ?: streamInfo?.bitDepth,
        )
    }

    private class FlacStreamInfo(val sampleRateHz: Int, val channels: Int, val bitDepth: Int)

    private fun Format.flacStreamInfo(): FlacStreamInfo? {
        if (sampleMimeType != MimeTypes.AUDIO_FLAC) return null
        val data = initializationData.firstOrNull() ?: return null
        fun byteAt(i: Int) = data[i].toInt() and 0xFF
        fun headerAt(i: Int) = data.size > i + 3 &&
            (byteAt(i) and 0x7F) == 0 &&
            byteAt(i + 1) == 0 && byteAt(i + 2) == 0 && byteAt(i + 3) == STREAM_INFO_BYTES
        val body = when {
            data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC" ->
                if (headerAt(4)) 8 else 4
            headerAt(0) -> 4
            else -> 0
        }
        if (data.size < body + STREAM_INFO_BYTES) return null
        fun at(i: Int) = byteAt(body + i)
        val sampleRate = (at(10) shl 12) or (at(11) shl 4) or (at(12) shr 4)
        val channels = ((at(12) shr 1) and 0x07) + 1
        val bitDepth = (((at(12) and 0x01) shl 4) or (at(13) shr 4)) + 1
        return FlacStreamInfo(sampleRate, channels, bitDepth).takeIf { sampleRate > 0 }
    }

    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val scheme = uri?.scheme
        if (scheme != null && scheme != "bitchord") return false
        return Downloads.verifiedSavedUri(mediaId) != null
    }

    private fun currentSourceName(mediaId: String?, mediaItem: MediaItem?): String? {
        val id = mediaId ?: return null
        if (isLocalPlayback(id, mediaItem)) {
            return "Local Storage"
        }

        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null) {
            QualityUpgrade.forcedStream(uri)?.let { upgraded ->
                val name = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                if (!name.isNullOrBlank()) return name
            }
        }

        val exactRecorded = NerdStats.exactSourceFor(id)
        if (!exactRecorded.isNullOrBlank()) return exactRecorded

        val sourceTrack = SourceRegistry.parseTrackKey(id)
        if (sourceTrack != null) {
            val config = SourceRegistry.config(sourceTrack.first)
            if (config != null) return config.displayName
            return "Unknown"
        }

        StreamChoice.of(id)?.let { serving ->
            val name = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(id)) "Module" else null
            if (!name.isNullOrBlank()) return name
        }

        if (uri?.authority == "source") {
            val configId = uri.getQueryParameter("s")
            val name = configId?.let { SourceRegistry.config(it)?.displayName }
            if (!name.isNullOrBlank()) return name
            return "Unknown"
        }

        val recorded = NerdStats.sourceFor(id)
        if (!recorded.isNullOrBlank()) return recorded

        if (id.length == 11 || uri?.getQueryParameter("v") != null) {
            return "YouTube"
        }

        return "Unknown"
    }

    private fun resolveLocalUri(mediaId: String, mediaItem: MediaItem?): Uri? {
        val directUriStr = when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> mediaId
            mediaId.startsWith("/") -> "file://$mediaId"
            else -> null
        }
        if (directUriStr != null) return Uri.parse(directUriStr)

        val localConfigUri = mediaItem?.localConfiguration?.uri
        if (localConfigUri != null && (localConfigUri.scheme == "file" || localConfigUri.scheme == "content")) {
            return localConfigUri
        }

        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return requestUri
        }

        val extras = mediaItem?.mediaMetadata?.extras
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUriStr.isNullOrBlank()) {
            return Uri.parse(localUriStr)
        }

        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return Uri.fromFile(java.io.File(localPath))
        }

        val downloadedUri = Downloads.verifiedSavedUri(mediaId)
        if (downloadedUri != null) {
            return Uri.parse(downloadedUri)
        }

        return null
    }

    private fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val reading = runCatching { extractLocalBitrate(uri) }.getOrNull()
            if (reading != null && reading.kbps > 0) {
                localBitrateCache[mediaId] = reading
                withContext(Dispatchers.Main) {
                    if (player?.currentMediaItem?.mediaId == mediaId) {
                        publishNerdStats()
                    }
                }
            }
        }
    }

    private fun extractLocalBitrate(uri: Uri): LocalBitrateReading? {
        val extractor = MediaExtractor()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                var durationMs: Long? = null
                var sizeBytes: Long? = descriptor.statSize.takeIf { it > 0 }
                for (i in 0 until extractor.trackCount) {
                    val track = extractor.getTrackFormat(i)
                    val mime = track.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    if (durationMs == null && track.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = track.getLong(MediaFormat.KEY_DURATION) / 1_000L
                    }
                    val bitrateBps = if (track.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        track.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    } else {
                        0L
                    }
                    if (bitrateBps > 0) {
                        val sampleRate = if (track.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            track.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else {
                            null
                        }
                        val channels = if (track.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            track.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else {
                            null
                        }
                        val bitDepth = if (track.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmBitDepthOf(track.getInteger(MediaFormat.KEY_PCM_ENCODING))
                        } else {
                            null
                        }
                        if (ContainerBitrate.isPlausibleEncodedBitrate(bitrateBps, sampleRate, channels, bitDepth)) {
                            return LocalBitrateReading(
                                kbps = ((bitrateBps + 500L) / 1_000L).toInt(),
                                provenance = TelemetryProvenance.MEASURED,
                            )
                        }
                    }
                    break
                }
                val duration = durationMs ?: retrieverDurationMs(uri)
                if (sizeBytes != null && duration != null) {
                    ContainerBitrate.averageKbpsFromSizeAndDuration(sizeBytes, duration)?.let {
                        return LocalBitrateReading(it, TelemetryProvenance.DERIVED)
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
        return null
    }

    private fun retrieverDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this@PlaybackService, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (error: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun pcmBitDepthOf(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    private fun publishNerdStats() {
        val player = player ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        val measured = format?.measure()
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val liveMeasuredKbps = mediaId?.let { measuredBitrate.measuredKbps(it) }
        val localReading = mediaId?.let { localBitrateCache[it] }
        val (bitrate, provenance) = when {
            liveMeasuredKbps != null ->
                Pair(liveMeasuredKbps, TelemetryProvenance.MEASURED)
            localReading != null ->
                Pair(localReading.kbps, localReading.provenance)
            !isLossless && dsd == null && format?.averageBitrate != null && format.averageBitrate > 0 ->
                Pair((format.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            !isLossless && dsd == null && format?.bitrate != null && format.bitrate > 0 ->
                Pair((format.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            !isLossless && dsd == null && NerdStats.declaredFormat(mediaId)?.let { it.kbps != null && it.isLossless != true } == true ->
                Pair(NerdStats.declaredFormat(mediaId)?.kbps, TelemetryProvenance.DERIVED)
            !isLossless && dsd == null && NerdStats.pickedBitrateKbps(mediaId) != null ->
                Pair(NerdStats.pickedBitrateKbps(mediaId), TelemetryProvenance.DERIVED)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.current.value = NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            claimed = NerdStats.declaredFormat(mediaId),
            sourceName = currentSourceName(mediaId, player.currentMediaItem),
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
        )
    }

    private fun saveQueueSnapshot(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clear()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        persistedQueueStart = range.first
        LastPlayed.saveQueue(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
        )
        savePlaybackState(player)
    }

    private fun saveQueueSnapshotImmediately(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clearImmediately()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        persistedQueueStart = range.first
        LastPlayed.saveQueueImmediately(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
        )
    }

    private fun beginRadioQueue() {
        crossfade?.onSkipRequested()
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        sessionSongHistory.clear()
        persistedQueueStart = 0
        LastPlayed.clearImmediately()
    }

    private fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        LastPlayed.savePlaybackState(
            index = player.currentMediaItemIndex - persistedQueueStart,
            positionMs = player.currentPosition,
        )
    }

    private fun restoreLastQueue(player: ExoPlayer): Boolean {
        val last = LastPlayed.load() ?: return false
        persistedQueueStart = 0
        player.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        return true
    }

    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    private fun publishWidgetState(playing: Boolean? = null) {
        val exoPlayer = player ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val liked = LikeState.overrides.value[song.videoId]?.let { it == LikeStatus.LIKE }
            ?: MediaWidgetSnapshot.load(this).takeIf { it.mediaId == song.videoId }?.isLiked
            ?: false
        MediaWidgetSnapshot.save(
            this,
            MediaWidgetSnapshot(
                mediaId = song.videoId,
                title = song.title,
                artist = song.artist,
                artworkUrl = song.thumbnailUrl,
                isPlaying = playing ?: exoPlayer.playWhenReady,
                hasPrevious = exoPlayer.hasPreviousMediaItem(),
                hasNext = exoPlayer.hasNextMediaItem(),
                isLiked = liked,
                shuffleEnabled = QueueShuffle.enabled.value,
            ),
        )
        MediaWidget.refresh(this)
    }

    private var foreignCachePurgeJob: Job? = null

    private fun scheduleForeignCachePurge() {
        foreignCachePurgeJob?.cancel()
        foreignCachePurgeJob = scope.launch {
            delay(FOREIGN_CACHE_PURGE_DELAY_MS)
            val exoPlayer = player ?: return@launch
            val keep = buildSet {
                exoPlayer.currentMediaItem?.mediaId?.let(::add)
                val current = exoPlayer.currentMediaItemIndex
                if (current != C.INDEX_UNSET) {
                    val window = (current - 1)..(current + FOREIGN_CACHE_KEEP_AHEAD)
                    for (index in window) {
                        if (index in 0 until exoPlayer.mediaItemCount) add(exoPlayer.getMediaItemAt(index).mediaId)
                    }
                }
                spare?.currentMediaItem?.mediaId?.let(::add)
            }
            AudioCache.dropForeignEntries(keep)
        }
    }

    private fun prefetchAround(player: ExoPlayer) {
        val nextIndex = player.nextMediaItemIndex
        val upcomingSongs = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            (nextIndex..end).map { index -> player.getMediaItemAt(index).toSong() }
        } else {
            emptyList()
        }
        AudioCache.noteSongs(upcomingSongs)
        val preferAudio = AppSettings.preferMusicOnly.value
        val request = preferAudio to upcomingSongs.map { it.videoId }
        if (request == preferredPrefetchRequest) return
        preferredPrefetchRequest = request
        preferredPrefetchJob?.cancel()

        fun warm(songs: List<Song>) {
            AudioCache.prefetchQueue(
                songs.map { song ->
                    AudioCache.Upcoming(
                        mediaId = song.videoId,
                        target = TrackMatcher.targetOf(song),
                    )
                },
            )
        }

        val next = upcomingSongs.firstOrNull()
        if (!preferAudio || next?.isVideo != true) {
            preferredPrefetchJob = null
            warm(upcomingSongs)
            return
        }

        AudioCache.cancel()
        preferredPrefetchJob = scope.launch {
            val preferred = runCatching { YtMusicRepository.resolveAudio(next) }
                .onFailure {
                    TrackLog.d(
                        "BitChord",
                        "music-only queue warm-up fell back to video: ${it.message}",
                        about = next.videoId,
                    )
                }
                .getOrDefault(next)
            if (preferredPrefetchRequest != request) return@launch
            warm(listOf(preferred) + upcomingSongs.drop(1))
        }
    }

    private fun cancelPrefetch() {
        preferredPrefetchRequest = null
        preferredPrefetchJob?.cancel()
        preferredPrefetchJob = null
        AudioCache.cancel()
    }

    private fun reportProgress() {
        scope.launch {
            while (isActive) {
                val player = this@PlaybackService.player
                if (player != null && player.isPlaying) {
                    lastPositionSeconds = player.currentPosition / 1000
                    player.currentMediaItem?.mediaId?.let {
                        PlaybackTracker.onProgress(it, lastPositionSeconds)
                        measuredBitrate.onPlayTick(
                            mediaId = it,
                            positionMs = player.currentPosition,
                            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        )
                    }
                    player.currentMediaItem?.toSong()?.let {
                        ListeningRecorder.onSample(it, player.duration)
                    }
                    savePlaybackState(player)
                    publishNerdStats()
                    lookForBetterCopy(player)
                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player?.pause()
                SleepTimer.cancel()
            }
        }
    }

    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            /* maxBufferMs = */ FAR_BUFFER_MS,
            /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(/* backBufferDurationMs = */ BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()

    private fun silenceSkippingRenderers(
        sing: VocalCutProcessor,
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        transition: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
    ) = object : DefaultRenderersFactory(this) {
        init {
            setEnableAudioFloatOutput(configuredFloatOutput)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val selector = if (mimeType == MimeTypes.AUDIO_FLAC) {
                    MediaCodecSelector.PREFER_SOFTWARE
                } else {
                    MediaCodecSelector.DEFAULT
                }
                val candidates = selector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                if (mimeType == MimeTypes.AUDIO_FLAC) {
                    candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                } else {
                    candidates
                }
            }
        }

        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out,
            )
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val defaultSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(
                    enableAudioTrackPlaybackParams || enableFloatOutput,
                )
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray<AudioProcessor>(),
                        SilenceSkippingAudioProcessor(
                            MIN_SILENCE_US,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                        ),
                        SonicAudioProcessor(),
                    ),
                )
                .build()

            val dspChain = DspChain(sing, spatial, equalizer, transition, loudness)
            return PrecisionAudioSink(
                delegate = defaultSink,
                dspChain = dspChain,
                enableFloatOutput = enableFloatOutput,
                preferredOutputEncodingProvider = { format -> resolvePreferredOutputEncoding(format) },
                isAudible = { transition === activeFilter },
            )
        }
    }

    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
        player.repeatMode = AppSettings.repeatMode.value
    }

    private fun lastReportedSampleRateHz(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.sampleRateHz?.takeIf { it > 0 }
            ?: status.actualSampleRateHz?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun lastReportedChannelCount(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.channels?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.channelCount?.takeIf { it > 0 }
    }

    private fun applyOutputRoute() {
        val manager = audioManager ?: return
        val usb = preferredUsbDevice()
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: usb.takeIf { AppSettings.preferUsbDac.value }
        eachPlayer { it.setPreferredAudioDevice(preferred) }

        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val directUsbProbe = if (routeKind == AudioRouting.Kind.USB) UsbDirectManager.probe(this) else null

        val format = currentAudioInputFormat
        val measured by lazy(LazyThreadSafetyMode.NONE) { format?.measure() }
        val sampleRate = format?.sampleRate?.takeIf { it > 0 }
            ?: measured?.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
        val channels = format?.channelCount?.takeIf { it > 0 }
            ?: measured?.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val directSupport = if (sampleRate != null) {
            DirectAudioProbe.probeDirectSupport(
                audioManager = manager,
                sampleRateHz = sampleRate,
                channelCount = channels,
                activeDevice = activeDevice,
            )
        } else {
            DirectAudioProbe.DirectSupport.NONE
        }
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        AudioOutputStatus.publish(
            manager = manager,
            requestedPcmMode = AppSettings.outputPcmMode.value,
            preferred = activeDevice,
            floatEnabled = shouldEnableFloatOutput(),
            routeKind = routeKind,
            directUsbProbe = directUsbProbe,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            systemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        if (format != null) {
            resolvePreferredOutputEncoding(format)
        }
    }

    private fun resolvePreferredOutputEncoding(format: Format): PcmEncoding? {
        val manager = audioManager ?: return null
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        if (routeKind == AudioRouting.Kind.PHONE) {
            return PcmEncoding.PCM_16BIT
        }

        val inputFormat = currentAudioInputFormat ?: format
        val measuredFormat = format.measure()
        val measuredInput by lazy(LazyThreadSafetyMode.NONE) {
            if (inputFormat === format) measuredFormat else inputFormat.measure()
        }

        val sampleRate = format.sampleRate.takeIf { it > 0 }
            ?: inputFormat.sampleRate.takeIf { it > 0 }
            ?: measuredFormat.sampleRateHz?.takeIf { it > 0 }
            ?: measuredInput.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
            ?: return null

        val channels = format.channelCount.takeIf { it > 0 }
            ?: inputFormat.channelCount.takeIf { it > 0 }
            ?: measuredFormat.channels?.takeIf { it > 0 }
            ?: measuredInput.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val bitDepth = measuredFormat.bitDepth
            ?: measuredInput.bitDepth
            ?: NerdStats.current.value?.bitDepth ?: when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_FLOAT -> 32
            else -> when (format.pcmEncoding) {
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }
        }

        val sourceEncoding = if (inputFormat.sampleMimeType != null && inputFormat.sampleMimeType != "audio/raw") {
            inputFormat.sampleMimeType!!
        } else {
            format.sampleMimeType ?: "audio/raw"
        }

        val source = SourceDescriptor(
            encoding = sourceEncoding,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitDepth = bitDepth,
        )

        val directSupport = DirectAudioProbe.probeDirectSupport(
            audioManager = manager,
            sampleRateHz = sampleRate,
            channelCount = channels,
            activeDevice = activeDevice,
        )
        val directUsbProbe = if (routeKind == AudioRouting.Kind.USB) UsbDirectManager.probe(this) else null
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        val advertisedEncodings = activeDevice?.encodings?.toList() ?: emptyList()
        val advertisedSampleRates = activeDevice?.sampleRates?.toList() ?: emptyList()

        val negotiation = OutputNegotiator.negotiate(
            source = source,
            decoderName = AudioOutputStatus.current.value.decoderName,
            sampleRateHz = sampleRate,
            channelCount = channels,
            routeKind = routeKind,
            deviceName = activeDevice?.productName?.toString()?.ifBlank { null } ?: "System default",
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            requestedMode = AppSettings.outputPcmMode.value,
            directUsbProbe = directUsbProbe,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            delegateSupportsFloat = shouldEnableFloatOutput(),
            delegateSupportsPcm24 = false,
            knownSystemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        AudioOutputStatus.publishNegotiation(negotiation)

        return negotiation.output.encoding
    }

    private fun requestOutputReconfiguration() {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            while (crossfade?.isTransitioning() == true) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput) {
                applyOutputRoute()
            } else {
                rebuildPlayersForOutput(requestedFloat)
            }
        }
    }

    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player ?: return
        val oldSpare = spare ?: return
        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val shuffleMode = oldActive.shuffleModeEnabled

        oldActive.playWhenReady = false
        crossfade?.release()
        oldActive.removeListener(playbackListener)
        oldActive.removeAnalyticsListener(formatListener)

        configuredFloatOutput = enableFloat
        activeFilter = transitionFilterA
        spareFilter = transitionFilterB
        val newActive = buildPlayer(
            singProcessorA,
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val newSpare = buildPlayer(
            singProcessorB,
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = newActive
        spare = newSpare
        newSpare.audioSessionId = newActive.audioSessionId
        AppSettings.audioSessionId.value = newActive.audioSessionId
        applySettings(newActive)
        applySettings(newSpare)
        newActive.repeatMode = repeatMode
        newActive.shuffleModeEnabled = shuffleMode
        applyEqualizer()
        applySpatialAudioEnabled()
        if (items.isNotEmpty()) {
            newActive.setMediaItems(items, index.coerceIn(items.indices), position)
            setupLoudness(items.getOrNull(index.coerceIn(items.indices))?.mediaId)
        }
        newActive.addListener(playbackListener)
        newActive.addAnalyticsListener(formatListener)

        val newCrossfade = createCrossfadeController()
        crossfade = newCrossfade
        newCrossfade.start()
        mediaSession?.player = newSessionPlayer(newActive, newCrossfade)
        applyOutputRoute()
        if (items.isNotEmpty()) newActive.prepare()
        newActive.playWhenReady = playWhenReady

        oldActive.release()
        oldSpare.release()
        TrackLog.i(
            "AUDIO_OUT",
            "live output switch: ${if (enableFloat) "PCM_FLOAT" else "PCM_16BIT"}",
            about = newActive.currentMediaItem?.mediaId,
        )
    }

    private fun preferredUsbDevice(): AudioDeviceInfo? = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    private fun resolveActiveOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val infos = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: preferredUsbDevice().takeIf { AppSettings.preferUsbDac.value }
        return preferred ?: AudioRouting.activeOf(infos, null)
    }

    private fun resolveActiveRouteKind(device: AudioDeviceInfo?): AudioRouting.Kind {
        if (device == null) return AudioRouting.Kind.PHONE
        return AudioRouting.kindOf(device.type) ?: AudioRouting.Kind.PHONE
    }

    private fun shouldEnableFloatOutput(): Boolean {
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val advertisesFloat = activeDevice?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true
        val directFloatSupported = audioManager?.let { mgr ->
            DirectAudioProbe.probeDirectSupport(mgr, 48000, 2, activeDevice).supportsFloat
        } ?: false
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = AppSettings.outputPcmMode.value,
            routeKind = routeKind,
            advertisesPcmFloat = advertisesFloat || directFloatSupported,
        )
    }

    private inline fun eachPlayer(body: (ExoPlayer) -> Unit) {
        player?.let(body)
        spare?.let(body)
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.skipSilence.collect { on -> eachPlayer { it.skipSilenceEnabled = on } }
        }
        scope.launch {
            AppSettings.preferMusicOnly.drop(1).collect {
                cancelPrefetch()
                player?.takeIf { it.isPlaying }?.let(::prefetchAround)
            }
        }
        scope.launch {
            AppSettings.preferUsbDac.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AudioRouting.selectedId.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.outputPcmMode.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.playbackSpeed.collect { speed ->
                if (crossfade?.isTransitioning() == true) return@collect
                crossfade?.applyPlaybackSpeed(speed) ?: eachPlayer { it.setPlaybackSpeed(speed) }
            }
        }
        scope.launch {
            combine(
                AppSettings.syncedLyrics,
                AppSettings.lyricsSources,
                AppSettings.lyricsSourceOrder,
                AppSettings.prioritizeSyllableSync,
            ) { synced, sources, order, prio ->
                synced to sources
            }.distinctUntilChanged().collect {
                loadLyricsForCurrentTrack()
            }
        }
        scope.launch {
            AppSettings.spatialAudio.collect { applySpatialAudioEnabled() }
        }
        scope.launch {
            AppSettings.singLevel.collect { applySingLevel() }
        }
        scope.launch {
            AppSettings.singMethod.collect { method ->
                singProcessorA.setMethod(method)
                singProcessorB.setMethod(method)
            }
        }
        scope.launch {
            var wasOffline = AppSettings.effectiveOffline.value
            AppSettings.effectiveOffline.collect { offline ->
                if (wasOffline && !offline) restoreStreamingAfterOffline()
                if (!wasOffline && offline) applyOfflineFilesToQueue()
                wasOffline = offline
            }
        }
        scope.launch {
            AppSettings.loudnessNormalization.collect {
                setupLoudness(player?.currentMediaItem?.mediaId)
            }
        }
        scope.launch {
            combine(
                AppSettings.loudnessOffOnSpeaker,
                AudioOutputStatus.current.map { it.routeKind == AudioRouting.Kind.PHONE }.distinctUntilChanged(),
            ) { off, onSpeaker -> off && onSpeaker }
                .distinctUntilChanged()
                .drop(1)
                .collect { setupLoudness(player?.currentMediaItem?.mediaId) }
        }
        scope.launch {
            combine<Any, Unit>(
                AppSettings.equalizerEnabled,
                AppSettings.equalizerMode,
                AppSettings.equalizerToneX,
                AppSettings.equalizerToneY,
                AppSettings.equalizerFocused,
                AppSettings.equalizerBalance,
                AppSettings.equalizerBands,
            ) { }.collect { applyEqualizer() }
        }
    }

    private fun applyEqualizer() {
        val enabled = AppSettings.equalizerEnabled.value
        val curve = when (AppSettings.equalizerMode.value) {
            EqualizerMode.DYNAMIC -> toneCurve(
                x = AppSettings.equalizerToneX.value,
                y = AppSettings.equalizerToneY.value,
                focused = AppSettings.equalizerFocused.value,
            )
            EqualizerMode.MANUAL -> manualCurve(AppSettings.equalizerBands.value)
        }
        val balance = AppSettings.equalizerBalance.value
        equalizerProcessorA.setTuning(enabled, curve, balance)
        equalizerProcessorB.setTuning(enabled, curve, balance)
    }

    private fun applySpatialAudioEnabled() {
        val enabled = AppSettings.spatialAudio.value && !activeTrackIsDolbyAtmos
        spatialAudioProcessorA.enabled = enabled
        spatialAudioProcessorB.enabled = enabled
    }

    private fun applySingLevel() {
        val level = AppSettings.singLevel.value
        singProcessorA.level = level
        singProcessorB.level = level
    }

    private suspend fun restoreStreamingAfterOffline() {
        val player = player ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val items: List<MediaItem> = (0 until count).map { player.getMediaItemAt(it) }
        val from = player.currentMediaItemIndex + 1
        if (from >= items.size) return
        val rebuilt = (from until items.size)
            .mapNotNull { items[it].toSong().takeIf { song -> song.localUri != null } }
            .map { it.toMediaItem() }
        if (rebuilt.isEmpty()) return
        TrackLog.d(
            "BitChord",
            "back online; ${rebuilt.size} queued track(s) return to streaming",
            about = player.currentMediaItem?.mediaId,
        )
        player.replaceMediaItems(from, items.size, rebuilt)
    }

    private fun applyOfflineFilesToQueue() {
        val player = player ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val items: List<MediaItem> = (0 until count).map { player.getMediaItemAt(it) }
        val from = player.currentMediaItemIndex + 1
        if (from >= items.size) return
        val rebuilt = (from until items.size)
            .mapNotNull { items[it].toSong().takeIf { song -> song.localUri != null } }
            .map { it.toMediaItem() }
        if (rebuilt.isEmpty()) return
        TrackLog.d(
            "BitChord",
            "offline; ${rebuilt.size} queued track(s) with files switch to them",
            about = player.currentMediaItem?.mediaId,
        )
        player.replaceMediaItems(from, items.size, rebuilt)
    }

    private fun loudnessGainFor(mediaId: String): Float = loudnessGainMb(mediaId)
        ?.let { 10.0.pow(it / 2000.0).toFloat() }
        ?: 1f

    private fun loudnessGainMb(mediaId: String?): Int? {
        if (!AppSettings.loudnessNormalization.value) return null
        if (AppSettings.loudnessOffOnSpeaker.value &&
            AudioOutputStatus.current.value.routeKind == AudioRouting.Kind.PHONE
        ) return null
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val loudnessDb = StreamResolver.loudnessDbFor(id) ?: return null
        return (-loudnessDb * 100.0).roundToInt().coerceIn(MIN_LOUDNESS_GAIN_MB, MAX_LOUDNESS_GAIN_MB)
    }

    private fun setupLoudness(mediaId: String?) {
        val exoPlayer = player ?: return
        activeLoudness().track(mediaId, nextMediaIdOf(exoPlayer))
        publishLoudnessFor(mediaId)
    }

    private fun nextMediaIdOf(exoPlayer: ExoPlayer): String? {
        val next = exoPlayer.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next !in 0 until exoPlayer.mediaItemCount) return null
        return exoPlayer.getMediaItemAt(next).mediaId
    }

    private fun publishLoudnessFor(mediaId: String?) {
        val gainMb = loudnessGainMb(mediaId)
        val lufs = mediaId?.takeIf { it.isNotBlank() }?.let(StreamResolver::loudnessDbFor)
        if (gainMb == null || lufs == null) {
            AudioOutputStatus.publishLoudness(gainDb = null, lufs = null)
        } else {
            AudioOutputStatus.publishLoudness(gainDb = gainMb / 100f, lufs = lufs.toFloat())
        }
    }

    private fun scheduleLoudnessRetry(mediaId: String?) {
        loudnessRetryJob?.cancel()
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        if (StreamResolver.loudnessDbFor(id) != null) return
        loudnessRetryJob = scope.launch {
            delay(LOUDNESS_RETRY_MS)
            if (player?.currentMediaItem?.mediaId == id) publishLoudnessFor(id)
        }
    }

    private fun observeScrobbling() {
        scope.launch {
            combine<Any, ScrobblingSnapshot>(
                AppSettings.lastfmEnabled,
                AppSettings.lastfmScrobbleEnabled,
                AppSettings.lastfmNowPlaying,
                AppSettings.lastfmPrimaryArtistOnly,
                AppSettings.lastfmSessionKey,
                AppSettings.lastfmApiKey,
                AppSettings.lastfmSecret,
                AppSettings.lastfmEndpoint,
                AppSettings.scrobbleMinDuration,
                AppSettings.scrobbleDelayPercent,
                AppSettings.scrobbleDelaySeconds,
            ) { values ->
                ScrobblingSnapshot(
                    lastfmEnabled = values[0] as Boolean,
                    scrobbleEnabled = values[1] as Boolean,
                    nowPlaying = values[2] as Boolean,
                    primaryArtistOnly = values[3] as Boolean,
                    sessionKey = values[4] as String,
                    apiKey = values[5] as String,
                    secret = values[6] as String,
                    endpoint = values[7] as String,
                    minDuration = values[8] as Int,
                    delayPercent = values[9] as Float,
                    delaySeconds = values[10] as Int,
                )
            }.collectLatest { snapshot ->
                val shouldEnable = AppSettings.scrobblingAvailable &&
                    snapshot.lastfmEnabled &&
                    snapshot.scrobbleEnabled &&
                    snapshot.sessionKey.isNotBlank() &&
                    snapshot.apiKey.isNotBlank() &&
                    snapshot.secret.isNotBlank()

                if (!shouldEnable) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                    return@collectLatest
                }

                LastFM.configure(
                    endpoint = snapshot.endpoint.ifBlank { LastFM.DEFAULT_API_ENDPOINT },
                    apiKey = snapshot.apiKey,
                    secret = snapshot.secret,
                    sessionKey = snapshot.sessionKey,
                )
                val manager = scrobbleManager ?: ScrobbleManager(scope).also {
                    scrobbleManager = it
                }
                manager.minSongDuration = snapshot.minDuration
                manager.scrobbleDelayPercent = snapshot.delayPercent
                manager.scrobbleDelaySeconds = snapshot.delaySeconds
                manager.useNowPlaying = snapshot.nowPlaying
                manager.usePrimaryArtistOnly = snapshot.primaryArtistOnly

                player?.let { exoPlayer ->
                    if (exoPlayer.isPlaying) {
                        manager.onPlayerStateChanged(
                            isPlaying = true,
                            song = exoPlayer.currentMediaItem?.toSong(),
                            durationMs = exoPlayer.duration.takeIf { it > 0 },
                        )
                    }
                }
            }
        }
    }

    private data class ScrobblingSnapshot(
        val lastfmEnabled: Boolean,
        val scrobbleEnabled: Boolean,
        val nowPlaying: Boolean,
        val primaryArtistOnly: Boolean,
        val sessionKey: String,
        val apiKey: String,
        val secret: String,
        val endpoint: String,
        val minDuration: Int,
        val delayPercent: Float,
        val delaySeconds: Int,
    )

    private fun observeDiscord() {
        scope.launch {
            combine(
                AppSettings.discordToken,
                AppSettings.discordRpcEnabled,
            ) { token, enabled -> token.takeIf { enabled && it.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    discordUpdateJob?.cancel()
                    discordRpc?.let { rpc ->
                        val wasUp = discordPresenceUp
                        discordPresenceUp = false
                        withContext(Dispatchers.IO + NonCancellable) {
                            withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                                if (wasUp) runCatching { rpc.close() }
                            }
                            runCatching { rpc.closeRPC() }
                        }
                    }
                    discordRpc = null

                    if (token == null) return@collectLatest
                    discordRpc = DiscordRPC(this@PlaybackService, token)
                    player?.takeIf { it.isPlaying }?.let(::pushDiscordPresence)
                }
        }

        scope.launch {
            combine<Any, List<Any>>(
                AppSettings.discordUseDetails,
                AppSettings.discordStatus,
                AppSettings.discordActivityType,
                AppSettings.discordActivityName,
                AppSettings.discordButton1Text,
                AppSettings.discordButton1Visible,
                AppSettings.discordButton2Text,
                AppSettings.discordButton2Visible,
                AppSettings.playbackSpeed,
            ) { it.toList() }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
                }
        }

        scope.launch {
            combine(
                NerdStats.current,
                AppSettings.discordShowAudioQuality,
            ) { stats, enabled ->
                discordAudioQualityLine(stats).takeIf { enabled }
            }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
                }
        }

        scope.launch {
            AppSettings.meteredConnection
                .drop(1)
                .collect { metered ->
                    if (metered == null) return@collect
                    val rpc = discordRpc ?: return@collect
                    withContext(Dispatchers.IO) { runCatching { rpc.wakeUp() } }
                    if (discordPresenceUp) {
                        player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
                    }
                }
        }
    }

    private fun pushDiscordPresence(exoPlayer: ExoPlayer) {
        val rpc = discordRpc ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        val speed = AppSettings.playbackSpeed.value
        val audioQuality = discordAudioQualityLine(NerdStats.current.value)
            .takeIf { AppSettings.discordShowAudioQuality.value }

        discordUpdateJob?.cancel()
        discordPresenceUp = true
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            rpc.updateSong(
                song = song,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                playbackSpeed = speed,
                useDetails = AppSettings.discordUseDetails.value,
                status = AppSettings.discordStatus.value,
                button1Text = AppSettings.discordButton1Text.value,
                button1Visible = AppSettings.discordButton1Visible.value,
                button1Url = AppSettings.discordButton1Url.value,
                button2Text = AppSettings.discordButton2Text.value,
                button2Visible = AppSettings.discordButton2Visible.value,
                button2Url = AppSettings.discordButton2Url.value,
                activityType = AppSettings.discordActivityType.value,
                activityName = AppSettings.discordActivityName.value,
                audioQuality = audioQuality,
            ).onFailure {
                TrackLog.d("BitChord", "Discord presence failed: ${it.message}", about = song.videoId)
            }
        }
    }

    private fun clearDiscordPresence() {
        val rpc = discordRpc ?: return
        if (!discordPresenceUp) return
        discordPresenceUp = false
        discordUpdateJob?.cancel()
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            runCatching { rpc.close() }
        }
    }

    private fun submitListenBrainzFinished(song: Song, startMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        val endMs = System.currentTimeMillis()
        scope.launch {
            ListenBrainzManager.submitFinished(lbToken, song, startMs, endMs, durationMs, AppSettings.listenBrainzPrimaryArtistOnly.value)
        }
    }

    private fun submitListenBrainzPlayingNow(song: Song, positionMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        scope.launch {
            ListenBrainzManager.submitPlayingNow(lbToken, song, positionMs, durationMs, AppSettings.listenBrainzPrimaryArtistOnly.value)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppSettings.stopOnTaskRemoved.value) {
            if (castPlayback.active) CastController.disconnect(resumeHere = false)
            eachPlayer { it.stop() }
            stopSelf()
        }
    }

    override fun onDestroy() {
        CastController.detach(castPlayback)
        castPlayback.release()
        closeAudioEffectSession()
        bluetoothTracker.stop()
        audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
        partySync?.stop()
        partySync = null
        player?.let(::savePlaybackState)
        publishWidgetState(playing = false)
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        serviceLyricsJob?.cancel()
        serviceLyricsJob = null
        serviceLyrics = null
        cancelPrefetch()
        trackAnalyzer.release()
        loudnessRetryJob?.cancel()
        PlaybackTracker.onPlaybackFinished(
            player?.currentPosition?.div(1000) ?: lastPositionSeconds,
        )
        val lastSong = listenBrainzSong
        if (lastSong != null && listenBrainzStartMs > 0L) {
            val lbEnabled =
                AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
            val lbToken = AppSettings.listenBrainzToken.value
            if (lbEnabled && lbToken.isNotBlank()) {
                val lastStart = listenBrainzStartMs
                val lastDuration = player?.duration?.takeIf { it > 0 }
                CoroutineScope(Dispatchers.IO).launch {
                    ListenBrainzManager.submitFinished(
                        lbToken, lastSong, lastStart, System.currentTimeMillis(), lastDuration,
                        AppSettings.listenBrainzPrimaryArtistOnly.value,
                    )
                }
            }
        }
        scrobbleManager?.destroy()
        scrobbleManager = null
        ListeningRecorder.onStopped()
        discordRpc?.let { rpc ->
            discordRpc = null
            val wasUp = discordPresenceUp
            discordPresenceUp = false
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                    if (wasUp) runCatching { rpc.close() }
                }
                runCatching { rpc.closeRPC() }
            }
        }
        scope.cancel()
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playbackListener)
        player?.removeAnalyticsListener(formatListener)
        player?.release()
        player = null
        spare?.release()
        spare = null
        AudioOutputStatus.reset()
        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrate.forget()
        super.onDestroy()
    }

    private fun loadLyricsForCurrentTrack() {
        val currentSong = player?.currentMediaItem?.toSong() ?: run {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        if (!AppSettings.syncedLyrics.value) {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        val trackDurationMs = (player?.duration ?: 0L).takeIf { it > 0 } ?: 0L

        serviceLyricsJob?.cancel()
        serviceLyricsJob = scope.launch(Dispatchers.IO) {
            val localUri = currentSong.localUri
            var lines: List<LyricLine>? = null
            if (localUri != null) {
                lines = EmbeddedLyrics.forUri(this@PlaybackService, localUri)
            }
            if (lines == null) {
                val found = LyricsRepository.lyrics(
                    videoId = currentSong.videoId,
                    title = currentSong.title,
                    artist = currentSong.artist,
                    durationMs = trackDurationMs,
                    album = currentSong.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
                lines = found?.lines
            }
            withContext(Dispatchers.Main) {
                serviceLyrics = lines
                if (player?.isPlaying == true) {
                    updateLyricSubtitle()
                }
            }
        }
    }

    private fun startLyricsTicker() {
        if (lyricsTickerJob?.isActive == true) return
        lyricsTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateLyricSubtitle()
                delay(500L)
            }
        }
    }

    private fun stopLyricsTicker() {
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        updateLyricSubtitle()
    }

    private fun updateLyricSubtitle() {
        val exoPlayer = player ?: return
        val currentSong = exoPlayer.currentMediaItem?.toSong() ?: return
        val lines = serviceLyrics
        val pos = exoPlayer.currentPosition
        val subtitleText = if (lines != null && lines.isNotEmpty() && AppSettings.syncedLyrics.value) {
            val idx = lines.indexOfLast { it.timeMs <= pos }
            val currentLine = lines.getOrNull(idx)
            if (currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank()) {
                "♪ ${currentLine.text}"
            } else {
                currentSong.artist
            }
        } else {
            currentSong.artist
        }

        if (subtitleText != lastPublishedSubtitle) {
            lastPublishedSubtitle = subtitleText
            exoPlayer.playlistMetadata = exoPlayer.mediaMetadata
                .buildUpon()
                .setSubtitle(subtitleText)
                .build()
        }
    }

    private class SessionPlayer(
        player: Player,
        private val crossfade: CrossfadeController,
        private val onUserIntent: () -> Unit,
        private val deferPlayToParty: () -> Boolean,
        private val lockedTransport: (Boolean) -> Boolean,
        private val cast: CastPlayback,
        private val getSubtitle: () -> String?,
    ) : ForwardingPlayer(player), CastSink {

        private val listeners = CopyOnWriteArraySet<Player.Listener>()
        private var reportedPlayWhenReady = false
        private var reportedPlaybackState = Player.STATE_IDLE
        private var reportedIsPlaying = false

        init {
            cast.sink = this
        }

        private companion object {
            val CAST_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(100)
                .build()

            const val VOLUME_STEP = 0.05f
        }

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }

        override fun getPlayWhenReady() = if (cast.active) cast.playWhenReady else super.getPlayWhenReady()

        override fun isPlaying() = if (cast.active) cast.isPlaying else super.isPlaying()

        override fun getPlaybackState() = if (cast.active) cast.playbackState else super.getPlaybackState()

        override fun getCurrentPosition() = if (cast.active) cast.positionMs else super.getCurrentPosition()

        override fun getContentPosition() = if (cast.active) cast.positionMs else super.getContentPosition()

        override fun getBufferedPosition() = if (cast.active) cast.positionMs else super.getBufferedPosition()

        override fun getContentBufferedPosition() =
            if (cast.active) cast.positionMs else super.getContentBufferedPosition()

        override fun getDuration(): Long =
            if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getDuration()

        override fun getContentDuration(): Long =
            if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getContentDuration()

        override fun getDeviceInfo(): DeviceInfo = if (cast.active) CAST_DEVICE else super.getDeviceInfo()

        override fun getDeviceVolume(): Int = if (cast.active) cast.deviceVolume else super.getDeviceVolume()

        override fun isDeviceMuted(): Boolean = if (cast.active) false else super.isDeviceMuted()

        override fun setDeviceVolume(volume: Int, flags: Int) {
            if (cast.active) CastController.setVolume(volume / 100f) else super.setDeviceVolume(volume, flags)
        }

        @Suppress("DEPRECATION")
        override fun setDeviceVolume(volume: Int) {
            if (cast.active) CastController.setVolume(volume / 100f) else super.setDeviceVolume(volume)
        }

        override fun increaseDeviceVolume(flags: Int) {
            if (cast.active) nudgeReceiverVolume(+VOLUME_STEP) else super.increaseDeviceVolume(flags)
        }

        @Suppress("DEPRECATION")
        override fun increaseDeviceVolume() {
            if (cast.active) nudgeReceiverVolume(+VOLUME_STEP) else super.increaseDeviceVolume()
        }

        override fun decreaseDeviceVolume(flags: Int) {
            if (cast.active) nudgeReceiverVolume(-VOLUME_STEP) else super.decreaseDeviceVolume(flags)
        }

        @Suppress("DEPRECATION")
        override fun decreaseDeviceVolume() {
            if (cast.active) nudgeReceiverVolume(-VOLUME_STEP) else super.decreaseDeviceVolume()
        }

        override fun setDeviceMuted(muted: Boolean, flags: Int) {
            if (!cast.active) super.setDeviceMuted(muted, flags)
        }

        @Suppress("DEPRECATION")
        override fun setDeviceMuted(muted: Boolean) {
            if (!cast.active) super.setDeviceMuted(muted)
        }

        private fun nudgeReceiverVolume(delta: Float) =
            CastController.setVolume(CastController.volume.value + delta)

        override fun onRemotePlaybackChanged() {
            val flags = FlagSet.Builder()
            val playWhenReady = getPlayWhenReady()
            val state = getPlaybackState()
            val playing = isPlaying()
            if (playWhenReady != reportedPlayWhenReady) {
                reportedPlayWhenReady = playWhenReady
                flags.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                listeners.forEach {
                    it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                }
            }
            if (state != reportedPlaybackState) {
                reportedPlaybackState = state
                flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                listeners.forEach { it.onPlaybackStateChanged(state) }
            }
            if (playing != reportedIsPlaying) {
                reportedIsPlaying = playing
                flags.add(Player.EVENT_IS_PLAYING_CHANGED)
                listeners.forEach { it.onIsPlayingChanged(playing) }
            }
            flags.add(Player.EVENT_POSITION_DISCONTINUITY)
            val position = Player.PositionInfo(
                null,
                currentMediaItemIndex,
                currentMediaItem,
                null,
                currentPeriodIndex,
                currentPosition,
                contentPosition,
                C.INDEX_UNSET,
                C.INDEX_UNSET,
            )
            listeners.forEach {
                it.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_INTERNAL)
            }
            val events = Player.Events(flags.build())
            listeners.forEach { it.onEvents(this, events) }
        }

        override fun onRemoteRouteChanged() {
            val info = getDeviceInfo()
            listeners.forEach { it.onDeviceInfoChanged(info) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_INFO_CHANGED).build())
            listeners.forEach { it.onEvents(this, events) }
            onRemoteVolumeChanged()
        }

        override fun onRemoteVolumeChanged() {
            val volume = getDeviceVolume()
            val muted = isDeviceMuted()
            listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())
            listeners.forEach { it.onEvents(this, events) }
        }

        private fun castPrevious() {
            if (cast.positionMs > wrappedPlayer.maxSeekToPreviousPosition || !wrappedPlayer.hasPreviousMediaItem()) {
                cast.seekTo(0L)
            } else {
                wrappedPlayer.seekToPreviousMediaItem()
            }
        }

        private fun locked(): Boolean = ListenTogether.state.value.controlsLocked

        override fun play() {
            if (lockedTransport(true)) return
            onUserIntent()
            if (deferPlayToParty()) return
            if (cast.active) {
                cast.play()
                return
            }
            super.play()
        }

        override fun pause() {
            if (lockedTransport(false)) return
            onUserIntent()
            if (cast.active) {
                cast.pause()
                return
            }
            super.pause()
        }

        override fun stop() {
            if (locked()) { super.stop(); return }
            onUserIntent()
            super.stop()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (lockedTransport(playWhenReady)) return
            onUserIntent()
            if (playWhenReady && deferPlayToParty()) return
            if (cast.active) {
                if (playWhenReady) cast.play() else cast.pause()
                return
            }
            super.setPlayWhenReady(playWhenReady)
        }

        override fun seekTo(positionMs: Long) {
            if (locked()) return
            onUserIntent()
            if (cast.active) {
                cast.seekTo(positionMs)
                return
            }
            super.seekTo(positionMs)
        }

        override fun seekBack() {
            if (locked()) return
            onUserIntent()
            if (cast.active) {
                cast.seekTo(cast.positionMs - seekBackIncrement)
                return
            }
            super.seekBack()
        }

        override fun seekForward() {
            if (locked()) return
            onUserIntent()
            if (cast.active) {
                cast.seekTo(cast.positionMs + seekForwardIncrement)
                return
            }
            super.seekForward()
        }

        override fun seekToPrevious() {
            if (locked()) return
            onUserIntent()
            if (cast.active) {
                castPrevious()
                return
            }
            super.seekToPrevious()
        }

        override fun seekToDefaultPosition() {
            if (locked()) return
            onUserIntent()
            if (cast.active) {
                cast.seekTo(0L)
                return
            }
            super.seekToDefaultPosition()
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (locked()) return
            onUserIntent()
            super.seekToDefaultPosition(mediaItemIndex)
        }

        override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
            if (locked()) return
            onUserIntent()
            super.setMediaItems(mediaItems, resetPosition)
        }

        override fun setMediaItems(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) {
            if (locked()) return
            onUserIntent()
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
            if (locked()) return
            onUserIntent()
            super.addMediaItems(index, mediaItems)
        }

        override fun addMediaItems(mediaItems: List<MediaItem>) {
            if (locked()) return
            onUserIntent()
            super.addMediaItems(mediaItems)
        }

        override fun removeMediaItem(index: Int) {
            if (locked()) return
            onUserIntent()
            super.removeMediaItem(index)
        }

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            if (locked()) return
            onUserIntent()
            super.removeMediaItems(fromIndex, toIndex)
        }

        override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
            if (locked()) return
            onUserIntent()
            super.moveMediaItem(currentIndex, newIndex)
        }

        override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
            if (locked()) return
            onUserIntent()
            super.moveMediaItems(fromIndex, toIndex, newIndex)
        }

        override fun replaceMediaItems(
            fromIndex: Int,
            toIndex: Int,
            mediaItems: List<MediaItem>,
        ) {
            if (locked()) return
            onUserIntent()
            super.replaceMediaItems(fromIndex, toIndex, mediaItems)
        }

        override fun clearMediaItems() {
            if (locked()) return
            onUserIntent()
            super.clearMediaItems()
        }

        override fun getMediaMetadata(): MediaMetadata {
            val base = wrappedPlayer.mediaMetadata
            val subtitle = getSubtitle()
            return if (!subtitle.isNullOrBlank()) {
                base.buildUpon().setSubtitle(subtitle).build()
            } else {
                base
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (locked()) return
            onUserIntent()
            crossfade.onSkipRequested()
            if (mediaItemIndex !in 0 until wrappedPlayer.mediaItemCount) return
            if (cast.active && mediaItemIndex == wrappedPlayer.currentMediaItemIndex) {
                cast.seekTo(positionMs)
                return
            }
            val skipped = skippedByQueueJump(currentMediaItemIndex, mediaItemIndex)
            if (skipped == null) {
                wrappedPlayer.seekTo(mediaItemIndex, positionMs)
                return
            }

            val targetTier = wrappedPlayer.getMediaItemAt(mediaItemIndex).queueTier
            val removableIndices = mutableListOf<Int>()
            for (i in skipped) {
                val tier = wrappedPlayer.getMediaItemAt(i).queueTier
                val isRemovable = if (targetTier == QueueTier.USER_QUEUE) {
                    tier == QueueTier.USER_QUEUE
                } else {
                    tier != QueueTier.USER_QUEUE
                }
                if (isRemovable) {
                    removableIndices.add(i)
                }
            }

            for (i in removableIndices.asReversed()) {
                wrappedPlayer.removeMediaItem(i)
            }
            var newTargetIndex = mediaItemIndex - removableIndices.count { it < mediaItemIndex }
            val nextIndex = wrappedPlayer.currentMediaItemIndex + 1
            if (newTargetIndex > nextIndex) {
                wrappedPlayer.moveMediaItem(newTargetIndex, nextIndex)
                newTargetIndex = nextIndex
            }
            wrappedPlayer.seekTo(newTargetIndex, positionMs)
        }

        override fun seekToPreviousMediaItem() {
            if (locked()) return
            onUserIntent()
            crossfade.onSkipRequested()
            if (cast.active) {
                castPrevious()
                return
            }
            wrappedPlayer.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            if (locked()) return
            onUserIntent()
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNextMediaItem()
        }

        override fun seekToNext() {
            if (locked()) return
            onUserIntent()
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNext()
        }
    }

    /**
     * Handles browsing and content resolution for Android Auto and other MediaBrowser clients.
     */
    private inner class MediaLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(favoriteCommand)
                .add(autoplayCommand)
                .add(shuffleCommand)
                .add(startStationCommand)
                .add(revertToOriginalCommand)
                .add(swapToVersionCommand)
                .add(beginRadioQueueCommand)
                .add(commitRadioQueueCommand)
                .add(upgradeQualityCommand)
                .add(swapVersionCommand)
                .add(reorderQueueCommand)
                .add(queueDragCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
                carControllers += controller
                refreshCustomLayouts()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) refreshCustomLayouts()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_AUTOPLAY -> toggleAutoplayFromNotification()
                ACTION_TOGGLE_SHUFFLE -> toggleShuffleFromSession()
                ACTION_START_STATION -> startStationFromSession()
                ACTION_REVERT_TO_ORIGINAL -> revertCurrentToOriginal()
                ACTION_SWAP_TO_VERSION -> swapCurrentToVersion(args)
                ACTION_BEGIN_RADIO_QUEUE -> beginRadioQueue()
                ACTION_COMMIT_RADIO_QUEUE -> player?.let(::saveQueueSnapshotImmediately)
                ACTION_UPGRADE_QUALITY -> upgradeQualityNow()
                ACTION_SWAP_VERSION -> {
                    val bundle = args.getBundle(EXTRA_SWAP_MEDIA_ITEM)
                    if (bundle != null) {
                        val targetSong = songFromBundle(bundle)
                        smoothSwapCurrentTrackVersion(targetSong.toMediaItem())
                    }
                }
                ACTION_REORDER_QUEUE -> player?.let { QueueShuffle.reorderFromCommand(it, args) }
                ACTION_QUEUE_DRAG -> {
                    if (args.getBoolean(EXTRA_QUEUE_DRAG_ACTIVE, false)) {
                        partySync?.beginQueueDrag()
                    } else {
                        partySync?.endQueueDrag()
                    }
                }
                ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem?.mediaId?.let {
                    toggleFavoriteFromNotification(it)
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val isRecent = params?.isRecent == true ||
                params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
                params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true

            val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
            val rootExtras = buildContentStyleBundle(
                CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            )
            val rootItem = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setExtras(rootExtras)
                        .build(),
                )
                .build()
            val rootParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .build()
            scope.launch(Dispatchers.IO) {
                if (AppSettings.spotifySpdcToken.value.isNotBlank()) {
                    launch { runCatching { cachedSpotifyDaylist() } }
                    launch { runCatching { cachedSpotifyMadeForYou() } }
                    launch { runCatching { cachedSpotifyPlaylists() } }
                    launch { runCatching { cachedSpotifyRecentTracks() } }
                }
                launch { runCatching { cachedHomeFeed() } }
                launch { runCatching { cachedQuickPicksSongs() } }
            }

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val isGridFolder = parentId == MEDIA_ROOT_ID ||
                parentId == MEDIA_HOME_ID ||
                parentId == MEDIA_SPOTIFY_ID ||
                parentId == MEDIA_PLAYLISTS_ID
            val items: List<MediaItem> = when (parentId) {
                MEDIA_ROOT_ID -> listOf(
                    createFolderItem(MEDIA_HOME_ID, "Home", folderType = MediaMetadata.FOLDER_TYPE_MIXED, isGrid = true),
                    createFolderItem(MEDIA_SPOTIFY_ID, "Spotify", folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                    createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_MORE_ID, getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                )
                MEDIA_HOME_ID -> {
                    val homeItems = mutableListOf<MediaItem>()
                    val seenIds = HashSet<String>()
                    val spotifySignedIn = AppSettings.spotifySpdcToken.value.isNotBlank()

                    val (daylist, madeForYou, spRecent, spLibrary, quickPicks, feed) = kotlinx.coroutines.coroutineScope {
                        val daylistJob = async { if (spotifySignedIn) cachedSpotifyDaylist() else null }
                        val mfyJob = async { if (spotifySignedIn) cachedSpotifyMadeForYou() else emptyList() }
                        val spRecentJob = async { if (spotifySignedIn) cachedSpotifyRecentTracks() else emptyList() }
                        val spLibJob = async {
                            if (!spotifySignedIn) {
                                emptyList()
                            } else {
                                val spSnap = snapshotSpotifyPlaylists()
                                if (spSnap.isNotEmpty()) {
                                    if (!isSpotifyFresh()) {
                                        refreshFolderInBackground(session, browser, parentId, params) {
                                            cachedSpotifyPlaylists().size
                                        }
                                    }
                                    spSnap
                                } else {
                                    cachedSpotifyPlaylists()
                                }
                            }
                        }
                        val quickJob = async {
                            val quickSnap = snapshotQuickPicks()
                            if (quickSnap.isNotEmpty()) {
                                if (!isQuickPicksFresh()) {
                                    refreshFolderInBackground(session, browser, parentId, params) {
                                        cachedQuickPicksSongs().size
                                    }
                                }
                                quickSnap
                            } else {
                                cachedQuickPicksSongs()
                            }
                        }
                        val homeJob = async {
                            val snapshot = snapshotHome()
                            if (snapshot != null) {
                                if (!isHomeFresh()) {
                                    refreshFolderInBackground(session, browser, parentId, params) {
                                        cachedHomeFeed()?.shelves?.sumOf { it.items.size } ?: 0
                                    }
                                }
                                snapshot
                            } else {
                                cachedHomeFeed()
                            }
                        }
                        HomeBundle(
                            daylist = daylistJob.await(),
                            madeForYou = mfyJob.await(),
                            spRecent = spRecentJob.await(),
                            spLibrary = spLibJob.await(),
                            quickPicks = quickJob.await(),
                            feed = homeJob.await(),
                        )
                    }

                    // 1. Made For You (Spotify Daylist + Daily Mixes + Discover Weekly + Release Radar + Flow)
                    daylist?.let { dl ->
                        if (seenIds.add("sp:${dl.id}")) {
                            homeItems += spotifyPlaylistToGridItem(dl).withGroupTitle("Made for you")
                        }
                    }
                    madeForYou.forEach { pl ->
                        if (seenIds.add("sp:${pl.id}")) {
                            homeItems += spotifyPlaylistToGridItem(pl).withGroupTitle("Made for you")
                        }
                    }
                    homeItems += createFlowPlayItem().withGroupTitle("Made for you")

                    // 2. Jump back in (Spotify recently played tracks + top Spotify library playlists)
                    spRecent.take(6).forEach { track ->
                        if (seenIds.add("spt:${track.id}")) {
                            homeItems += spotifyTrackToMediaItem(track).withGroupTitle("Jump back in")
                        }
                    }
                    spLibrary.take(6).forEach { pl ->
                        if (seenIds.add("sp:${pl.id}")) {
                            homeItems += spotifyPlaylistToGridItem(pl).withGroupTitle("Your Spotify")
                        }
                    }

                    // 3. Recommended Quick Picks
                    quickPicks.forEach { songCache[it.videoId] = it }
                    quickPicks.take(8).forEach { song ->
                        if (seenIds.add(song.videoId)) {
                            homeItems += song.toMediaItem().withGroupTitle("Quick picks")
                        }
                    }

                    // 4. Recommended Playlists, Mixes & Shelves
                    feed?.shelves.orEmpty().forEach { shelf ->
                        val sectionTitle = shelf.title.ifBlank { "Recommended" }
                        shelf.items.forEach { item ->
                            val song = item.toSongOrNull()
                            val browseId = item.browseId
                            if (song != null) {
                                if (seenIds.add(song.videoId)) {
                                    songCache[song.videoId] = song
                                    homeItems += song.toMediaItem().withGroupTitle(sectionTitle)
                                }
                            } else if (browseId != null) {
                                if (seenIds.add(browseId)) {
                                    homeItems += shelfToGridPlaylistItem(item).withGroupTitle(sectionTitle)
                                }
                            }
                        }
                    }

                    if (homeItems.size <= 1) {
                        val fallback = snapshotRecents().ifEmpty {
                            Downloads.getDownloadedSongs(this@PlaybackService)
                        }
                        fallback.forEach { song ->
                            if (seenIds.add(song.videoId)) {
                                songCache[song.videoId] = song
                                homeItems += song.toMediaItem().withGroupTitle("Recent & Downloads")
                            }
                        }
                    }

                    homeItems
                }
                MEDIA_SPOTIFY_ID -> {
                    if (AppSettings.spotifySpdcToken.value.isBlank()) {
                        listOf(
                            createInfoItem(
                                "msg:spotify_login",
                                "Sign in to Spotify",
                                "Connect Spotify inside IMA on your phone",
                            ),
                        )
                    } else {
                        val (daylist, madeForYou, playlists) = kotlinx.coroutines.coroutineScope {
                            val dlJob = async { cachedSpotifyDaylist() }
                            val mfyJob = async { cachedSpotifyMadeForYou() }
                            val libJob = async {
                                val spSnap = snapshotSpotifyPlaylists()
                                if (spSnap.isNotEmpty()) {
                                    if (!isSpotifyFresh()) {
                                        refreshFolderInBackground(session, browser, parentId, params) {
                                            cachedSpotifyPlaylists().size
                                        }
                                    }
                                    spSnap
                                } else {
                                    cachedSpotifyPlaylists()
                                }
                            }
                            Triple(dlJob.await(), mfyJob.await(), libJob.await())
                        }
                        val out = mutableListOf<MediaItem>()
                        val seenSp = HashSet<String>()
                        daylist?.let { dl ->
                            if (seenSp.add(dl.id)) {
                                out += spotifyPlaylistToGridItem(dl).withGroupTitle("Made for you")
                            }
                        }
                        madeForYou.forEach { pl ->
                            if (seenSp.add(pl.id)) {
                                out += spotifyPlaylistToGridItem(pl).withGroupTitle("Made for you")
                            }
                        }
                        playlists.forEach { pl ->
                            if (seenSp.add(pl.id)) {
                                out += spotifyPlaylistToGridItem(pl).withGroupTitle("Your Library")
                            }
                        }
                        if (out.isNotEmpty()) {
                            out
                        } else {
                            listOf(
                                createInfoItem(
                                    "msg:empty_spotify",
                                    "No Spotify playlists",
                                    "Open Spotify in IMA on your phone to refresh",
                                ),
                            )
                        }
                    }
                }
                MEDIA_MORE_ID -> listOf(
                    createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                    createFolderItem(MEDIA_QUICK_PICKS_ID, getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_DOWNLOADS_ID, getString(R.string.downloads), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                    createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                )
                MEDIA_RECENTS_ID -> {
                    val snapshot = snapshotRecents()
                    val songs = if (snapshot.isNotEmpty()) {
                        if (!isRecentsFresh()) {
                            refreshFolderInBackground(session, browser, parentId, params) {
                                cachedRecentsSongs().size
                            }
                        }
                        snapshot
                    } else {
                        cachedRecentsSongs()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_recents",
                            getString(R.string.auto_empty_recents_title),
                            getString(R.string.auto_empty_recents_subtitle),
                        ),
                    )
                }
                MEDIA_QUICK_PICKS_ID -> {
                    val snapshot = snapshotQuickPicks()
                    val songs = if (snapshot.isNotEmpty()) {
                        if (!isQuickPicksFresh()) {
                            refreshFolderInBackground(session, browser, parentId, params) {
                                cachedQuickPicksSongs().size
                            }
                        }
                        snapshot
                    } else {
                        cachedQuickPicksSongs().ifEmpty {
                            snapshotHome()?.shelves?.flatMap { it.items }
                                ?.mapNotNull { it.toSongOrNull() }
                                ?.distinctBy { it.videoId }
                                .orEmpty()
                        }
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_quick_picks",
                            getString(R.string.auto_empty_quick_picks_title),
                            getString(R.string.auto_empty_quick_picks_subtitle),
                        ),
                    )
                }
                MEDIA_LIKED_ID -> {
                    if (com.music.bitchord.data.innertube.Innertube.cookie == null) {
                        listOf(createLoginPromptItem())
                    } else {
                        val snapshot = snapshotLiked()
                        val songs = if (snapshot.isNotEmpty()) {
                            if (!isLikedFresh()) {
                                refreshFolderInBackground(session, browser, parentId, params) {
                                    cachedLikedSongs().size
                                }
                            }
                            snapshot
                        } else {
                            cachedLikedSongs()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        if (songs.isNotEmpty()) {
                            songs.map { it.toMediaItem() }
                        } else {
                            listOf(
                                createInfoItem(
                                    "msg:empty_liked",
                                    getString(R.string.auto_empty_liked_title),
                                    getString(R.string.auto_empty_liked_subtitle),
                                ),
                            )
                        }
                    }
                }
                MEDIA_DOWNLOADS_ID -> {
                    val downloaded = Downloads.getDownloadedSongs(this@PlaybackService)
                    downloaded.forEach { songCache[it.videoId] = it }
                    if (downloaded.isNotEmpty()) downloaded.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_downloads",
                            getString(R.string.auto_empty_offline_title),
                            getString(R.string.auto_empty_offline_subtitle),
                        ),
                    )
                }
                MEDIA_PLAYLISTS_ID -> {
                    if (com.music.bitchord.data.innertube.Innertube.cookie == null) {
                        listOf(createLoginPromptItem())
                    } else {
                        val snapshot = snapshotPlaylistCards()
                        val playlists = if (snapshot.isNotEmpty()) {
                            if (!isPlaylistsFresh()) {
                                refreshFolderInBackground(session, browser, parentId, params) {
                                    cachedPlaylistCards().size
                                }
                            }
                            snapshot
                        } else {
                            cachedPlaylistCards()
                        }
                        if (playlists.isNotEmpty()) playlists.map(::shelfToGridPlaylistItem) else listOf(
                            createInfoItem(
                                "msg:empty_playlists",
                                getString(R.string.auto_empty_playlists_title),
                                getString(R.string.auto_empty_playlists_subtitle),
                            ),
                        )
                    }
                }
                MEDIA_LOCAL_MUSIC_ID -> {
                    val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                    local.forEach { songCache[it.videoId] = it }
                    if (local.isNotEmpty()) local.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_local",
                            getString(R.string.auto_empty_offline_title),
                            getString(R.string.auto_empty_offline_subtitle),
                        ),
                    )
                }
                else -> {
                    when {
                        parentId.startsWith("spotify_playlist:") -> {
                            val playlistId = parentId.removePrefix("spotify_playlist:")
                            val tracks = loadSpotifyPlaylistTracks(playlistId)
                            scope.launch(Dispatchers.IO) {
                                tracks.take(6).forEach { track ->
                                    if (!spotifyMatchedCache.containsKey(track.id)) {
                                        try {
                                            com.music.bitchord.data.spotify.SpotifyImporter.matchTrack(track)?.let { match ->
                                                val song = match.copy(thumbnailUrl = track.imageUrl ?: match.thumbnailUrl)
                                                spotifyMatchedCache[track.id] = song
                                                songCache[song.videoId] = song
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            if (tracks.isNotEmpty()) {
                                tracks.map(::spotifyTrackToMediaItem)
                            } else {
                                listOf(
                                    createInfoItem(
                                        "msg:empty_spotify_tracks",
                                        "No tracks found",
                                        "Open this playlist on your phone and retry",
                                    ),
                                )
                            }
                        }
                        parentId.startsWith("spotify_album:") -> {
                            val albumId = parentId.removePrefix("spotify_album:")
                            val tracks = loadSpotifyAlbumTracks(albumId)
                            scope.launch(Dispatchers.IO) {
                                tracks.take(6).forEach { track ->
                                    if (!spotifyMatchedCache.containsKey(track.id)) {
                                        try {
                                            com.music.bitchord.data.spotify.SpotifyImporter.matchTrack(track)?.let { match ->
                                                val song = match.copy(thumbnailUrl = track.imageUrl ?: match.thumbnailUrl)
                                                spotifyMatchedCache[track.id] = song
                                                songCache[song.videoId] = song
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            if (tracks.isNotEmpty()) {
                                tracks.map(::spotifyTrackToMediaItem)
                            } else {
                                listOf(
                                    createInfoItem(
                                        "msg:empty_spotify_album",
                                        "No album tracks found",
                                        "Check your Spotify sign-in in IMA",
                                    ),
                                )
                            }
                        }
                        parentId.startsWith("playlist:") -> {
                            val playlistId = parentId.removePrefix("playlist:")
                            val songs = try {
                                withTimeoutOrNull(5_000L) {
                                    YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs?.ifEmpty { null }
                                        ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                                } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                            songs.forEach { songCache[it.videoId] = it }
                            songs.map { it.toMediaItem() }
                        }
                        parentId.startsWith("browse:") -> {
                            val browseId = parentId.removePrefix("browse:")
                            val songs = try {
                                withTimeoutOrNull(5_000L) {
                                    YtMusicRepository.browseSongs(browseId).getOrNull()?.songs?.ifEmpty { null }
                                        ?: YtMusicRepository.browseSongs("VL$browseId").getOrNull()?.songs
                                } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                            songs.forEach { songCache[it.videoId] = it }
                            songs.map { it.toMediaItem() }
                        }
                        else -> {
                            val cachedSong = songCache[parentId]
                            if (cachedSong != null) {
                                listOf(cachedSong.toMediaItem())
                            } else {
                                emptyList()
                            }
                        }
                    }
                }
            }
            val returnParams = LibraryParams.Builder().setExtras(
                buildContentStyleBundle(
                    if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                ),
            ).build()
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), returnParams)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val item = when (mediaId) {
                MEDIA_ROOT_ID -> MediaItem.Builder()
                    .setMediaId(MEDIA_ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.app_name))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                            .build(),
                    ).build()
                MEDIA_HOME_ID -> createFolderItem(MEDIA_HOME_ID, "Home", folderType = MediaMetadata.FOLDER_TYPE_MIXED, isGrid = true)
                MEDIA_SPOTIFY_ID -> createFolderItem(MEDIA_SPOTIFY_ID, "Spotify", folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
                MEDIA_FLOW_PLAY_ID -> createFlowPlayItem()
                MEDIA_RECENTS_ID -> createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_QUICK_PICKS_ID -> createFolderItem(MEDIA_QUICK_PICKS_ID, getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_PLAYLISTS_ID -> createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
                MEDIA_MORE_ID -> createFolderItem(MEDIA_MORE_ID, getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED)
                MEDIA_LIKED_ID -> createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_DOWNLOADS_ID -> createFolderItem(MEDIA_DOWNLOADS_ID, getString(R.string.downloads))
                MEDIA_LOCAL_MUSIC_ID -> createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music))
                "msg:login_required" -> createLoginPromptItem()
                else -> {
                    if (mediaId.startsWith("msg:")) {
                        createInfoItem(
                            mediaId,
                            getString(R.string.auto_unavailable_title),
                            getString(R.string.auto_unavailable_subtitle),
                        )
                    } else {
                        val cached = songCache[mediaId]
                        if (cached != null) {
                            cached.toMediaItem()
                        } else if (mediaId.startsWith("spotify_track:")) {
                            val track = spotifyTrackCache[mediaId.removePrefix("spotify_track:")]
                            if (track != null) spotifyTrackToMediaItem(track) else createInfoItem(
                                "msg:unavailable|$mediaId",
                                getString(R.string.auto_unavailable_title),
                                getString(R.string.auto_unavailable_subtitle),
                            )
                        } else if (mediaId.startsWith("playlist:") || mediaId.startsWith("browse:") || mediaId.startsWith("spotify_playlist:") || mediaId.startsWith("spotify_album:")) {
                            createGridPlaylistItem(
                                mediaId = mediaId,
                                title = if (mediaId.contains("MPREb_")) "Album" else getString(R.string.playlists),
                                folderType = if (mediaId.contains("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                            )
                        } else {
                            createInfoItem(
                                "msg:unavailable|$mediaId",
                                getString(R.string.auto_unavailable_title),
                                getString(R.string.auto_unavailable_subtitle),
                            )
                        }
                    }
                }
            }
            LibraryResult.ofItem(item, null)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val activePlayer = player
            if (activePlayer != null && activePlayer.mediaItemCount > 0) {
                val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
                val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
                val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(currentItems),
                    startIndex,
                    startPositionMs,
                )
            }

            val downloaded = Downloads.getDownloadedSongs(this@PlaybackService)
            if (downloaded.isNotEmpty()) {
                val items = downloaded.map { it.toMediaItem() }
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(items),
                    0,
                    0L,
                )
            }

            val localSongs = LocalMediaRepository.getLocalMusic(this@PlaybackService)
            if (localSongs.isNotEmpty()) {
                val items = localSongs.map { it.toMediaItem() }
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(items),
                    0,
                    0L,
                )
            }

            val fallbackSongs = try {
                withTimeoutOrNull(2500L) {
                    val home = YtMusicRepository.home().getOrNull()
                    home?.shelves?.flatMap { shelf ->
                        shelf.items.mapNotNull { it.toMediaItemOrNull() }
                    }?.distinctBy { it.mediaId }
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (fallbackSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(fallbackSongs),
                    0,
                    0L,
                )
            }

            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.of(),
                0,
                0L,
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
            val (spTracks, spPlaylists, spAlbums) = searchSpotify(query)
            val spTotal = spTracks.size + spPlaylists.size + spAlbums.size
            if (spTotal > 0) {
                storeSpotifySearchResults(query, spTracks, spPlaylists, spAlbums)
                session.notifySearchResultChanged(browser, query, spTotal, params)
            } else {
                val (songs, browses) = searchAll(query)
                storeSearchResults(query, songs, browses)
                session.notifySearchResultChanged(browser, query, songs.size + browses.size, params)
            }
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val trimmed = query.trim()
            val cachedSpTracks = spotifySearchTracks[query]
            val cachedSpPlaylists = spotifySearchPlaylists[query]
            val cachedSpAlbums = spotifySearchAlbums[query]
            val (spTracks, spPlaylists, spAlbums) = if (
                cachedSpTracks != null || cachedSpPlaylists != null || cachedSpAlbums != null
            ) {
                Triple(cachedSpTracks.orEmpty(), cachedSpPlaylists.orEmpty(), cachedSpAlbums.orEmpty())
            } else {
                searchSpotify(query).also {
                    if (it.first.isNotEmpty() || it.second.isNotEmpty() || it.third.isNotEmpty()) {
                        storeSpotifySearchResults(query, it.first, it.second, it.third)
                    }
                }
            }

            val wantsPlaylistFirst = trimmed.contains("playlist", ignoreCase = true) ||
                trimmed.contains("mix", ignoreCase = true) ||
                trimmed.contains("daylist", ignoreCase = true)

            val items: List<MediaItem> = if (
                spTracks.isNotEmpty() || spPlaylists.isNotEmpty() || spAlbums.isNotEmpty()
            ) {
                val playlistItems = spPlaylists
                    .take(10)
                    .map { spotifyPlaylistToGridItem(it).asPlayableSearchItem("Playlists") }
                val albumItems = spAlbums
                    .take(6)
                    .map { spotifyAlbumToGridItem(it).asPlayableSearchItem("Albums") }
                val trackLimit = if (playlistItems.isNotEmpty() || albumItems.isNotEmpty()) 5 else 10
                val trackItems = spTracks
                    .take(trackLimit)
                    .map { spotifyTrackToMediaItem(it).withGroupTitle("Songs") }
                if (wantsPlaylistFirst) {
                    playlistItems + trackItems + albumItems
                } else {
                    trackItems + playlistItems + albumItems
                }
            } else {
                val cachedSongs = searchResults[query]
                val cachedBrowses = searchBrowseResults[query]
                val (songs, browses) = if (cachedSongs != null || cachedBrowses != null) {
                    cachedSongs.orEmpty() to cachedBrowses.orEmpty()
                } else {
                    searchAll(query).also { storeSearchResults(query, it.first, it.second) }
                }
                songs.forEach { songCache[it.videoId] = it }
                val browseItems = browses
                    .take(10)
                    .map { browseItemToGridItem(it).asPlayableSearchItem("Playlists & Albums") }
                val songLimit = if (browseItems.isNotEmpty()) 4 else 15
                val songItems = songs.take(songLimit).map { it.toMediaItem().withGroupTitle("Songs") }
                if (wantsPlaylistFirst) browseItems + songItems else songItems + browseItems
            }

            val returnParams = LibraryParams.Builder().setExtras(
                buildContentStyleBundle(
                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                ),
            ).build()
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), returnParams)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = resolveMediaItems(mediaItems)
            val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(resolved),
                validIndex,
                startPositionMs.coerceAtLeast(0L),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            resolveMediaItems(mediaItems)
        }

        private suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val id = item.mediaId
                val searchQuery = item.requestMetadata.searchQuery
                when {
                    !searchQuery.isNullOrBlank() -> {
                        val q = searchQuery.trim()
                        val matchedFromSpotify = mutableListOf<MediaItem>()
                        if (AppSettings.spotifySpdcToken.value.isNotBlank()) {
                            if (q.contains("daylist", ignoreCase = true)) {
                                val dlTracks = loadSpotifyPlaylistTracks(SPOTIFY_DAYLIST_ID).take(10)
                                matchedFromSpotify.addAll(matchSpotifyTracksToMediaItems(dlTracks, 2_500L))
                            } else {
                                val (spTracks, spPlaylists, _) = searchSpotify(q)
                                val wantsPlaylist = q.contains("playlist", ignoreCase = true) ||
                                    q.contains("mix", ignoreCase = true)
                                if (wantsPlaylist && spPlaylists.isNotEmpty()) {
                                    val plTracks = loadSpotifyPlaylistTracks(spPlaylists.first().id).take(10)
                                    matchedFromSpotify.addAll(matchSpotifyTracksToMediaItems(plTracks, 2_500L))
                                } else if (spTracks.isNotEmpty()) {
                                    matchedFromSpotify.addAll(matchSpotifyTracksToMediaItems(spTracks.take(6), 3_000L))
                                } else if (spPlaylists.isNotEmpty()) {
                                    val plTracks = loadSpotifyPlaylistTracks(spPlaylists.first().id).take(8)
                                    matchedFromSpotify.addAll(matchSpotifyTracksToMediaItems(plTracks, 2_500L))
                                }
                            }
                        }
                        if (matchedFromSpotify.isNotEmpty()) {
                            resolved.addAll(matchedFromSpotify)
                        } else {
                            val cached = searchResults[searchQuery]
                            val songs = if (!cached.isNullOrEmpty()) {
                                cached
                            } else {
                                try {
                                    withTimeoutOrNull(3000L) {
                                        YtMusicRepository.search(searchQuery, SearchFilter.SONGS).getOrNull()
                                            ?.mapNotNull { if (it is SearchResult.Track) it.song else null }
                                    } ?: emptyList()
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                            songs.forEach { songCache[it.videoId] = it }
                            if (songs.isNotEmpty()) {
                                resolved.addAll(songs.map { it.toMediaItem() })
                            }
                        }
                    }
                    extractSpotifyUriRef(id, item.requestMetadata.mediaUri) != null -> {
                        val (kind, spId) = extractSpotifyUriRef(id, item.requestMetadata.mediaUri)!!
                        when (kind) {
                            "track" -> {
                                val track = fetchSpotifyTrackById(spId)
                                if (track != null) {
                                    resolved.addAll(matchSpotifyTracksToMediaItems(listOf(track), 5_000L))
                                }
                            }
                            "playlist" -> {
                                val tracks = loadSpotifyPlaylistTracks(spId).take(10)
                                resolved.addAll(matchSpotifyTracksToMediaItems(tracks, 2_500L))
                            }
                            "album" -> {
                                val tracks = loadSpotifyAlbumTracks(spId).take(10)
                                resolved.addAll(matchSpotifyTracksToMediaItems(tracks, 2_500L))
                            }
                        }
                    }
                    id == MEDIA_FLOW_PLAY_ID -> {
                        val flowSongs = com.music.bitchord.data.flow.FlowStore.readSnapshot()
                            ?.let { (songs, _) -> songs }
                            ?.takeIf { it.isNotEmpty() }
                            ?: cachedQuickPicksSongs()
                        flowSongs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(flowSongs.map { it.toMediaItem() })
                    }
                    id.startsWith("spotify_track:") -> {
                        val trackId = id.removePrefix("spotify_track:")
                        val matched = spotifyMatchedCache[trackId] ?: spotifyTrackCache[trackId]?.let { track ->
                            try {
                                withTimeoutOrNull(5_000L) {
                                    com.music.bitchord.data.spotify.SpotifyImporter.matchTrack(track)
                                }?.let { match ->
                                    match.copy(thumbnailUrl = track.imageUrl ?: match.thumbnailUrl)
                                }
                            } catch (_: Exception) {
                                null
                            }?.also { song ->
                                spotifyMatchedCache[trackId] = song
                                songCache[song.videoId] = song
                            }
                        }
                        if (matched != null) {
                            resolved.add(matched.toMediaItem())
                        }
                    }
                    id.startsWith("spotify_playlist:") -> {
                        val playlistId = id.removePrefix("spotify_playlist:")
                        val tracks = loadSpotifyPlaylistTracks(playlistId).take(10)
                        resolved.addAll(matchSpotifyTracksToMediaItems(tracks, 2_500L))
                    }
                    id.startsWith("spotify_album:") -> {
                        val albumId = id.removePrefix("spotify_album:")
                        val tracks = loadSpotifyAlbumTracks(albumId).take(10)
                        resolved.addAll(matchSpotifyTracksToMediaItems(tracks, 2_500L))
                    }
                    id.startsWith("playlist:") -> {
                        val playlistId = id.removePrefix("playlist:")
                        val songs = try {
                            withTimeoutOrNull(5_000L) {
                                YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs?.ifEmpty { null }
                                    ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id.startsWith("browse:") -> {
                        val browseId = id.removePrefix("browse:")
                        val songs = try {
                            withTimeoutOrNull(5_000L) {
                                YtMusicRepository.browseSongs(browseId).getOrNull()?.songs?.ifEmpty { null }
                                    ?: YtMusicRepository.browseSongs("VL$browseId").getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_ROOT_ID || id == MEDIA_RECENTS_ID -> {
                        val songs = cachedRecentsSongs()
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_QUICK_PICKS_ID -> {
                        val songs = cachedQuickPicksSongs()
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_LIKED_ID -> {
                        val songs = cachedLikedSongs()
                        if (songs.isNotEmpty()) {
                            resolved.addAll(songs.map { it.toMediaItem() })
                        }
                    }
                    id == MEDIA_DOWNLOADS_ID -> {
                        val downloaded = Downloads.getDownloadedSongs(this@PlaybackService)
                        downloaded.forEach { songCache[it.videoId] = it }
                        resolved.addAll(downloaded.map { it.toMediaItem() })
                    }
                    id == MEDIA_PLAYLISTS_ID -> {
                        val songs = try {
                            withTimeoutOrNull(2500L) {
                                val playlists = YtMusicRepository.userPlaylists().getOrNull() ?: emptyList()
                                val first = playlists.firstOrNull()
                                if (first != null) {
                                    YtMusicRepository.browseSongs("VL${first.playlistId}").getOrNull()?.songs
                                        ?: YtMusicRepository.browseSongs(first.playlistId).getOrNull()?.songs
                                } else null
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_LOCAL_MUSIC_ID -> {
                        val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                        local.forEach { songCache[it.videoId] = it }
                        resolved.addAll(local.map { it.toMediaItem() })
                    }
                    songCache.containsKey(id) -> {
                        resolved.add(songCache[id]!!.toMediaItem())
                    }
                    item.localConfiguration != null -> {
                        resolved.add(item)
                    }
                    item.requestMetadata.mediaUri != null -> {
                        val uri = item.requestMetadata.mediaUri!!
                        val videoId = uri.getQueryParameter("v") ?: uri.lastPathSegment.orEmpty()
                        if (videoId.isNotEmpty() && !videoId.startsWith("http")) {
                            val cached = songCache[videoId]
                            if (cached != null) {
                                resolved.add(cached.toMediaItem())
                            } else {
                                resolved.add(Song(videoId = videoId, title = item.mediaMetadata.title?.toString() ?: "Track", artist = item.mediaMetadata.artist?.toString() ?: "Artist", thumbnailUrl = null).toMediaItem())
                            }
                        } else {
                            resolved.add(item.buildUpon().setUri(uri).build())
                        }
                    }
                    else -> {
                        val cached = songCache[id]
                        if (cached != null) {
                            resolved.add(cached.toMediaItem())
                        } else {
                            val song = item.toSong()
                            resolved.add(song.toMediaItem())
                        }
                    }
                }
            }
            return if (resolved.isEmpty()) mediaItems else resolved
        }
    }

    private fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
        val fromLong = page.toLong() * safePageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    private fun createGridPlaylistItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(folderType)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()

    private fun shelfToGridPlaylistItem(playlist: ShelfItem): MediaItem {
        val browseId = playlist.browseId ?: "VL${playlist.videoId}"
        val mediaId = if (browseId.startsWith("VL")) "playlist:${browseId.removePrefix("VL")}" else "browse:$browseId"
        return createGridPlaylistItem(
            mediaId = mediaId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            artworkUrl = playlist.thumbnailUrl,
        )
    }

    private fun spotifyPlaylistToGridItem(playlist: com.music.bitchord.data.spotify.SpotifyPlaylist): MediaItem =
        createGridPlaylistItem(
            mediaId = "spotify_playlist:${playlist.id}",
            title = playlist.name,
            subtitle = playlist.owner,
            artworkUrl = playlist.imageUrl,
        )

    private fun spotifyTrackToMediaItem(track: com.music.bitchord.data.spotify.SpotifyTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId("spotify_track:${track.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.imageUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun createFlowPlayItem(): MediaItem {
        val flowArt = com.music.bitchord.data.flow.FlowStore.readSnapshot()
            ?.let { (songs, _) -> songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl }
            ?: snapshotQuickPicks().firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
        return MediaItem.Builder()
            .setMediaId(MEDIA_FLOW_PLAY_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Flow")
                    .setSubtitle("Endless personal mix")
                    .setArtworkUri(flowArt?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    private fun MediaItem.withGroupTitle(groupTitle: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", groupTitle)
        }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private fun MediaItem.asPlayableSearchItem(groupTitle: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", groupTitle)
        }
        return buildUpon()
            .setMediaMetadata(
                mediaMetadata.buildUpon()
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun spotifyAlbumToGridItem(album: com.music.bitchord.data.spotify.SpotifyPlaylist): MediaItem =
        createGridPlaylistItem(
            mediaId = "spotify_album:${album.id}",
            title = album.name,
            subtitle = album.owner,
            artworkUrl = album.imageUrl,
            folderType = MediaMetadata.FOLDER_TYPE_ALBUMS,
        )

    private suspend fun matchSpotifyTracksToMediaItems(
        tracks: List<com.music.bitchord.data.spotify.SpotifyTrack>,
        perTrackTimeoutMs: Long,
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (track in tracks) {
            val matched = spotifyMatchedCache[track.id] ?: try {
                withTimeoutOrNull(perTrackTimeoutMs) {
                    com.music.bitchord.data.spotify.SpotifyImporter.matchTrack(track)
                }?.let { match ->
                    match.copy(thumbnailUrl = track.imageUrl ?: match.thumbnailUrl)
                }
            } catch (_: Exception) {
                null
            }?.also { song ->
                spotifyMatchedCache[track.id] = song
                songCache[song.videoId] = song
            }
            if (matched != null) {
                out.add(matched.toMediaItem())
            }
        }
        return out
    }

    private fun extractSpotifyUriRef(mediaId: String, mediaUri: Uri?): Pair<String, String>? {
        val candidates = listOfNotNull(mediaId.takeIf { it.isNotBlank() }, mediaUri?.toString())
        for (raw in candidates) {
            if (raw.startsWith("spotify:track:")) {
                val id = raw.removePrefix("spotify:track:").substringBefore("?").trim()
                if (id.isNotEmpty()) return "track" to id
            }
            if (raw.startsWith("spotify:playlist:")) {
                val id = raw.removePrefix("spotify:playlist:").substringBefore("?").trim()
                if (id.isNotEmpty()) return "playlist" to id
            }
            if (raw.startsWith("spotify:album:")) {
                val id = raw.removePrefix("spotify:album:").substringBefore("?").trim()
                if (id.isNotEmpty()) return "album" to id
            }
            if (raw.contains("open.spotify.com/")) {
                val u = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
                val segs = u.pathSegments.filterNot { it.startsWith("intl-") }
                val idx = segs.indexOfFirst { it == "track" || it == "playlist" || it == "album" }
                if (idx >= 0 && idx + 1 < segs.size) {
                    val id = segs[idx + 1].trim()
                    if (id.isNotEmpty()) return segs[idx] to id
                }
            }
        }
        return null
    }

    private suspend fun searchSpotify(
        query: String,
    ): Triple<
        List<com.music.bitchord.data.spotify.SpotifyTrack>,
        List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
        List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
    > {
        val safeQuery = query.trim().take(100)
        if (safeQuery.isEmpty() || AppSettings.spotifySpdcToken.value.isBlank()) {
            return Triple(emptyList(), emptyList(), emptyList())
        }
        val libraryMatches = snapshotSpotifyPlaylists()
            .filter { it.name.contains(safeQuery, ignoreCase = true) }
        val daylistMatch = if (safeQuery.contains("daylist", ignoreCase = true)) {
            listOfNotNull(cachedSpotifyDaylist())
        } else {
            emptyList()
        }
        val headers = spotifyApiHeaders()
        val apiResult = if (headers != null) {
            try {
                withTimeoutOrNull(4_500L) {
                    val encoded = URLEncoder.encode(safeQuery, "UTF-8")
                    val root = spotifyGetJson(
                        "https://api.spotify.com/v1/search?q=$encoded&type=track,playlist,album&limit=10",
                        headers,
                    ) ?: return@withTimeoutOrNull null
                    val tracks = root.spObj("tracks")?.spArr("items").orEmpty().mapNotNull { el ->
                        val obj = el.takeIf { it !is JsonNull }
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: return@mapNotNull null
                        parseSpotifyWebApiTrack(obj)
                    }
                    val playlists = root.spObj("playlists")?.spArr("items").orEmpty().mapNotNull { el ->
                        val obj = el.takeIf { it !is JsonNull }
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: return@mapNotNull null
                        parseSpotifyWebApiPlaylist(obj)
                    }
                    val albums = root.spObj("albums")?.spArr("items").orEmpty().mapNotNull { el ->
                        val obj = el.takeIf { it !is JsonNull }
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: return@mapNotNull null
                        val id = obj.spStr("id") ?: return@mapNotNull null
                        val name = obj.spStr("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val artist = obj.spArr("artists").orEmpty().mapNotNull { a ->
                            a.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }?.spStr("name")
                        }.joinToString(", ")
                        val imageUrl = obj.spArr("images")
                            ?.firstOrNull()?.takeIf { it !is JsonNull }
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?.spStr("url")
                        com.music.bitchord.data.spotify.SpotifyPlaylist(
                            id = id,
                            name = name,
                            owner = artist.ifBlank { null },
                            imageUrl = imageUrl,
                        )
                    }
                    Triple(tracks, playlists, albums)
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val tracks = apiResult?.first.orEmpty()
        val playlists = (daylistMatch + libraryMatches + apiResult?.second.orEmpty()).distinctBy { it.id }
        val albums = apiResult?.third.orEmpty().distinctBy { it.id }
        tracks.forEach { spotifyTrackCache[it.id] = it }
        if (tracks.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                tracks.take(4).forEach { track ->
                    if (!spotifyMatchedCache.containsKey(track.id)) {
                        try {
                            com.music.bitchord.data.spotify.SpotifyImporter.matchTrack(track)?.let { match ->
                                val song = match.copy(thumbnailUrl = track.imageUrl ?: match.thumbnailUrl)
                                spotifyMatchedCache[track.id] = song
                                songCache[song.videoId] = song
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        return Triple(tracks, playlists, albums)
    }

    private fun storeSpotifySearchResults(
        query: String,
        tracks: List<com.music.bitchord.data.spotify.SpotifyTrack>,
        playlists: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
        albums: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
    ) {
        while (spotifySearchTracks.size >= MAX_AUTO_SEARCH_CACHE_ENTRIES) {
            val key = spotifySearchTracks.keys.firstOrNull() ?: break
            spotifySearchTracks.remove(key)
            spotifySearchPlaylists.remove(key)
            spotifySearchAlbums.remove(key)
        }
        spotifySearchTracks[query] = tracks
        spotifySearchPlaylists[query] = playlists
        spotifySearchAlbums[query] = albums
        tracks.forEach { spotifyTrackCache[it.id] = it }
    }

    private suspend fun searchAll(query: String): Pair<List<Song>, List<BrowseItem>> {
        val safeQuery = query.trim().take(MAX_AUTO_SEARCH_QUERY_CHARS)
        if (safeQuery.isEmpty()) return emptyList<Song>() to emptyList()
        val (mainResults, playlistResults) = kotlinx.coroutines.coroutineScope {
            val mainJob = async {
                try {
                    withTimeoutOrNull(4_000L) {
                        YtMusicRepository.search(safeQuery, SearchFilter.ALL).getOrNull()
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val playlistJob = async {
                try {
                    withTimeoutOrNull(4_000L) {
                        YtMusicRepository.search("$safeQuery playlist", SearchFilter.ALL).getOrNull()
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            mainJob.await() to playlistJob.await()
        }
        val songs = mainResults.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.distinctBy { it.videoId }
        val browses = (mainResults + playlistResults)
            .mapNotNull { (it as? SearchResult.Browse)?.item }
            .distinctBy { it.browseId }
        return songs to browses
    }

    private fun storeSearchResults(query: String, songs: List<Song>, browses: List<BrowseItem>) {
        while (searchResults.size >= MAX_AUTO_SEARCH_CACHE_ENTRIES) {
            val key = searchResults.keys.firstOrNull() ?: break
            searchResults.remove(key)
            searchBrowseResults.remove(key)
        }
        searchResults[query] = songs
        searchBrowseResults[query] = browses
        songs.forEach { songCache[it.videoId] = it }
    }

    private fun browseItemToGridItem(item: BrowseItem): MediaItem = createGridPlaylistItem(
        mediaId = "browse:${item.browseId}",
        title = item.title,
        subtitle = item.subtitle,
        artworkUrl = item.thumbnailUrl,
        folderType = if (item.browseId.startsWith("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    )

    private fun createFolderItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
        isGrid: Boolean = false,
    ): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun MediaItem.withGridStyle(): MediaItem {
        val currentExtras = mediaMetadata.extras ?: Bundle()
        val newExtras = Bundle(currentExtras).apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }
        return buildUpon()
            .setMediaMetadata(
                mediaMetadata.buildUpon()
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setExtras(newExtras)
                    .build(),
            )
            .build()
    }

    private fun ShelfItem.toMediaItemOrNull(): MediaItem? {
        val vid = videoId
        if (vid != null) {
            val song = Song(
                videoId = vid,
                title = title,
                artist = InnertubeParser.artistFromSubtitle(subtitle),
                thumbnailUrl = thumbnailUrl,
            )
            songCache[vid] = song
            return song.toMediaItem()
        }
        val bid = browseId
        if (bid != null) {
            return MediaItem.Builder()
                .setMediaId("browse:$bid")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setArtworkUri(thumbnailUrl.artworkAt(NOTIFICATION_ART_PX)?.let { Uri.parse(it) })
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                        .build(),
                )
                .build()
        }
        return null
    }

    private fun ShelfItem.toSongOrNull(): Song? {
        val id = videoId ?: return null
        return Song(
            videoId = id,
            title = title,
            artist = InnertubeParser.artistFromSubtitle(subtitle),
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun createLoginPromptItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("msg:login_required")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.auto_login_title))
                    .setSubtitle(getString(R.string.auto_login_subtitle))
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    private companion object {
        const val MEDIA_ROOT_ID = "root"
        const val MEDIA_HOME_ID = "home"
        const val MEDIA_SPOTIFY_ID = "spotify"
        const val MEDIA_FLOW_PLAY_ID = "home:flow"
        const val MEDIA_RECENTS_ID = "recents"
        const val MEDIA_QUICK_PICKS_ID = "quick_picks"
        const val MEDIA_PLAYLISTS_ID = "playlists"
        const val MEDIA_MORE_ID = "more"
        const val MEDIA_LIKED_ID = "liked"
        const val FOREIGN_CACHE_PURGE_DELAY_MS = 30_000L
        const val FOREIGN_CACHE_KEEP_AHEAD = 3
        const val MEDIA_DOWNLOADS_ID = "downloads"
        const val MEDIA_LOCAL_MUSIC_ID = "local_music"
        const val MAX_AUTO_PAGE_SIZE = 50
        const val MAX_AUTO_SEARCH_QUERY_CHARS = 200
        const val MAX_AUTO_SEARCH_CACHE_ENTRIES = 20

        val ROOT_CHILDREN: List<String> = listOf(
            MEDIA_HOME_ID,
            MEDIA_SPOTIFY_ID,
            MEDIA_RECENTS_ID,
            MEDIA_MORE_ID,
        )

        const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        const val STREAM_INFO_BYTES = 34
        const val CHANNEL_ID = "bitchord_playback"
        const val SESSION_ID = "BitChordPlayback"
        const val PROGRESS_SAMPLE_MS = 5_000L
        const val MIN_LOUDNESS_GAIN_MB = -1500
        const val MAX_LOUDNESS_GAIN_MB = 300
        const val LOUDNESS_RETRY_MS = 6_000L
        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L
        const val STREAM_CHUNK_BYTES = 1L * 1024 * 1024
        const val MIN_SILENCE_US = 1_000_000L
        const val FAR_BUFFER_MS = 15 * 60 * 1000
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024
        const val BACK_BUFFER_MS = 30 * 1000
        const val START_PLAYBACK_MS = 500
        const val RESUME_PLAYBACK_MS = 2_000
        const val RESOLVE_TIMEOUT_MS = 120_000L
        const val SUBSTITUTE_TIMEOUT_MS = 20_000L
        const val UPGRADE_MIN_REMAINING_MS = 20_000L
        const val MANUAL_UPGRADE_COOLDOWN_MS = 30_000L
        const val UPGRADE_CROSSFADE_POLL_MS = 250L
        const val UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS = 20_000L
        const val UPGRADE_AFTER_CROSSFADE_MS = 5_000L
        const val UPGRADE_PROVE_MS = 10_000L
        const val UPGRADE_PROVE_STEP_MS = 200L
        const val UPGRADE_AUDITION_MS = 25_000L
        const val UPGRADE_PREBUFFER_MS = 12_000L
        const val UPGRADE_HEADER_BYTES = 1L * 1024 * 1024
        const val MIN_PROVEN_LOAD_BYTES = UPGRADE_HEADER_BYTES
        const val ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024
        const val AUDITION_BUFFER_MS = 40_000
        const val AUDITION_BUFFER_BYTES = 24 * 1024 * 1024
        const val AUDITION_RELEASE_MS = 250L
        const val DURATION_SETTLE_MS = 8_000L
        const val MAX_RECOVERIES = 2
        const val MAX_CONSECUTIVE_SKIPS = 4
        const val PERMANENT_CAUSE_DEPTH = 8
        const val RECOVERY_DELAY_MS = 350L
        const val AUTOPLAY_EMPTY_REFRESH_DELAY_MS = 2_000L
        const val MAX_AUTOPLAY_EMPTY_REFRESHES = 1
        const val PARTY_QUEUE_ECHO_TIMEOUT_MS = 5_000L
    }
}
