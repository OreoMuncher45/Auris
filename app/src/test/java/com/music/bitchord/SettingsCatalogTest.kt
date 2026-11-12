package com.music.bitchord

import com.music.bitchord.ui.screens.SettingsCatalog
import com.music.bitchord.ui.screens.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the settings taxonomy in [SettingsCatalog].
 *
 * The catalogue is the one place that says which category a setting lives in,
 * and these are the properties that make "every setting in exactly one
 * category" true rather than aspirational: no setting twice, no empty
 * category, no setting whose name disagrees with the category it is filed
 * under.
 */
class SettingsCatalogTest {

    @Test
    fun `no setting appears twice`() {
        val duplicates = SettingsCatalog.entries
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        assertEquals("these settings are listed more than once: $duplicates", emptySet<String>(), duplicates)
    }

    @Test
    fun `every category has at least one setting`() {
        val empty = SettingsCategory.entries.filter { SettingsCatalog.idsIn(it).isEmpty() }
        assertEquals("these categories hold nothing: $empty", emptyList<SettingsCategory>(), empty)
    }

    @Test
    fun `a setting's name agrees with its category`() {
        for (category in SettingsCategory.entries) {
            for (id in SettingsCatalog.idsIn(category)) {
                assertEquals(
                    "setting $id is filed under ${category.id} but is named otherwise",
                    category,
                    SettingsCatalog.categoryOf(id),
                )
            }
        }
    }

    @Test
    fun `every id is a category prefix and a name`() {
        for (id in SettingsCatalog.entries) {
            assertTrue("setting id is not namespaced: $id", id.contains('.'))
            assertFalse("setting id has an empty name: $id", id.endsWith('.'))
            assertFalse("setting id has an empty category: $id", id.startsWith('.'))
        }
    }

    @Test
    fun `an unknown id has no category`() {
        assertNull(SettingsCatalog.categoryOf("nonsense"))
        assertNull(SettingsCatalog.categoryOf(""))
        assertNull(SettingsCatalog.categoryOf("not_a_category.thing"))
        // The identifier of a real setting resolves.
        assertEquals(SettingsCategory.AUDIO, SettingsCatalog.categoryOf("audio.wifi"))
    }

    @Test
    fun `contains only knows the catalogue`() {
        assertTrue(SettingsCatalog.contains("playback.automix"))
        assertFalse(SettingsCatalog.contains("playback.automix.extra"))
        assertFalse(SettingsCatalog.contains("audio.not_a_setting"))
    }

    /**
     * The settings the re-categorisation exists to fix: each was on the
     * Settings screen and again on the Customisation page (or was removed for
     * being a second Dolby Atmos switch). Each must appear exactly once now.
     */
    @Test
    fun `formerly duplicated settings appear exactly once`() {
        val formerlyDuplicated = listOf(
            "appearance.theme",
            "appearance.app_ui",
            "appearance.reduce_animation",
            "appearance.reduce_dynamic_blur",
            "appearance.liquid_glass",
            "appearance.folding_nav_bar",
            "performance.high_performance_mode",
            "home_library.show_flow_on_home",
        )
        for (id in formerlyDuplicated) {
            assertEquals(
                "$id should be in the catalogue exactly once",
                1,
                SettingsCatalog.entries.count { it == id },
            )
        }
    }

    /** The simulated Atmos switch survives; the standalone Atmos row does not. */
    @Test
    fun `atmos is represented by the simulated switch alone`() {
        assertTrue(SettingsCatalog.contains("playback.simulated_atmos"))
        val dolbyRows = SettingsCatalog.entries.filter { it.contains("dolby_atmos") }
        assertEquals("a standalone Atmos row should have been removed: $dolbyRows", emptyList<String>(), dolbyRows)
    }

    /**
     * Every setting the app really shows. Kept as a list so that dropping one
     * by accident fails here rather than silently removing it from the app.
     */
    @Test
    fun `the known settings are all present`() {
        val required = listOf(
            "account.integrations", "account.listen_together",
            "audio.source", "audio.offline_mode", "audio.wifi", "audio.mobile_data",
            "audio.output_precision", "audio.bit_perfect", "audio.prefer_usb_dac",
            "audio.loudness_normalization", "audio.loudness_off_on_speaker",
            "audio.seamless_quality_upgrades", "audio.auto_upgrade_local_storage",
            "audio.equalizer", "audio.jamesdsp",
            "playback.prefer_music_only", "playback.crossfade", "playback.automix",
            "playback.automix_engine", "playback.automix_performance", "playback.skip_silence",
            "playback.spatial_audio", "playback.simulated_atmos", "playback.smart_version_alignment",
            "playback.play_next_on_swipe", "playback.dont_repeat_songs", "playback.stop_music_on_close",
            "downloads.quality", "downloads.wifi_only", "downloads.export_compatible", "downloads.concurrent",
            "now_playing.full_screen_cover_art", "now_playing.legacy_mesh_gradient",
            "now_playing.animated_cover_art", "now_playing.animated_cover_cellular",
            "now_playing.spotify_canvas", "now_playing.synced_lyrics",
            "now_playing.blur_unfocused_lyrics", "now_playing.lyrics_sources",
            "now_playing.translation_language", "now_playing.hide_volume_bar",
            "now_playing.hide_song_status", "now_playing.colors", "now_playing.text_glow",
            "now_playing.show_lyrics", "now_playing.next_track_status", "now_playing.upgrade_badge",
            "now_playing.lossless_badge", "now_playing.hires_badge", "now_playing.transport_smoothness",
            "home_library.show_flow_on_home", "home_library.shelf_order", "home_library.show_replay",
            "home_library.sort", "home_library.blocked_artists", "home_library.block_ai_music",
            "podcasts.enabled",
            "appearance.theme", "appearance.app_ui", "appearance.fonts", "appearance.script_fonts",
            "appearance.liquid_glass", "appearance.liquid_glass_intensity", "appearance.folding_nav_bar",
            "appearance.reduce_animation", "appearance.reduce_dynamic_blur",
            "appearance.liquid_glass_haptics", "appearance.hide_status_bar",
            "performance.high_performance_mode", "performance.refresh_rate",
            "storage.show_cache_folder", "storage.song_cache_limit", "storage.clear_song_cache",
            "storage.clear_image_cache", "storage.local_music_folder", "storage.use_all_audio_folders",
            "storage.filter_non_music_audio",
            "data.replay", "data.work_out_genres", "data.export", "data.import",
            "language.app_language", "advanced.nerd_stats", "advanced.replay_onboarding", "support.donate",
        )
        val missing = required.filterNot { SettingsCatalog.contains(it) }
        assertEquals("these settings are not in the catalogue: $missing", emptyList<String>(), missing)
        assertEquals(
            "the catalogue has settings the checklist does not know about",
            emptySet<String>(),
            SettingsCatalog.entries.toSet() - required.toSet(),
        )
    }
}
