package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.AutomixPanelState
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.smart.AutomixOverride
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the player takes from the application it is drawn in.
 *
 * The player itself — its layout, its gestures, its animations, the lyric sheet
 * and the queue — is one piece of code for the phone and the desktop alike.
 * What differs is underneath it: where settings are kept, how a looping clip is
 * decoded, what an audio output is, which party this device is in. Each
 * application answers those once, here, and installs its answer with
 * [PlayerPlatform.install] before the player is first drawn.
 */
interface PlayerHost {
    val settings: PlayerSettingsSource

    /** A Canvas already settled for [song], without a lookup. */
    fun cachedCanvas(song: Song): CanvasArtwork?

    /** Looks a Canvas up for [song]; null is the normal answer. */
    suspend fun canvasFor(song: Song): CanvasArtwork?

    /** The looping clip over the cover, decoded however this platform decodes video. */
    @Composable
    fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier)

    /**
     * Artwork for [song], resolving covers that live inside a remote file
     * (WebDAV, SMB) where the platform can. Returns [Song.thumbnailUrl] otherwise.
     */
    @Composable
    fun rememberRemoteArtworkUrl(song: Song?): String?

    /**
     * The Flow station's own looping clip: the wide cut for the radio banner
     * and the square cut for its home tile. Null where the platform ships no
     * such asset, in which case callers keep their gradient.
     */
    @Composable
    fun rememberFlowVideo(square: Boolean): CanvasArtwork? = null

    /** The system output level the player's volume bar drives, if it drives one. */
    val volume: SystemVolume

    /** The outputs the output drawer lists, the active one marked. */
    @Composable
    fun rememberAudioOutputs(): List<AudioOutputDevice>

    /**
     * Wraps opening the output drawer in whatever it needs first — on Android,
     * the Bluetooth permission that lets it name headphones.
     */
    @Composable
    fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit

    fun selectAudioOutput(id: Int?)

    /** The negotiated output format, as the pipeline row and the hi-res shine read it. */
    val outputFormat: StateFlow<OutputFormatUi>

    /** The Listen Together party this device is in, as the player draws it. */
    val party: StateFlow<PartyUi>

    suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult

    suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult

    /** A short, non-blocking notice — a Toast on the phone. */
    fun showMessage(message: String)

    /** The full signal-chain readout, opened from the output drawer. */
    @Composable
    fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit)

    /** Casting to a network receiver. Absent, and so never drawn, where the platform has none. */
    val castState: StateFlow<CastUi> get() = NoCast

    /** The device picker the output drawer's Cast row opens, in the pipeline dialog's card. */
    @Composable
    fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) = Unit

    /**
     * Whether lines can be put on a share card here. False hides the pick mode
     * altogether rather than offering a mode with nowhere to put its result.
     */
    val lyricsShareAvailable: Boolean get() = false

    /**
     * The picked lines, drawn as a picture this platform can write out and send.
     * Absent, and so never called, where [lyricsShareAvailable] is false.
     */
    @Composable
    fun LyricsShareSheet(hazeState: HazeState, request: LyricsShareRequest, onDismiss: () -> Unit) = Unit

    /**
     * Whether this platform can search for songs to put on the queue.
     *
     * False hides the search row altogether rather than offering one that
     * leads to an empty screen. The phone answers true; a platform with no
     * catalogue behind it has nothing to search.
     */
    val queueSearchAvailable: Boolean get() = false

    /**
     * The songs [query] is asking for, for the queue's search.
     *
     * One list, two sources: the catalogue's answer and the device's own
     * songs, with the device's copy first where both have one — a song the
     * listener downloaded is a song they can hear without a network. The
     * platform reads both; [QueueSearch][com.music.bitchord.playback.QueueSearch]
     * decides what the two lists mean together, so the rule is one rule.
     *
     * A platform with no catalogue answers with the device's songs and
     * [QueueSearchResult.catalogueUnavailable] false — there was nothing to
     * fail.
     */
    suspend fun searchQueueSongs(query: String): QueueSearchResult = QueueSearchResult(emptyList())

    /**
     * The songs to offer for [song] under [mode]: what else sounds like it,
     * what else the artist has, the rest of its record.
     *
     * A platform with no catalogue answers with nothing, which the box reads as
     * an empty list rather than as a failure — there was nothing to fail.
     */
    suspend fun queueSuggestions(
        song: Song,
        mode: com.music.bitchord.playback.QueueSuggestion,
    ): List<Song> = emptyList()
}

