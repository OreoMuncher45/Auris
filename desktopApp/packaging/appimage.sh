#!/usr/bin/env bash
#
# Wraps the app image jpackage produced into an AppImage, the one Linux format
# Gradle cannot make itself.
#
# Usage: desktopApp/packaging/appimage.sh <app-image-dir> <out.AppImage> [appimagetool]
set -euo pipefail

APP_DIR_SRC=${1:?usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool]}
OUT=${2:?usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool]}
TOOL=${3:-appimagetool}

# The app image carries one launcher in bin/, named after the package. The name is read from
# the tree rather than assumed: the old images were called BitChord, the current ones Auris,
# and a script that still asked for "bin/BitChord" stopped the build with "no launcher".
APP_NAME=$(basename "$(cd "$APP_DIR_SRC" && pwd)")
LAUNCHER=$(find "$APP_DIR_SRC/bin" -maxdepth 1 -type f -perm -u+x | head -n 1)
[ -n "$LAUNCHER" ] || { echo "no launcher in $APP_DIR_SRC" >&2; exit 1; }
LAUNCHER_NAME=$(basename "$LAUNCHER")

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
APPDIR="$STAGE/$APP_NAME.AppDir"

mkdir -p "$APPDIR/usr"
cp -a "$APP_DIR_SRC/." "$APPDIR/usr/"

# The mount point differs every run, so nothing may be hard-coded.
cat > "$APPDIR/AppRun" <<APPRUN
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/usr/bin/$LAUNCHER_NAME" "\$@"
APPRUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/$APP_NAME.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$APP_NAME
GenericName=Music Player
Comment=Aesthetic YouTube Music client
Exec=$LAUNCHER_NAME
Icon=$APP_NAME
Categories=AudioVideo;Audio;Player;
Terminal=false
StartupWMClass=com-music-bitchord-desktop-MainKt
DESKTOP

# appimagetool reads the icon by the desktop entry; the desktop reads .DirIcon.
cp "$ROOT/desktopApp/packaging/icons/AppIcon.png" "$APPDIR/$APP_NAME.png"
cp "$ROOT/desktopApp/packaging/icons/AppIcon.png" "$APPDIR/.DirIcon"

mkdir -p "$(dirname "$OUT")"
# APPIMAGE_EXTRACT_AND_RUN rather than the flag of the same name: it is read by the AppImage
# runtime, so it covers a CI runner with no FUSE, and a natively built appimagetool — which has
# no such flag and would refuse to start — simply ignores it. appimagetool itself is an x86_64
# binary, so the architecture of the image it writes is x86_64 unless the caller says otherwise.
ARCH=${APPIMAGE_ARCH:-x86_64} APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$APPDIR" "$OUT"
echo "appimage: $OUT"
