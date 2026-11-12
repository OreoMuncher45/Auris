package com.music.bitchord.ui.screens

import androidx.annotation.StringRes
import com.music.bitchord.R

/**
 * The categories the settings are sorted into, and the one place that says
 * which setting belongs to which.
 *
 * Every setting in the app lives in exactly one category. That is the whole
 * point of this file: a setting that appears in two places is a setting
 * somebody will change in one of them and not find in the other, and a
 * setting that appears in none of them is a setting nobody can find at all.
 *
 * The identifier of a setting is `"<category>.<name>"`, so the category a
 * setting belongs to is part of its name rather than a separate fact about it
 * — [categoryOf] reads it back out, and [idsIn] lists what is in each
 * category.
 *
 * The catalogue is deliberately data rather than code: it can be checked by a
 * test, and it can be listed in a document, both of which are in
 * `docs/SETTINGS_CATALOG.md`.
 */
enum class SettingsCategory(
    /** The prefix every setting identifier in this category carries. */
    val id: String,
    @StringRes val titleRes: Int,
) {
    ACCOUNT("account", R.string.settings_category_account),
    AUDIO("audio", R.string.settings_category_audio),
    PLAYBACK("playback", R.string.settings_category_playback),
    DOWNLOADS("downloads", R.string.settings_category_downloads),
    NOW_PLAYING("now_playing", R.string.settings_category_now_playing),
    HOME_LIBRARY("home_library", R.string.settings_category_home_library),
    PODCASTS("podcasts", R.string.settings_category_podcasts),
    APPEARANCE("appearance", R.string.settings_category_appearance),
    PERFORMANCE("performance", R.string.settings_category_performance),
    STORAGE("storage", R.string.settings_category_storage),
    DATA("data", R.string.settings_category_data),
    LANGUAGE("language", R.string.settings_category_language),
    ADVANCED("advanced", R.string.settings_category_advanced),
    SUPPORT("support", R.string.settings_category_support),
    ;

    companion object {
        /** The category with this identifier, or null if there is none. */
        fun fromId(id: String): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The catalogue of every setting in the app.
 *
 * [entries] is the whole of it: one line per setting, saying which category
 * it lives in. Nothing else in the app decides where a setting goes.
 */
object SettingsCatalog {
    /**
     * Every setting, grouped by the category it belongs to.
     *
     * The order inside a category is the order the rows are shown in, so the
     * catalogue doubles as the shape of the settings screen.
     */
    private val byCategory: Map<SettingsCategory, List<String>> = mapOf(
        SettingsCategory.ACCOUNT to listOf(
            "account.integrations",
            "account.listen_together",
        ),
        SettingsCategory.AUDIO to listOf(
            "audio.source",
            "audio.offline_mode",
            "audio.wifi",
            "audio.mobile_data",
            "audio.output_precision",
            "audio.bit_perfect",
            "audio.prefer_usb_dac",
            "audio.loudness_normalization",
            "audio.loudness_off_on_speaker",
            "audio.seamless_quality_upgrades",
            "audio.auto_upgrade_local_storage",
            "audio.equalizer",
            "audio.jamesdsp",
        ),
        SettingsCategory.PLAYBACK to listOf(
            "playback.prefer_music_only",
            "playback.crossfade",
            "playback.automix",
            "playback.automix_engine",
            "playback.automix_performance",
            "playback.skip_silence",
            "playback.spatial_audio",
            "playback.simulated_atmos",
            "playback.smart_version_alignment",
            "playback.play_next_on_swipe",
            "playback.dont_repeat_songs",
            "playback.stop_music_on_close",
        ),
        SettingsCategory.DOWNLOADS to listOf(
            "downloads.quality",
            "downloads.wifi_only",
            "downloads.export_compatible",
            "downloads.concurrent",
        ),
        SettingsCategory.NOW_PLAYING to listOf(
            "now_playing.full_screen_cover_art",
            "now_playing.legacy_mesh_gradient",
            "now_playing.animated_cover_art",
            "now_playing.animated_cover_cellular",
            "now_playing.spotify_canvas",
            "now_playing.synced_lyrics",
            "now_playing.blur_unfocused_lyrics",
            "now_playing.lyrics_sources",
            "now_playing.translation_language",
            "now_playing.hide_volume_bar",
            "now_playing.hide_song_status",
            "now_playing.colors",
            "now_playing.text_glow",
            "now_playing.show_lyrics",
            "now_playing.next_track_status",
            "now_playing.upgrade_badge",
            "now_playing.lossless_badge",
            "now_playing.hires_badge",
            "now_playing.transport_smoothness",
        ),
        SettingsCategory.HOME_LIBRARY to listOf(
            "home_library.show_flow_on_home",
            "home_library.shelf_order",
            "home_library.show_replay",
            "home_library.sort",
            "home_library.blocked_artists",
            "home_library.block_ai_music",
        ),
        SettingsCategory.PODCASTS to listOf(
            "podcasts.enabled",
        ),
        SettingsCategory.APPEARANCE to listOf(
            "appearance.theme",
            "appearance.app_ui",
            "appearance.fonts",
            "appearance.script_fonts",
            "appearance.liquid_glass",
            "appearance.liquid_glass_intensity",
            "appearance.folding_nav_bar",
            "appearance.reduce_animation",
            "appearance.reduce_dynamic_blur",
            "appearance.liquid_glass_haptics",
            "appearance.hide_status_bar",
        ),
        SettingsCategory.PERFORMANCE to listOf(
            "performance.high_performance_mode",
            "performance.refresh_rate",
        ),
        SettingsCategory.STORAGE to listOf(
            "storage.show_cache_folder",
            "storage.song_cache_limit",
            "storage.clear_song_cache",
            "storage.clear_image_cache",
            "storage.local_music_folder",
            "storage.use_all_audio_folders",
            "storage.filter_non_music_audio",
        ),
        SettingsCategory.DATA to listOf(
            "data.replay",
            "data.work_out_genres",
            "data.export",
            "data.import",
        ),
        SettingsCategory.LANGUAGE to listOf(
            "language.app_language",
        ),
        SettingsCategory.ADVANCED to listOf(
            "advanced.nerd_stats",
            "advanced.replay_onboarding",
        ),
        SettingsCategory.SUPPORT to listOf(
            "support.donate",
        ),
    )

    /** Every setting identifier in the app, category by category. */
    val entries: List<String> = SettingsCategory.entries.flatMap { byCategory[it].orEmpty() }

    /** The settings in one category, in the order they are shown. */
    fun idsIn(category: SettingsCategory): List<String> = byCategory[category].orEmpty()

    /**
     * The category a setting belongs to, read out of its identifier.
     *
     * Null for an identifier that is not in the catalogue — which is a
     * programming mistake, and the caller is expected to have checked.
     */
    fun categoryOf(id: String): SettingsCategory? {
        val prefix = id.substringBefore('.', missingDelimiterValue = "")
        return SettingsCategory.fromId(prefix)
    }

    /** Whether this setting is in the catalogue at all. */
    fun contains(id: String): Boolean = entries.contains(id)
}