/** What the queue's search found. */
data class QueueSearchResult(
    val songs: List<Song>,
    /**
     * True when the catalogue could not be reached and [songs] is only what
     * the device holds.
     *
     * Said out loud on the search screen, because "nothing found" and "the
     * catalogue is unreachable" look exactly alike otherwise — and on a train
     * the second one is the answer, with the downloaded songs under it.
     */
    val catalogueUnavailable: Boolean = false,
)

private val NoCast = MutableStateFlow(CastUi())

/** What the output drawer needs to know about casting to draw its row. */
data class CastUi(
    /** Whether this device can cast at all. False hides the row. */
    val supported: Boolean = false,
    /** The receiver the music is on now, or null when it is on this device. */
    val connectedName: String? = null,
    val connecting: Boolean = false,
)

/** Where the installed [PlayerHost] lives. */
object PlayerPlatform {
    @Volatile
    private var installed: PlayerHost? = null

    val host: PlayerHost
        get() = installed ?: error("PlayerPlatform.install() was never called")

    fun install(host: PlayerHost) {
        installed = host
    }
}

/** The settings the player reads, named and typed as the phone's AppSettings has them. */
interface PlayerSettingsSource {
    val animatedCanvas: StateFlow<Boolean>
    val audioQualityCellular: StateFlow<AudioQuality>
    val audioQualityWifi: StateFlow<AudioQuality>
    val canvasOverCellular: StateFlow<Boolean>
    val fullBleedArtwork: StateFlow<Boolean>
    val hideSongStatus: StateFlow<Boolean>
    /** Hide the device status bar while the player is up. Always false where there is none. */
    val hideStatusBar: StateFlow<Boolean>
    val hideVolumeBar: StateFlow<Boolean>
    val lastPlayerScreen: StateFlow<LastPlayerScreen>
    val legacyMeshGradient: StateFlow<Boolean>
    val lyricsBlur: StateFlow<Boolean>
    val textGlow: StateFlow<Boolean>
    val lyricsOffsetMs: StateFlow<Int>
    val lyricsSourceOrder: StateFlow<List<LyricsSource>>
    val meteredConnection: StateFlow<Boolean?>
    val preferUsbDac: StateFlow<Boolean>
    val prioritizeSpotifyCanvas: StateFlow<Boolean>
    /** Whether Spotify Canvas is looked up at all; the other providers still are. */
    val spotifyCanvasEnabled: StateFlow<Boolean>
    val reduceAnimation: StateFlow<Boolean>
    val reduceDynamicBlur: StateFlow<Boolean>
    val showNerdStats: StateFlow<Boolean>
    /** Whether the Automix "this song / next song" line under the sleeve is drawn. */
    val showNextTrackStatus: StateFlow<Boolean>
    /** Whether the "Upgrading quality" badge is drawn while a better copy is sought. */
    val showUpgradeBadge: StateFlow<Boolean>
    /** Whether the "Lossless" badge is drawn for a losslessly decoded stream. */
    val showLosslessBadge: StateFlow<Boolean>
    /** Whether that badge may say "Hi-Res Lossless" instead of "Lossless". */
    val showHiResBadge: StateFlow<Boolean>
    val smartAnalysis: StateFlow<SmartAnalysis>
    /**
     * Everything the Automix panel shows: the analysis in full, the plan that
     * came out of it, and the listener's own choice for the playing track.
     */
    val automixPanel: StateFlow<AutomixPanelState>
    val smartFadeEnabled: StateFlow<Boolean>
    /** True only while an analysed Automix transition is audibly mixing two tracks. */
    val smartMixInProgress: StateFlow<Boolean>
    /** The Automix blend in flight, for the scrubber's beat glow; null between blends. */
    val smartMixBlend: StateFlow<MixBlend?>
    val smartTransitionWindow: StateFlow<TransitionWindow?>
    val spotifyCanvasAutoHide: StateFlow<Boolean>
    val syncedLyrics: StateFlow<Boolean>
    val showNowPlayingLyrics: StateFlow<Boolean>
    val translationLanguage: StateFlow<String>
    val versionAlignmentInProgress: StateFlow<Boolean>

