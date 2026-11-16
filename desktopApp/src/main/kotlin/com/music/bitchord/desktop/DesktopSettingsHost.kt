package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Settings and the pages its rows lead to, standing in for the page while [page] is open.
 *
 * Lifted out of [BitChordDesktopApp] as it was. The preferences are read and written on
 * [settings], and saved to [persistence] at the same call sites as before; everything else the
 * window owns comes in as a value or a callback.
 *
 * [scope] is the window's own, so a source check outlives a step to another Settings page, as it
 * did when this lived in the window.
 */
@Composable
internal fun DesktopSettingsHost(
    page: DesktopSettingsPage,
    overlays: DesktopOverlays,
    settings: DesktopAppSettings,
    persistence: DesktopPersistence,
    scope: CoroutineScope,
    contentPadding: PaddingValues,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    song: Song?,
    /**
     * The sink's format as text. A plain read of the engine rather than state, so it is read
     * again on each of [playbackClock]'s updates, as it was when the window recomposed on each.
     */
    outputSummary: () -> String,
    playbackClock: State<DesktopPlaybackState>,
    sleepTimerMinutes: Int?,
    sleepAfterTrack: Boolean,
    sourceConfigs: List<DesktopSourceConfig>,
    sourceStatus: Map<String, String>,
    onSourceConfigsChange: (List<DesktopSourceConfig>) -> Unit,
    onSourceStatus: (id: String, status: String) -> Unit,
    onAutoplayChange: (Boolean) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onSleepTimerCycle: () -> Unit,
    onLocalMusicChanged: () -> Unit,
    onBack: () -> Unit,
) {
    // The countdown under the sleep timer row, the only place it is drawn. It ticks here, while
    // Settings is open, rather than in the window, where a running timer recomposed everything
    // once a second whatever page was showing.
    var sleepTimerTick by remember { mutableStateOf(0L) }
    LaunchedEffect(sleepTimerMinutes) {
        while (sleepTimerMinutes != null && currentCoroutineContext().isActive) {
            delay(1_000L)
            sleepTimerTick++
        }
    }
    val sleepRemainingMs = remember(sleepTimerMinutes, sleepTimerTick) {
        DesktopSleepTimer.remainingMs()
    }
    Box(
        Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        CompositionLocalProvider(LocalDesktopPanelIsPage provides true) {
        when (page) {
            DesktopSettingsPage.MAIN -> DesktopSettingsScreen(
                query = query,
                onQueryChange = onQueryChange,
                listState = listState,
                autoplay = settings.autoplay,
                onAutoplayChange = onAutoplayChange,
                automix = settings.automix,
                onAutomixChange = {
                    settings.automix = it
                    persistence.saveBoolean("automix", it)
                },
                automixPerformance = settings.automixPerformance,
                onAutomixPerformanceChange = {
                    settings.automixPerformance = it
                    persistence.saveString("automix_performance", it.name)
                },
                shuffle = settings.shuffle,
                onShuffleChange = onShuffleChange,
                repeatMode = settings.repeatMode,
                onRepeatModeChange = {
                    settings.repeatMode = it
                    persistence.saveString("repeat_mode", it.name)
                },
                playbackSpeed = settings.playbackSpeed,
                onPlaybackSpeedChange = {
                    settings.playbackSpeed = it
                    persistence.saveString("playback_speed", it.toString())
                },
                crossfadeSeconds = settings.crossfadeSeconds,
                onCrossfadeSecondsChange = {
                    settings.crossfadeSeconds = it
                    persistence.saveString("crossfade_seconds", it.toString())
                },
                animatedCanvas = settings.animatedCanvas,
                showNerdStats = settings.showNerdStats,
                fullBleedArtwork = settings.fullBleedArtwork,
                onAnimatedCanvasChange = {
                    settings.animatedCanvas = it
                    persistence.saveBoolean("animated_canvas", it)
                },
                spotifyCanvasReady = settings.spotifyCanvasCookie.isNotBlank(),
                onOpenSpotifyCanvasSetup = { overlays.settingsPage = DesktopSettingsPage.SPOTIFY_CANVAS },
                dontRepeatSuggestions = settings.dontRepeatSuggestions,
                onDontRepeatSuggestionsChange = {
                    settings.dontRepeatSuggestions = it
                    persistence.saveBoolean(KEY_DONT_REPEAT_SUGGESTIONS, it)
                },
                onChooseLocalMusicFolder = { onChosen ->
                    DesktopLocalMusic.chooseFolder()?.let { chosen ->
                        DesktopLocalMusic.setFolder(chosen)
                        onChosen()
                        onLocalMusicChanged()
                    }
                },
                onLocalMusicFolderChanged = { onLocalMusicChanged() },
                filterNonMusicAudio = settings.filterNonMusicAudio,
                onFilterNonMusicAudioChange = {
                    settings.filterNonMusicAudio = it
                    persistence.saveBoolean(DesktopLocalMusic.KEY_FILTER_NON_MUSIC_AUDIO, it)
                    // The scan's result changes with it, so it has to be taken again.
                    onLocalMusicChanged()
                },
                syncedLyrics = settings.syncedLyrics,
                onSyncedLyricsChange = {
                    settings.syncedLyrics = it
                    persistence.saveBoolean(DesktopLyricsClient.KEY_SYNCED_LYRICS, it)
                },
                lyricsBlur = settings.lyricsBlur,
                onLyricsBlurChange = {
                    settings.lyricsBlur = it
                    persistence.saveBoolean(DesktopLyricsClient.KEY_LYRICS_BLUR, it)
                },
                enabledLyricsSources = DesktopLyricsClient.enabledSources(settings.lyricsOrder, settings.lyricsOn),
                onOpenLyricsSources = { overlays.settingsPage = DesktopSettingsPage.LYRICS_SOURCES },
                onOpenTranslationLanguage = { overlays.settingsPage = DesktopSettingsPage.TRANSLATION_LANGUAGE },
                onOpenEqualizer = { overlays.settingsPage = DesktopSettingsPage.EQUALIZER },
                onOpenAudioOutput = { overlays.settingsPage = DesktopSettingsPage.AUDIO_OUTPUT },
                onOpenListenTogether = { overlays.settingsPage = DesktopSettingsPage.LISTEN_TOGETHER },
                onShowNerdStatsChange = {
                    settings.showNerdStats = it
                    persistence.saveBoolean("show_nerd_stats", it)
                },
                onFullBleedArtworkChange = {
                    settings.fullBleedArtwork = it
                    persistence.saveBoolean("full_bleed_artwork", it)
                },
                legacyMeshGradient = settings.legacyMeshGradient,
                onLegacyMeshGradientChange = {
                    settings.legacyMeshGradient = it
                    persistence.saveBoolean("legacy_mesh_gradient", it)
                },
                spatialAudio = settings.spatialAudio,
                onSpatialAudioChange = {
                    settings.spatialAudio = it
                    persistence.saveBoolean("spatial_audio", it)
                },
                dolbyAtmos = settings.dolbyAtmos,
                onDolbyAtmosChange = {
                    settings.dolbyAtmos = it
                    DesktopAddonSettings.dolbyAtmosEnabled = it
                    persistence.saveBoolean("dolby_atmos", it)
                    DesktopAddonSource.clearCompletedTrackCalls()
                },
                skipSilence = settings.skipSilence,
                onSkipSilenceChange = {
                    settings.skipSilence = it
                    persistence.saveBoolean("skip_silence", it)
                },
                outputPrecision = settings.outputPrecision,
                onOutputPrecisionChange = {
                    settings.outputPrecision = it
                    persistence.saveString("output_precision", it)
                },
                outputSummary = playbackClock.value.let { outputSummary() },
                trayIconEnabled = settings.trayIconEnabled,
                closeToTray = settings.closeToTray,
                onCloseToTrayChange = {
                    settings.closeToTray = it
                    persistence.saveBoolean("close_to_tray", it)
                },
                onTrayIconChange = {
                    settings.trayIconEnabled = it
                    persistence.saveBoolean("tray_icon", it)
                },
                downloadQuality = settings.downloadQuality,
                onDownloadQualityChange = {
                    settings.downloadQuality = it
                    persistence.saveString("download_quality", it)
                },
                audioQuality = settings.audioQuality,
                onAudioQualityChange = {
                    settings.audioQuality = it
                    persistence.saveAudioQuality(it)
                },
                sleepTimerMinutes = sleepTimerMinutes,
                sleepAfterTrack = sleepAfterTrack,
                sleepRemainingMs = sleepRemainingMs,
                onSleepTimerCycle = onSleepTimerCycle,
                sourceConfigs = sourceConfigs,
                sourceStatus = sourceStatus,
                onSourceEnabledChange = { config, enabled ->
                    val next = sourceConfigs.map {
                        if (it.id == config.id && it.kind != DesktopSourceKind.YOUTUBE) {
                            it.copy(enabled = enabled)
                        } else {
                            it
                        }
                    }
                    persistence.saveSourceConfigs(next)
                    onSourceConfigsChange(persistence.sourceConfigs())
                    DesktopModuleSource.reload()
                },
                onSaveSource = { saved ->
                    // Replaced by id, so any number of addons can be configured.
                    val without = sourceConfigs.filterNot {
                        it.id == saved.id ||
                            (saved.kind == DesktopSourceKind.CUSTOM_MODULE &&
                                it.kind == DesktopSourceKind.CUSTOM_MODULE)
                    }
                    persistence.saveSourceConfigs((without + saved).inSourceOrder())
                    onSourceConfigsChange(persistence.sourceConfigs())
                    // Whatever was held for this entry describes a server that may no
                    // longer be the one selected.
                    DesktopAddonSource.forget(saved.id)
                    DesktopModuleSource.reload()
                },
                onRemoveSource = { config ->
                    if (config.isUserAdded) {
                        persistence.saveSourceConfigs(sourceConfigs.filterNot { it.id == config.id })
                        onSourceConfigsChange(persistence.sourceConfigs())
                        DesktopAddonSource.forget(config.id)
                        DesktopModuleSource.reload()
                    }
                },
                onMoveSource = { config, delta ->
                    val next = moveUserSource(sourceConfigs, config.id, delta)
                    persistence.saveSourceConfigs(next)
                    onSourceConfigsChange(persistence.sourceConfigs())
                },
                onTestSource = { candidate ->
                    onSourceStatus(candidate.id, "Checking source…")
                    scope.launch {
                        val health = if (candidate.kind == DesktopSourceKind.ADDON) {
                            DesktopAddonSource.health(candidate)
                        } else {
                            DesktopModuleSource.health(candidate)
                        }
                        health.fold(
                            onSuccess = { onSourceStatus(candidate.id, it) },
                            onFailure = {
                                onSourceStatus(candidate.id, it.message ?: "Source unavailable")
                            },
                        )
                    }
                },
                onOpenIntegrations = { overlays.settingsPage = DesktopSettingsPage.INTEGRATIONS },
                onOpenLicenses = { overlays.settingsPage = DesktopSettingsPage.LICENSES },
            )
            DesktopSettingsPage.EQUALIZER -> DesktopEqualizerDialog(onDismiss = onBack)
            DesktopSettingsPage.AUDIO_OUTPUT -> DesktopAudioOutputDialog(onDismiss = onBack)
            DesktopSettingsPage.LISTEN_TOGETHER -> DesktopListenTogetherDialog(
                autoplayEnabled = settings.autoplay,
                onDismiss = onBack,
            )
            DesktopSettingsPage.LYRICS_SOURCES -> DesktopLyricsSourcesDialog(
                order = settings.lyricsOrder,
                enabled = settings.lyricsOn,
                prioritizeSyllables = settings.prioritizeSyllables,
                onReorder = {
                    settings.lyricsOrder = it
                    persistence.saveLyricsSourceOrder(it)
                },
                onToggle = { name ->
                    settings.lyricsOn = if (name in settings.lyricsOn) settings.lyricsOn - name else settings.lyricsOn + name
                    persistence.saveLyricsEnabledSources(settings.lyricsOn)
                },
                onPrioritizeSyllables = {
                    settings.prioritizeSyllables = it
                    persistence.saveBoolean(DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES, it)
                },
                onReset = {
                    settings.lyricsOrder = DesktopLyricsClient.sources.map { it.name }
                    settings.lyricsOn = settings.lyricsOrder.toSet()
                    settings.prioritizeSyllables = false
                    persistence.saveLyricsSourceOrder(settings.lyricsOrder)
                    persistence.saveLyricsEnabledSources(settings.lyricsOn)
                    persistence.saveBoolean(DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES, false)
                },
                onDismiss = onBack,
            )
            DesktopSettingsPage.TRANSLATION_LANGUAGE -> DesktopTranslationLanguageDialog(onDismiss = onBack)
            DesktopSettingsPage.SPOTIFY_CANVAS -> DesktopSpotifyCanvasDialog(
                onDismiss = onBack,
                onSaved = { settings.spotifyCanvasCookie = it },
            )
            DesktopSettingsPage.INTEGRATIONS -> DesktopIntegrationsDialog(
                song = song,
                onOpenLastfm = { overlays.lastfmLogin = true },
                onOpenListenBrainz = { overlays.listenBrainzToken = true },
                onOpenDiscordToken = { overlays.discordToken = true },
                onDismiss = onBack,
            )
            DesktopSettingsPage.LICENSES -> DesktopLicensesPage(onDismiss = onBack)
        }
        }
    }
}
