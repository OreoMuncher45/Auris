# Auris Desktop: the conversion plan

The desktop is not a second implementation of Auris. It is the phone's app,
converted: the same screens, the same data layer, the same player, with the
things a window has and a phone does not.

This is what is done, what is left, and in what order — so the next pass does
not have to rediscover any of it.

## Where the desktop stands now

| Piece | Where it lives | Notes |
|---|---|---|
| The phone's UI | `:sharedUi` (`jvmSharedMain`) | Home, Explore, Library, Search, Radio, Podcasts, the player, the themes. The desktop draws exactly these. |
| The phone's data layer | `:shared` (`jvmSharedMain`) | InnerTubeX, the lyrics providers, the models, the settings enums. |
| The phone's wording | `app/src/main/res/values*` | Copied into the desktop at build time by `syncPhoneStrings`; the shared UI's own strings come through `syncSharedStrings`. |
| The desktop's shell | `:desktopApp` (`AurisApp.kt`, `AurisTabs.kt`) | The sidebar, the strip of open tabs, the keyboard, the window. |
| The desktop's platform layer | `:desktopApp` | The FFmpeg engine, the tray, MPRIS/SMTC, the window bridge, the cookie and PoToken plumbing. Media3 is Android-only, so this is the desktop's own player. |

What the desktop still keeps from the old build is the *platform* layer and a
handful of pages whose phone counterparts have not been moved into `:sharedUi`
yet: the artist and collection pages, Settings, History, Downloads and Local
Music. Those are the pages the conversion replaces.

## Why the phone's remaining screens are not on the desktop yet

Three things stand between them, and all three are the same problem: the
phone's screens ask the app around them for things only Android can answer.

1. **The app's state** — `AppSettings` (43 uses across the phone's UI),
   `Downloads`, `AuthStore`, `ListenTogether`, `AudioOutputStatus`,
   `SourceRegistry`, `LocalMediaRepository`, `ListeningStats`, `ArtistFacts`,
   `SpotifyLibrary`, `Backup`. All of it lives in `:app` and all of it is
   written against `Context`, `SharedPreferences`, `ConnectivityManager`,
   `AudioManager`, `MediaStore` and Media3.
2. **The phone's own resources** — `com.music.bitchord.R` and
   `androidx.compose.ui.res.stringResource`, which do not exist on the JVM.
3. **The phone's own components** — `app/ui/components/**` (42 files) is where
   the phone's rows, sheets and glass live. It is not in `:sharedUi`, so the
   phone's screens cannot be compiled without it.

## The order that works

**1. Move the phone's components into `:sharedUi`.**

`app/ui/components/**` moves to `sharedUi/src/jvmSharedMain/kotlin/com/music/
bitchord/ui/components/**` — same package, so no import changes anywhere.
Three things have to be dealt with on the way:

- `androidx.compose.ui.res.stringResource(R.string.x)` →
  `org.jetbrains.compose.resources.stringResource(Res.string.x)`, with the
  strings moved from `app/src/main/res/values*/strings.xml` into
  `sharedUi/src/commonMain/composeResources/values*/strings.xml`. The phone's
  Android resources stay as they are for the manifest, the notifications and
  the widget; the phone's *UI* reads the Compose copy.
- `androidx.compose.ui.platform.LocalContext` → a `LocalAppContext` in
  `:sharedUi`, with an `actual` per target: the Android one is the Android
  `Context`, the desktop one answers `openUri`, `clipboard` and `filesDir`
  through AWT.
- The two drawables the UI references (`R.drawable.*`) move to
  `composeResources/drawable`.

**2. Move the app's state into `:shared`, with the Android parts behind
`expect`/`actual`.**

`AppSettings` is the big one: it is 3,000 lines and almost all of it is a
setting and its default. What it needs from the platform is three things — a
key/value store, whether the connection is metered, and what the player is
doing — and each becomes a seam:

```kotlin
// shared/src/jvmSharedMain/.../settings/SettingsStore.kt
expect class SettingsStore

// androidMain: SharedPreferences. jvmMain: java.util.prefs.
```

The desktop then reads and writes the same settings the phone does, with the
same names, and a setting changed on one is a setting changed on the other
once the sync lands.

**3. Replace the desktop's player host with the phone's.**

`AndroidPlayerHost` (Media3) becomes `expect`/actual: the phone answers with
`MediaController`, the desktop with the FFmpeg engine. Everything the shared
player asks of its host — `PlayerHost` — is already platform-neutral.

**4. Delete the desktop's own pages.**

Once the phone's `DetailScreen`, `SettingsSheet`, `HistoryScreen`,
`LocalMusicScreen`, `SourcesScreen` and `EqualizerScreen` are in `:sharedUi`
and compile for the JVM, the desktop's copies go with them: `AurisArtistPage`,
`AurisCollectionPage`, `AurisSettingsScreen`, `AurisHistoryPage`,
`AurisDownloadsPage`, `AurisLocalMusicPage`, `AurisAccountUi`,
`AurisEqualizerUi`, `AurisIntegrationsUi`, `AurisLyricsSourcesUi`,
`AurisReplayUi`, `AurisSongMenuUi`, `AurisListenTogetherUi` and
`AurisDownloadManagerUi`. What is left of `:desktopApp` is the shell, the
platform layer and the packaging.

**5. The playback service.**

`PlaybackService` (10,600 lines of Media3) stays on the phone. The desktop's
FFmpeg engine is the desktop's answer to the same question, and the queue,
the crossfade and the Automix handoff are already shared through `:shared`'s
`QueueTimeline` and `AutomixEngineV2`.

## Rules for the conversion

- **One copy of a thing.** If the phone and the desktop both have it, it lives
  in `:shared` or `:sharedUi`, under the package it already has, so no import
  changes and no drift.
- **The phone's package does not move.** `com.music.bitchord` is the installed
  application id on Android, and changing it would break every update already
  on a phone. The *branding* moves — Auris everywhere a person can read it —
  but the id stays until the id itself is deliberately changed.
- **Nothing on the phone breaks.** The Android build is not verifiable from a
  desktop-only machine, so every step in this plan has to keep the phone's
  sources compiling as they are. That is why the moves keep their packages and
  why the resources are *copied* rather than relocated.