    fun setLastPlayerScreen(value: LastPlayerScreen)
    fun setLyricsOffsetMs(value: Int)

    /**
     * Stores the listener's own Automix choice for a track.
     *
     * The choice lives with the song, so it means the same thing every time
     * that song plays. An empty choice is the same as no choice, and clears
     * the track's entry rather than storing an empty one.
     */
    fun setAutomixProfile(trackId: String, override: AutomixOverride)

    /** Forgets the listener's Automix choice for a track, leaving Automix to decide. */
    fun clearAutomixProfile(trackId: String)

    /**
     * Whether this platform can play a preview of the transition on demand.
     *
     * False hides the button altogether rather than offering one that does
     * nothing: a desktop has no second deck to mix with, and a control that
     * does nothing when it is pressed is worse than no control at all.
     */
    val automixPreviewAvailable: Boolean get() = true

    /**
     * Plays the planned transition, or stops the one that is playing.
     *
     * A preview of the handoff and nothing else: the music either side of it
     * is left where it was afterwards, so hearing the transition does not
     * consume it. A platform with no second deck to blend with does nothing.
     */
    fun previewAutomix(start: Boolean)

    /**
     * Asks for the pair's analysis to be fetched and measured *now*.
     *
     * The panel's own demand, and the reason it is not the same as the
     * background analysis the engine already does: the background analysis
     * runs on its own schedule, and the panel opens on two lanes that the
     * listener is looking at. A platform with no analysis engine does
     * nothing.
     */
    fun forceAutomixAnalysis() = Unit
}

/** The player's name for the installed host's settings, read where AppSettings used to be. */
internal val PlayerSettings: PlayerSettingsSource
    get() = PlayerPlatform.host.settings

/** The lyric offset sheet's range, in milliseconds either way. */
const val MIN_LYRICS_OFFSET_MS = -5_000
const val MAX_LYRICS_OFFSET_MS = 5_000

/** A system output level, 0..1, and the means to set it. */
interface SystemVolume {
    /** The level as the system reports it, following hardware keys and other apps. */
    val level: StateFlow<Float>
    fun set(level: Float)

    /**
     * Reads the level again. Android keeps one media volume per output and
     * swaps which is in force on a route change without announcing it.
     */
    fun refresh()
}

enum class AudioOutputKind { PHONE, WIRED, USB, BLUETOOTH, HDMI, OTHER }

data class AudioOutputDevice(
    val id: Int,
    /** Blank for a device with no name of its own; the drawer supplies one. */
    val name: String,
    val kind: AudioOutputKind,
    val isActive: Boolean = false,
)

data class OutputFormatUi(
    /** "24-bit PCM · 48 kHz" — whichever parts are known. */
    val summary: String = "",
    /** Whether the negotiated output could carry more than 16-bit / 48 kHz. */
    val carriesHiRes: Boolean = false,
)

data class PartyUi(
    val inParty: Boolean = false,
    val controlsLocked: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val you: PartyMember? = null,
    val code: String? = null,
)

sealed interface LyricsTranslationResult {
    data class Translated(val lines: List<LyricLine>) : LyricsTranslationResult
    data class SameLanguage(val language: String) : LyricsTranslationResult
    data object Unavailable : LyricsTranslationResult
}

