# Settings catalogue

Every setting in Auris lives in exactly one category. This document is the
checklist of that: the category a setting is in, and the identifier the code
uses for it.

The source of truth is
[`SettingsCatalog`](../app/src/main/java/com/music/bitchord/ui/screens/SettingsCatalog.kt).
`SettingsCatalogTest` fails if a setting is listed twice, if a category is
empty, or if a setting's name disagrees with the category it is filed under.
Keep this document and that file in step when a setting is added, moved or
removed.

The identifier is `<category>.<name>`, so a setting carries the category it
belongs to in its own name. The screen groups its rows by category and the
category title is its group header, so the search field reaches every category
at once.

## The categories

| Category | Identifier prefix | What is in it |
|---|---|---|
| Account & social | `account` | Sign-in, integrations, Listen Together |
| Audio quality | `audio` | Sources, streaming ceilings, output precision, DSP |
| Playback | `playback` | How a track plays and what follows it |
| Downloads | `downloads` | What is kept offline, and how it is fetched |
| Now Playing | `now_playing` | The player itself: artwork, lyrics, colours, badges |
| Home & library | `home_library` | What Home and Library show and in what order |
| Podcasts | `podcasts` | Whether podcasts are part of the app at all |
| Appearance | `appearance` | Theme, shell, type, glass and motion |
| Performance | `performance` | Frame rate and the high-performance mode |
| Storage | `storage` | Caches, local files, non-music filtering |
| Your data | `data` | Replay, genres, export and import |
| Language | `language` | The app's language |
| Advanced | `advanced` | Diagnostics |
| Support | `support` | Donation rails |

## Every setting

### Account & social
- [x] `account.integrations` — Account & integrations
- [x] `account.listen_together` — Listen Together

### Audio quality
- [x] `audio.source` — Sources
- [x] `audio.offline_mode` — Offline mode
- [x] `audio.wifi` — On Wi-Fi
- [x] `audio.mobile_data` — On mobile data
- [x] `audio.output_precision` — Output precision
- [x] `audio.bit_perfect` — Bit-perfect
- [x] `audio.prefer_usb_dac` — Prefer USB DAC
- [x] `audio.loudness_normalization` — Loudness normalization
- [x] `audio.loudness_off_on_speaker` — Loudness off on speaker
- [x] `audio.seamless_quality_upgrades` — Seamless quality upgrades
- [x] `audio.auto_upgrade_local_storage` — Auto upgrade songs in local storage
- [x] `audio.equalizer` — Equalizer
- [x] `audio.jamesdsp` — JamesDSP

### Playback
- [x] `playback.prefer_music_only` — Prefer music only
- [x] `playback.crossfade` — Crossfade
- [x] `playback.automix` — Automix
- [x] `playback.automix_engine` — Automix engine
- [x] `playback.automix_performance` — Automix performance
- [x] `playback.skip_silence` — Skip silence
- [x] `playback.spatial_audio` — Spatial audio
- [x] `playback.simulated_atmos` — Simulated Dolby Atmos
- [x] `playback.smart_version_alignment` — Smart version alignment
- [x] `playback.play_next_on_swipe` — Play next on swipe
- [x] `playback.dont_repeat_songs` — Don't repeat songs
- [x] `playback.stop_music_on_close` — Stop music on close

### Downloads
- [x] `downloads.quality` — Download quality
- [x] `downloads.wifi_only` — Download on Wi-Fi only
- [x] `downloads.export_compatible` — Export compatible downloads
- [x] `downloads.concurrent` — Concurrent downloads

### Now Playing
- [x] `now_playing.colors` — Player colours (buttons, seekbar, text, lyrics)
- [x] `now_playing.text_glow` — Text glow
- [x] `now_playing.show_lyrics` — Lyric line
- [x] `now_playing.next_track_status` — Next track status
- [x] `now_playing.upgrade_badge` — Upgrade badge
- [x] `now_playing.lossless_badge` — Lossless badge
- [x] `now_playing.hires_badge` — Hi-res badge
- [x] `now_playing.transport_smoothness` — Transport smoothness
- [x] `now_playing.full_screen_cover_art` — Full screen cover art
- [x] `now_playing.legacy_mesh_gradient` — Legacy mesh gradient
- [x] `now_playing.animated_cover_art` — Animated cover art
- [x] `now_playing.animated_cover_cellular` — Play animated cover over cellular
- [x] `now_playing.spotify_canvas` — Integrate Spotify canvas
- [x] `now_playing.synced_lyrics` — Synced lyrics
- [x] `now_playing.blur_unfocused_lyrics` — Blur unfocused lyrics
- [x] `now_playing.lyrics_sources` — Lyrics sources
- [x] `now_playing.translation_language` — Translation language
- [x] `now_playing.hide_volume_bar` — Hide volume bar
- [x] `now_playing.hide_song_status` — Hide song status

### Home & library
- [x] `home_library.show_flow_on_home` — Show Flow on home
- [x] `home_library.shelf_order` — Home shelves
- [x] `home_library.show_replay` — Show Replay
- [x] `home_library.sort` — Show-all order
- [x] `home_library.blocked_artists` — Blocked artists
- [x] `home_library.block_ai_music` — Block AI music

### Podcasts
- [x] `podcasts.enabled` — Podcasts

### Appearance
- [x] `appearance.theme` — Theme
- [x] `appearance.app_ui` — App UI
- [x] `appearance.fonts` — Fonts
- [x] `appearance.script_fonts` — Script fonts
- [x] `appearance.liquid_glass` — Liquid glass
- [x] `appearance.liquid_glass_intensity` — Liquid glass intensity
- [x] `appearance.folding_nav_bar` — Folding nav bar
- [x] `appearance.reduce_animation` — Reduce animation
- [x] `appearance.reduce_dynamic_blur` — Reduce dynamic blur
- [x] `appearance.liquid_glass_haptics` — Liquid glass haptics
- [x] `appearance.hide_status_bar` — Hide status bar

### Performance
- [x] `performance.high_performance_mode` — High performance mode
- [x] `performance.refresh_rate` — Refresh rate

### Storage
- [x] `storage.show_cache_folder` — Show cache folder
- [x] `storage.song_cache_limit` — Song cache limit
- [x] `storage.clear_song_cache` — Clear song cache
- [x] `storage.clear_image_cache` — Clear image cache
- [x] `storage.local_music_folder` — Local music folder
- [x] `storage.use_all_audio_folders` — Use all audio folders
- [x] `storage.filter_non_music_audio` — Filter non-music audio

### Your data
- [x] `data.replay` — Replay
- [x] `data.work_out_genres` — Work out genres
- [x] `data.export` — Export data
- [x] `data.import` — Import data

### Language
- [x] `language.app_language` — App language

### Advanced
- [x] `advanced.nerd_stats` — Show nerd stats

### Support
- [x] `support.donate` — Support / donations

## What changed

- The **Customisation page was folded in**. Its colour, Home-order, Library
  and type settings are rows in Now Playing, Home & library and Appearance
  now; the page and its route are gone. Nothing that was on both screens
  remains on both.
- The standalone **Dolby Atmos** row was removed. It was a second switch for
  a capability (the Dolby decoder) the rest of the audio path already reports,
  and it read as a setting when it was a fact about the device. The simulated
  Atmos switch, which is a real choice, stays — `playback.simulated_atmos`.
