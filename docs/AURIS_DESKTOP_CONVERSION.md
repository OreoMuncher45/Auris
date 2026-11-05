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

### What that gap costs, measured

An audit of the three pages that are still duplicated — Settings, the active
download manager, and Local Music/Downloads — found:

- **31 of the phone's 82 Settings rows** have a desktop row today. 6 more have
  something related but not the same row. **45 have none**: themes, fonts,
  liquid glass, blocked artists, offline mode, loudness, bit-perfect, JamesDSP,
  concurrent downloads, the custom home/library/now-playing screens, and the
  rest.
- The phone's Settings needs three seams before it can move: an editable
  settings host, a platform host (pickers, intents, locale, refresh rate), and
  the resource conversion from `R.string` to Compose Resources.
- The two download queues do not agree: the phone keeps a finished row until
  it has been seen, and remembers where the song came from; the desktop drops
  a finished row at once and keeps neither. One of them has to change before
  there can be one queue.
- Local Music and Downloads need `LocalMusicSort` and `DownloadedCollection`
  moved into `:shared`, and a preferences host, before the phone's screen can
  move. The desktop has no equivalent of the phone's downloaded-album
  collections, and no bulk delete or WebDAV upload.

So the desktop is the old app with the phone's screens inside it, not yet the
phone's app. The Windows release that the desktop workflow builds should be
read as exactly that.

## Windows: the app itself, through WSA

There is a second Windows path, and it is not the desktop at all: the phone's
APK, running on the Windows Subsystem for Android.

`wsaLauncher/` holds a small C# program that carries the APK and an `adb`
client inside one `Auris.exe`. It starts WSA if it is asleep, finds WSA's adb
bridge (WSA picks its own port, so the launcher looks for it rather than
assuming one), installs the app with `adb install -r -g -d`, starts it through
`WsaClient.exe /launch wsa://<package>`, and leaves a Start-menu shortcut
pointing at WSA.

`.github/workflows/wsa-draft-release.yml` builds it: the APK, then the
executable, then a **draft** release with the executable and the bare APK in
it. Nothing is published — `draft: true`, `prerelease: true`,
`make_latest: false`.

What it needs on the machine: WSA installed, and **WSA → Settings → Developer
→ ADB bridge** on. WSA-Pacman installs WSA and can install the APK itself, for
the people who would rather not run anything.

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

## Platform notes

**The WebView, and ARM64 Linux.** The PoToken minter needs a real browser
engine, so the desktop runs BotGuard in JavaFX's WebKit. JavaFX ships its
natives per platform *and* per processor, so `desktopApp/build.gradle.kts`
picks the classifier from the target: `win` on Windows, `linux` on x86_64 and
`linux-aarch64` on ARM64. OpenJFX only started publishing the Linux ARM64
builds with 21.0.11; before that the `linux` jar carried x86_64 libraries
only, so an ARM64 build asked JavaFX to load them and died with "can't load
AMD 64 .so on a AARCH64 platform" — the app ran, but with no PoTokens at all.

Those ARM64 natives are built against glibc 2.34, so an ARM64 Linux host
needs Ubuntu 22.04 or newer for the WebView. The rest of the app — Compose,
Skiko, the player, the downloads — does not: on an older glibc the app runs
normally and only the PoToken minter is out of reach.

**Downloads start quietly.** Starting a download on the desktop says nothing:
no toast, no notice, no sound. The download manager is where a download
appears, as a row reading "Downloading · 42%", and that is the only place it
shows up. The phone's "download started" notice is not carried over.

**The desktop releases as a draft.** `.github/workflows/desktop-draft-release.yml`
builds the desktop's own packages — Linux AppImage, deb and rpm, Windows
installer, MSI and portable zip — and writes them to a *draft* release. It
never publishes: `draft: true`, `prerelease: true`, `make_latest: false`. The
phone's `release.yml` is a different workflow for a different lineage and is
left alone.

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