sealed interface LyricsRomanizationResult {
    data class Romanized(val lines: List<LyricLine>) : LyricsRomanizationResult
    data object AlreadyRomanized : LyricsRomanizationResult
    data object Unavailable : LyricsRomanizationResult
}

/** How a clip fills the bounds supplied by its caller. */
enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

/** Everything [CanvasArtworkPlayer] is asked, handed to the platform's decoder as one value. */
class CanvasVideoSpec(
    val canvas: CanvasArtwork,
    val isPlaying: Boolean,
    val contentMode: CanvasContentMode,
    val alignPortraitTop: Boolean,
    val onAspectRatioChanged: (Float) -> Unit,
    val portraitRevealBounds: IntSize,
    val presentationAlpha: () -> Float,
    val onRenderedChanged: (Boolean) -> Unit,
    val onFrameCaptured: (ImageBitmap) -> Unit,
    val refreshFrameEveryMs: Long?,
    val frameCapturePx: Int,
    val onCoverChanged: (Float) -> Unit,
    val bottomFade: Float,
    val bottomFadeEndPx: Float?,
    val pausedForTransition: Boolean,
)

/**
 * The looping video that plays over a track's cover art — see
 * [PlayerHost.CanvasVideo] for the decoder behind it on each platform.
 */
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    onAspectRatioChanged: (Float) -> Unit = {},
    portraitRevealBounds: IntSize = IntSize.Zero,
    presentationAlpha: () -> Float = { 1f },
    onRenderedChanged: (Boolean) -> Unit = {},
    onFrameCaptured: (ImageBitmap) -> Unit = {},
    refreshFrameEveryMs: Long? = null,
    frameCapturePx: Int = CANVAS_FRAME_CAPTURE_PX,
    onCoverChanged: (Float) -> Unit = {},
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    pausedForTransition: Boolean = false,
) {
    PlayerPlatform.host.CanvasVideo(
        CanvasVideoSpec(
            canvas = canvas,
            isPlaying = isPlaying,
            contentMode = contentMode,
            alignPortraitTop = alignPortraitTop,
            onAspectRatioChanged = onAspectRatioChanged,
            portraitRevealBounds = portraitRevealBounds,
            presentationAlpha = presentationAlpha,
            onRenderedChanged = onRenderedChanged,
            onFrameCaptured = onFrameCaptured,
            refreshFrameEveryMs = refreshFrameEveryMs,
            frameCapturePx = frameCapturePx,
            onCoverChanged = onCoverChanged,
            bottomFade = bottomFade,
            bottomFadeEndPx = bottomFadeEndPx,
            pausedForTransition = pausedForTransition,
        ),
        modifier,
    )
}

/**
 * The longest edge of a frame handed to [CanvasArtworkPlayer]'s onFrameCaptured.
 *
 * Small on purpose. Everything downstream averages the frame down — the mesh
 * to thirty-six cells, the palette to a handful of colours — so a bigger
 * frame buys nothing but a bigger copy off the GPU, and the copy is what
 * decides whether a phone stays cool while a clip plays.
 */
const val CANVAS_FRAME_CAPTURE_PX = 64

/** Media3's repeat modes, by value, so the transport can speak them without Media3. */
object RepeatModes {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

@Composable
internal fun rememberAudioOutputs(): List<AudioOutputDevice> = PlayerPlatform.host.rememberAudioOutputs()

@Composable
internal fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit =
    PlayerPlatform.host.rememberOutputPicker(onOpen)

@Composable
internal fun rememberRemoteArtworkUrl(song: Song?): String? =
    PlayerPlatform.host.rememberRemoteArtworkUrl(song)

@Composable
internal fun rememberFlowVideo(square: Boolean): CanvasArtwork? =
    PlayerPlatform.host.rememberFlowVideo(square)

@Composable
internal fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
    PlayerPlatform.host.AudioPipelineDialog(hazeState, isPlaying, onDismiss)
