package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.settings.AutomixPerformanceMode

/**
 * The listener's saved preferences, as the app window reads and edits them.
 *
 * One object rather than twenty-eight loose `remember`ed locals in [BitChordDesktopApp]. Each was
 * read from [DesktopPersistence] once, when the window opened, and every lambda that touched one
 * captured it on its own; held here they are a single capture. Each value is still read once, at
 * construction, and the callers still write each change back to [DesktopPersistence] themselves,
 * exactly as before.
 */
@Stable
internal class DesktopAppSettings(persistence: DesktopPersistence) {
    var autoplay by mutableStateOf(persistence.boolean("autoplay", true))
    var dontRepeatSuggestions by mutableStateOf(persistence.boolean(KEY_DONT_REPEAT_SUGGESTIONS, false))
    var filterNonMusicAudio by mutableStateOf(persistence.boolean(DesktopLocalMusic.KEY_FILTER_NON_MUSIC_AUDIO, false))
    var automix by mutableStateOf(persistence.boolean("automix", false))
    var automixPerformance by mutableStateOf(
        runCatching { AutomixPerformanceMode.valueOf(persistence.string("automix_performance", "BALANCED")) }
            .getOrDefault(AutomixPerformanceMode.BALANCED),
    )
    var shuffle by mutableStateOf(persistence.boolean("shuffle", false))
    var repeatMode by mutableStateOf(
        runCatching { DesktopRepeatMode.valueOf(persistence.string("repeat_mode", "OFF")) }
            .getOrDefault(DesktopRepeatMode.OFF),
    )
    var playbackSpeed by mutableStateOf(persistence.string("playback_speed", "1.0").toFloatOrNull() ?: 1.0f)
    var volume by mutableStateOf(persistence.string("volume", "1.0").toFloatOrNull()?.coerceIn(0.0f, 1.0f) ?: 1.0f)
    var crossfadeSeconds by mutableStateOf(persistence.string("crossfade_seconds", "0").toIntOrNull()?.coerceIn(0, 12) ?: 0)
    var downloadQuality by mutableStateOf(persistence.string("download_quality", "LOSSLESS"))
    var trayIconEnabled by mutableStateOf(persistence.boolean("tray_icon", true))
    var closeToTray by mutableStateOf(persistence.boolean("close_to_tray", true))

    // The three the FFmpeg engine unlocked: none of them could exist while JavaFX owned the decode,
    // because none of them can be done without the samples themselves.
    var spatialAudio by mutableStateOf(persistence.boolean("spatial_audio", false))
    var dolbyAtmos by mutableStateOf(persistence.boolean("dolby_atmos", true))
    var skipSilence by mutableStateOf(persistence.boolean("skip_silence", false))
    var outputPrecision by mutableStateOf(persistence.string("output_precision", "PCM_16"))

    /** The blob backdrop the mesh replaced, kept as an opt-out. */
    var legacyMeshGradient by mutableStateOf(persistence.boolean("legacy_mesh_gradient", false))
    var animatedCanvas by mutableStateOf(persistence.boolean("animated_canvas", true))
    var spotifyCanvasCookie by mutableStateOf(DesktopSpotifyToken.cookie())
    var showNerdStats by mutableStateOf(persistence.boolean("show_nerd_stats", false))
    var syncedLyrics by mutableStateOf(persistence.boolean(DesktopLyricsClient.KEY_SYNCED_LYRICS, true))
    var lyricsBlur by mutableStateOf(persistence.boolean(DesktopLyricsClient.KEY_LYRICS_BLUR, true))
    var prioritizeSyllables by mutableStateOf(persistence.boolean(DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES, false))
    var lyricsOrder by mutableStateOf(persistence.lyricsSourceOrder())
    var lyricsOn by mutableStateOf(persistence.lyricsEnabledSources())
    var fullBleedArtwork by mutableStateOf(persistence.boolean("full_bleed_artwork", false))
    var audioQuality by mutableStateOf(persistence.audioQuality())
}
