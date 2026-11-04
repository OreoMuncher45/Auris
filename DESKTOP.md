# Auris Desktop

Auris on the desktop is the Android app, converted: the same screens, the same
data layer, the same player, drawn by Compose Multiplatform and given the things
a window has and a phone does not.

The desktop application lives in `desktopApp/`. It is the phone's UI taken from
`:sharedUi` — Home, Explore, Library, Search, Radio, Podcasts and the player —
with a desktop shell around it: a sidebar, a strip of open tabs, a full keyboard,
media keys, a system tray and MPRIS.

Everything the window says is Auris. The package is `com.music.auris.desktop`,
the installer is `Auris`, the native libraries are `auris_analysis`,
`auris_window` and `auris_smtc`, the state lives under `~/.local/state/auris`,
and the MPRIS bus is `org.mpris.MediaPlayer2.auris`.

## What the desktop has that the phone does not

- **A sidebar** down the left: the pages the phone's bottom bar carries, plus
  the ones that were always a page of their own — History, Downloads, Local
  Music, Settings.
- **Tabs.** `Ctrl+T` opens one, `Ctrl+W` closes it, `Ctrl+Tab` walks through
  them and `Ctrl+1…9` goes straight to one. Every tab keeps its own road back,
  so a tab left on an album is still on that album when you come back to it.
- **The full keyboard.** Space plays and pauses, `Ctrl+←`/`Ctrl+→` change
  track, `Ctrl+↑`/`Ctrl+↓` move the volume, `Ctrl+F` and `Ctrl+L` go to the
  search, `Alt+1…9` jump to a page, `Ctrl+,` opens Settings, `Ctrl+/` lists
  every shortcut, `F11` fills the screen and `Ctrl+Q` quits.
- **Media keys**, the system tray, MPRIS on Linux and SMTC on Windows, and a
  window that remembers where it was.

## Running it

The desktop target uses Java 21. On Linux, run it with the JDK and the native
libraries in the shell:

On NixOS:
```bash
nix-shell -p jdk21 libglvnd glib gtk3 pango atk cairo cmake gdk-pixbuf libXtst libXxf86vm alsa-lib ffmpeg_6 --run '\
  export LD_LIBRARY_PATH="$(nix eval --raw nixpkgs#libglvnd.outPath)/lib:$(nix eval --raw nixpkgs#glib.out)/lib:$(nix eval --raw nixpkgs#gtk3.outPath)/lib:$(nix eval --raw nixpkgs#pango.out)/lib:$(nix eval --raw nixpkgs#atk.outPath)/lib:$(nix eval --raw nixpkgs#cairo.outPath)/lib:$(nix eval --raw nixpkgs#gdk-pixbuf.outPath)/lib:$(nix eval --raw nixpkgs#libXtst.outPath)/lib:$(nix eval --raw nixpkgs#libXxf86vm.outPath)/lib:$(nix eval --raw nixpkgs#alsa-lib.outPath)/lib:$(nix eval --raw nixpkgs#ffmpeg_6.lib)/lib:$HOME/.openjfx/cache/21.0.6+3/amd64:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; \
  bash ./gradlew :desktopApp:run
'
```

On Windows, use a Java 21 shell or install and select a Java 21 JDK before
running `gradlew.bat :desktopApp:run`.

## Installing a release

Every download on the [releases page](https://github.com/OreoMuncher45/Auris/releases)
carries its own Java runtime, the FFmpeg and ONNX natives, the Automix
analyser and its models. Nothing else has to be installed first — no JDK, no
codec pack.

| Platform | Download | Notes |
|---|---|---|
| Linux | `Auris-<version>-linux-x86_64.AppImage` | `chmod +x` it and run. No install, works on any distribution. |
| Linux | `Auris-<version>-linux-amd64.deb` | `sudo apt install ./Auris-*.deb` on Debian, Ubuntu and derivatives. |
| Linux | `Auris-<version>-linux-x86_64.rpm` | `sudo dnf install ./Auris-*.rpm` on Fedora, RHEL and openSUSE. |
| Windows | `Auris-<version>-windows-x64-setup.exe` | The ordinary installer. Installs for the current user, so it never asks for an administrator. |
| Windows | `Auris-<version>-windows-x64.msi` | The same thing for anyone who deploys by MSI. |
| Windows | `Auris-<version>-windows-x64-portable.zip` | Unzip anywhere and run `Auris.exe`. Writes nothing outside the folder. |

The Linux packages are built on Ubuntu 22.04 against its glibc, so they install
on that release and anything newer.

**Linux system libraries.** The packages carry their own Java runtime and codecs
but not the desktop's own graphics stack, and jpackage cannot derive that list —
so the deb and rpm declare only `xdg-utils`. Any machine running a desktop
session already has what is needed (GTK 3, X11 or XWayland, GL, ALSA); a minimal
or headless install does not, and will fail at startup rather than at install
time.

**Windows and the Visual C++ runtime.** The Automix analyser is linked against a
static C runtime, so on almost every machine there is nothing to install. If
Auris starts but Automix never analyses anything, install the
[Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://aka.ms/vs/17/release/vc_redist.x64.exe)
and restart it — that is the one dependency the bundled runtime cannot carry
itself, because Windows expects it to be a system component.

## Building the packages yourself

jpackage builds a package by driving the target platform's own tooling, so each
one has to be built on the platform it is for. This is what CI does; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

On Linux — `rpm`, `fakeroot` and `binutils` must be installed for the packages,
and `file` for the AppImage:

```bash
bash ./gradlew :desktopApp:packageDeb
bash ./gradlew :desktopApp:packageRpm

# The AppImage is wrapped around the app image jpackage produces.
bash ./gradlew :desktopApp:createDistributable
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/Auris \
  dist/Auris-linux-x86_64.AppImage
```

On Windows — [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) must
be on `PATH`, which is what jpackage builds both the `.exe` and the `.msi` with:

```bat
gradlew.bat :desktopApp:packageExe
gradlew.bat :desktopApp:packageMsi

REM The portable build is the app image, zipped.
gradlew.bat :desktopApp:createDistributable
```

### Build properties

| Property | What it does |
|---|---|
| `-Pauris.version=1.2.3` | The version stamped into the packages; a release build takes it from the tag. |
| `-Pauris.target=windows` | Build for Windows from a Linux machine, with a MinGW toolchain. |
| `-Pauris.arch=arm64` | The processor architecture. Defaults to the host's, so an ARM Linux machine builds an ARM app. |
| `-Pauris.requireNative=true` | Stop the build when a native library is missing, instead of shipping an app without it. |
