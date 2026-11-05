# Auris on Windows

Auris is an Android app, so on Windows it runs through the **Windows Subsystem for Android**.
This folder holds the launcher that makes that one click.

`Auris.exe` is one file. Inside it are:

- the phone's APK, built by `:app:assembleDevRelease`,
- Google's `adb.exe`, which is what talks to WSA's bridge,
- the Auris icon.

Run it and it:

1. checks that WSA is installed and starts it if it is asleep,
2. finds WSA's adb bridge — WSA picks its own port, so the launcher looks for it rather than
   assuming one,
3. installs the APK (`adb install -r -g -d`, falling back to a clean install when an older copy
   signed with another key is in the way),
4. starts the app through `WsaClient.exe /launch wsa://<package>`, the same way WSA's own
   Start-menu entry does,
5. puts an Auris shortcut in the Start menu, pointing at WSA and carrying the icon WSA wrote
   for the installed package.

## What it needs on the machine

- Windows 11 with WSA installed. If WSA is missing, the launcher says so and points at
  [WSA-Pacman](https://github.com/alesimula/wsa_pacman), which installs it.
- WSA's adb bridge on: **WSA → Settings → Developer → ADB bridge**. The launcher finds the port
  the Settings page shows; it never has to be typed in.

## Building it

The workflow does the whole thing:

```
.github/workflows/wsa-draft-release.yml
```

- `apk` — builds the phone's app (`:app:assembleDevRelease`), signed with the fork's key.
- `launcher` — drops the APK and `adb.exe` into `Resources/`, then
  `dotnet publish wsaLauncher/AurisWsaLauncher.csproj -c Release -o out`, which produces the
  single-file `Auris.exe`.
- `draft` — puts the executable and the bare APK in a **draft** release. It never publishes:
  `draft: true`, `prerelease: true`, `make_latest: false`.

## Notes

- The APK is the `dev` flavor, so its package is `com.dev.bitchord` while the code inside is
  the phone's `com.music.bitchord` code. The launcher looks for both.
- Everything the launcher does is logged to `%LOCALAPPDATA%\Auris\launcher.log`.
