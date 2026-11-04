# Auris for Windows (Android)

The Android app, on a PC, in a window that looks like a Windows app.

This folder is the whole thing. Copy it to any Windows 10 or 11 PC and run
`Start-Auris.cmd`. It asks for administrator rights once, puts whatever is
missing in place, and opens Auris.

There is no installer, no service, no registry keys of its own and nothing
left behind beyond the Android app itself. Delete the folder and the launcher
is gone; `Uninstall-Auris.cmd` takes Auris off the PC.

## What it does

1. Turns on the two Windows features the Android subsystem needs
   (`Microsoft-Windows-Subsystem-for-Android` and `VirtualMachinePlatform`).
   If either was off it asks Windows to restart, and stops there.
2. Installs the Windows Subsystem for Android if it is not already there —
   the package and its system image, taken from Microsoft's own releases
   (or from files you drop in this folder; see *Settings*).
3. Installs Auris: the `Auris.apk` in this folder if there is one, otherwise
   the APK named in the settings, otherwise the newest release on GitHub.
4. Opens Auris in a window, and takes the frame off that window.

## The border

Windows Subsystem for Android puts every Android app in a real Win32 window,
and that window arrives with a caption bar and a visible outline. The app
draws its own chrome inside it, so the frame is in the way of nothing.

`WsaFrame.cs` is compiled on the fly at launch and takes it off: the caption
goes, the outline is made invisible, and the window keeps its resize border,
so it still resizes from its edges. Windows 11's rounded corners are asked
for as well, which is what a native app's window looks like.

Turn it off with `StripWindowFrame = $false` in `auris.config.psd1`.

The Android system bars (the status bar and the navigation bar) are the app's
own: Auris hides them when it finds itself running inside the subsystem, so
what is left is the app's UI and nothing else.

## Settings

`auris.config.psd1` is optional. Every line in it can be left alone:

| Setting | What it does |
| --- | --- |
| `ApkUrl` | Where to get Auris when there is no `Auris.apk` in this folder. Empty means "the newest GitHub release". |
| `WsaPackageUrl` | The subsystem package. Empty means "Microsoft's newest release". |
| `WsaSystemImageUrl` | The subsystem's system image. Empty means "Microsoft's newest release". |
| `WindowTitle` | The name the subsystem gives the window; the border stripper looks for it. |
| `StripWindowFrame` | ` $false ` leaves the window's frame as Windows drew it. |

## Checking the folder

`Start-Auris.cmd -SelfTest` (or:

```
powershell -ExecutionPolicy Bypass -File Auris-Wsa.ps1 -SelfTest
```

) prints what it finds — the settings, whether the border stripper compiles,
whether an APK is there, and whether the subsystem is already on the PC —
without changing anything.

## Removing it

`Uninstall-Auris.cmd` removes Auris from the PC. The subsystem itself stays,
because other Android apps may be using it.

## What it cannot do

- **The subsystem is finished.** Microsoft ended support for it on 5 March
  2025. The package and system image still install and run on Windows 11 x64,
  and your own app is unaffected by that (the app is your APK, not the
  subsystem's), but the subsystem gets no further Android or driver updates.
- **No ARM64 Windows.** The subsystem is x64 only.
- **It needs virtualization.** Every PC with the Hyper-V-era requirements can
  do it; a machine with it switched off in the firmware cannot.
- **Audio crosses the virtual machine's boundary.** That is the one that can
  genuinely hurt a music player: seeking can take a moment, and the output
  device list is the virtual machine's rather than the PC's.
- **No Windows integration beyond the window.** There is no tray icon, no
  media-key handling, no file associations and no jump list. The Android
  app's own notifications and media-session behaviour work as they do on a
  phone.
- **The driver's job.** On some machines the subsystem needs Microsoft's
  signed driver for the Android kernel; on most, it does not. If the
  subsystem refuses to start, that is the first thing to look at.

## The other build

The repository also builds a native Windows app — real window, Mica and
Acrylic, the PC's own audio devices, tray, media keys, file associations. It
is a port of this same Android app, with the phone's own screens compiled
for the desktop, and it is the build that will feel like a first-class
Windows app in the end.

This folder is the short road: the Android app, unchanged, on the PC today.
