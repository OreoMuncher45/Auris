#!/usr/bin/env bash
#
# Perf & battery harness: drives one fixed scenario on a connected Android
# device and prints one summary line per metric, for pasting into a PR as the
# "before" or "after" number.
#
#   scripts/perf/measure.sh <scenario> <minutes>
#
# Scenarios:
#   screen-on-nowplaying  track playing, now-playing sheet open, screen kept on
#   screen-off-playing    track playing, screen off
#   idle-bg               track loaded then paused, app in background, screen off
#   downloading           a download you start by hand, screen off (see below)
#
# Environment:
#   PKG              package to measure (default com.dev.bitchord.benchmark, the
#                    R8 release build: ./gradlew :app:installDevBenchmark).
#                    Whatever you pick, measure before and after on the same
#                    build type: debug numbers are far higher than release ones.
#                    perf-build.init.gradle (next to this script) installs a
#                    debug build side by side as <applicationId>.perf.
#   ANDROID_SERIAL   which device, when more than one is attached (read by adb)
#   PERF_TRACK_URL   link to start playback from (default: a fixed YT Music track)
#   PERF_SETTLE_S    seconds to let the scenario settle before counters reset (20)
#   PERF_OUT         directory for raw dumps (default /tmp/auris-perf/<stamp>-<scenario>)
#   PERF_DOWNLOAD_STARTED=1  skip the "start a download, then press Enter" prompt
#
# The device must have no secure lock screen (or be unlocked) - the script
# wakes and dismisses the keyguard but can't enter a PIN.
#
# How it measures: the scenario is set up first and left to settle, then
# batterystats and gfxinfo are reset, the device is told it is on battery
# (`dumpsys battery unplug`, otherwise batterystats records nothing over USB),
# and the counters run for <minutes>. So the numbers are steady state: app
# start and the first network fetch are deliberately not in them.
#
# Exit status: 0 on a clean run, 1 on usage/device errors, 2 when the run
# finished but the scenario wasn't in the state it claims (e.g. not playing),
# so a number from a broken run can't be pasted by mistake without seeing it.

set -euo pipefail

PKG="${PKG:-com.dev.bitchord.benchmark}"
# A fixed, long-lived track so runs are comparable. Override to taste.
TRACK_URL="${PERF_TRACK_URL:-https://music.youtube.com/watch?v=dQw4w9WgXcQ}"
SETTLE_S="${PERF_SETTLE_S:-20}"
SCENARIOS="screen-on-nowplaying screen-off-playing idle-bg downloading"

usage() {
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0" >&2
    exit 1
}

die() { echo "measure.sh: $*" >&2; exit 1; }
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

[[ $# -eq 2 ]] || usage
SCENARIO="$1"
MINUTES="$2"
[[ " $SCENARIOS " == *" $SCENARIO "* ]] || die "unknown scenario '$SCENARIO' (one of: $SCENARIOS)"
[[ "$MINUTES" =~ ^[0-9]+$ && "$MINUTES" -ge 1 ]] || die "minutes must be a positive integer"

command -v adb >/dev/null || die "adb not on PATH"

# `adb shell` output carries \r on some devices; strip it everywhere.
sh_() { adb shell "$@" </dev/null | tr -d '\r'; }

state=$(adb get-state 2>/dev/null || true)
[[ "$state" == "device" ]] || die "no device attached (adb get-state: ${state:-none}); set ANDROID_SERIAL if several"

UID_=$(sh_ cmd package list packages -U "$PKG" | awk -v p="package:$PKG" '$1 == p { sub("uid:", "", $2); print $2 }')
[[ -n "$UID_" ]] || die "$PKG not installed (build it with ./gradlew :app:installDevBenchmark, or set PKG)"
VERSION=$(sh_ dumpsys package "$PKG" | awk -F= '/versionName=/ && v == "" { v = $2 } END { print v }')
DEVICE="$(sh_ getprop ro.product.model) / API $(sh_ getprop ro.build.version.sdk)"

OUT="${PERF_OUT:-/tmp/auris-perf/$(date +%Y%m%d-%H%M%S)-$SCENARIO}"
mkdir -p "$OUT"

# ---- Device state that has to be put back ----------------------------------

ORIG_TIMEOUT=$(sh_ settings get system screen_off_timeout)
restore() {
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    [[ -n "$ORIG_TIMEOUT" && "$ORIG_TIMEOUT" != "null" ]] &&
        adb shell settings put system screen_off_timeout "$ORIG_TIMEOUT" >/dev/null 2>&1 || true
}
trap restore EXIT
trap 'exit 130' INT TERM

# ---- Helpers ---------------------------------------------------------------

screen_on() {
    sh_ input keyevent KEYCODE_WAKEUP >/dev/null
    sh_ wm dismiss-keyguard >/dev/null 2>&1 || true
    sleep 1
    if sh_ dumpsys window | grep 'isKeyguardShowing=true' >/dev/null; then
        die "device is locked; remove the secure lock screen for perf runs, or unlock it and rerun"
    fi
}
screen_off() { sh_ input keyevent KEYCODE_SLEEP >/dev/null; }
interactive() { sh_ dumpsys power | grep 'mWakefulness=Awake' >/dev/null; }

# PlaybackState of $PKG's media session: PLAYING, PAUSED, ... or NONE.
playback_state() {
    sh_ dumpsys media_session | awk -v p="package=$PKG" '
        $1 == p { found = 1 }
        found && !st && /state=PlaybackState/ { match($0, /\{state=[A-Z_]+/); st = substr($0, RSTART + 7, RLENGTH - 7) }
        END { print (st == "" ? "NONE" : st) }'
}

wait_playing() {
    local i
    for i in $(seq 1 30); do
        [[ "$(playback_state)" == "PLAYING" ]] && return 0
        sleep 2
    done
    die "playback did not start within 60s (state: $(playback_state)); check the network and PERF_TRACK_URL"
}

start_playback() {
    # Fresh process each run: no state carried over from the previous scenario.
    sh_ am force-stop "$PKG"
    sleep 1
    # MusicLink plays the track as radio; bitchord.openPlayer opens the
    # now-playing sheet (PlayerDeepLink). Both read the same intent.
    sh_ am start -W -a android.intent.action.VIEW -d "$TRACK_URL" -p "$PKG" \
        --ez bitchord.openPlayer "${1:-false}" >/dev/null
    wait_playing
}

skin_temp() {
    sh_ dumpsys thermalservice | awk '
        /Current temperatures from HAL/ { hal = 1; next }
        hal && !t && /mName=SKIN/ { match($0, /mValue=[0-9.]+/); t = substr($0, RSTART + 7, RLENGTH - 7) }
        END { print t }'
}
thermal_status() { sh_ dumpsys thermalservice | awk -F': ' '/^Thermal Status:/ && s == "" { s = $2 } END { print s }'; }

# ---- Scenario setup --------------------------------------------------------

EXPECT_STATE=""
EXPECT_SCREEN=""
log "scenario=$SCENARIO minutes=$MINUTES pkg=$PKG ($VERSION) device=$DEVICE"
# On battery from here on: batterystats records nothing while it thinks USB
# is charging. Done before setup, not at reset time, because (un)plugging
# wakes the screen on devices with config_unplugTurnsOnScreen, which would
# silently turn a screen-off run into a screen-on one.
sh_ dumpsys battery unplug >/dev/null
case "$SCENARIO" in
    screen-on-nowplaying)
        # Keep the screen on for the whole run plus slack, without relying on
        # stay-awake-while-plugged (the device is about to think it's unplugged).
        sh_ settings put system screen_off_timeout $(( (MINUTES + 5) * 60 * 1000 ))
        screen_on
        start_playback true
        # The sheet is opened once there's a track; re-send in case the first
        # intent landed before the controller connected.
        sleep 3
        sh_ am start -a android.intent.action.MAIN -p "$PKG" --ez bitchord.openPlayer true >/dev/null || true
        EXPECT_STATE=PLAYING EXPECT_SCREEN=on
        ;;
    screen-off-playing)
        screen_on
        start_playback false
        screen_off
        EXPECT_STATE=PLAYING EXPECT_SCREEN=off
        ;;
    idle-bg)
        screen_on
        start_playback false
        sleep 5
        sh_ input keyevent KEYCODE_MEDIA_PAUSE >/dev/null
        sleep 2
        sh_ input keyevent KEYCODE_HOME >/dev/null
        screen_off
        EXPECT_STATE=PAUSED EXPECT_SCREEN=off
        ;;
    downloading)
        # Downloads can't be queued from outside the app on purpose
        # (DownloadService isn't exported), so this one needs a hand.
        screen_on
        sh_ monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        if [[ "${PERF_DOWNLOAD_STARTED:-}" != 1 ]]; then
            [[ -t 0 ]] || die "downloading needs a download started in the app; do that, then rerun with PERF_DOWNLOAD_STARTED=1"
            echo "Start downloading a playlist/album of at least ~20 tracks in the app, then press Enter." >&2
            read -r _
        fi
        screen_off
        EXPECT_SCREEN=off
        ;;
esac

log "settling ${SETTLE_S}s"
sleep "$SETTLE_S"
if [[ "$EXPECT_SCREEN" == off ]] && interactive; then screen_off; sleep 2; fi

# ---- Measure ---------------------------------------------------------------

sh_ dumpsys batterystats --reset >/dev/null
sh_ dumpsys gfxinfo "$PKG" reset >/dev/null
TH_START=$(thermal_status)
SKIN_START=$(skin_temp)
SKIN_MAX="$SKIN_START"
sh_ dumpsys thermalservice >"$OUT/thermal-start.txt"

log "measuring for ${MINUTES}m (raw dumps -> $OUT)"
END=$(( $(date +%s) + MINUTES * 60 ))
SCREEN_BAD=0   # 30s samples where the screen wasn't in the scenario's state
max_temp() { awk -v a="$1" -v b="$2" 'BEGIN { print (b + 0 > a + 0) ? b : a }'; }
while (( $(date +%s) < END )); do
    left=$(( END - $(date +%s) ))
    sleep $(( left < 30 ? left : 30 ))
    t=$(skin_temp)
    [[ -n "$t" ]] && SKIN_MAX=$(max_temp "$SKIN_MAX" "$t")
    [[ "$(interactive && echo on || echo off)" == "$EXPECT_SCREEN" ]] || SCREEN_BAD=$(( SCREEN_BAD + 1 ))
done

END_STATE=$(playback_state)
END_SCREEN=$(interactive && echo on || echo off)
sh_ dumpsys batterystats --checkin >"$OUT/batterystats-checkin.txt"
sh_ dumpsys batterystats "$PKG" >"$OUT/batterystats-$PKG.txt"
sh_ dumpsys gfxinfo "$PKG" >"$OUT/gfxinfo.txt"
sh_ dumpsys thermalservice >"$OUT/thermal-end.txt"
TH_END=$(thermal_status)
SKIN_END=$(skin_temp)
[[ -n "$SKIN_END" ]] && SKIN_MAX=$(max_temp "$SKIN_MAX" "$SKIN_END")
restore

# ---- Summarise -------------------------------------------------------------
#
# batterystats --checkin rows for the app are `9,<uid>,l,<kind>,...`:
#   cpu,<user_ms>,<sys_ms>,...
#   awl,<partial_wakelock_ms>,<bg_ms>          (aggregated across wakelocks)
#   wl,<name>,<full_ms>,f,...,<partial_ms>,p,... (per wakelock, for the top one)
#   wua,<alarm>,<count>                         (wakeup alarms)
#   jb,<job>,<total_ms>,<count>,...
#   nt,<mobile_rx>,<mobile_tx>,<wifi_rx>,<wifi_tx>,...  (bytes)
#   pwi,uid,<mAh>,...                           (estimated drain)

awk -F, -v uid="$UID_" -v min="$MINUTES" '
    $1 == 9 && $2 == uid && $3 == "l" {
        k = $4
        if (k == "cpu") { user += $5; sys += $6 }
        else if (k == "awl") { wl += $5 }
        else if (k == "wl") {
            for (i = 6; i <= NF; i++) if ($i == "p") { if ($(i - 1) > top_ms) { top_ms = $(i - 1); top = $5 }; break }
        }
        else if (k == "wua") { alarms += $6 }
        else if (k == "jb") { jobs += $7; job_ms += $6 }
        else if (k == "nt") { mrx += $5; mtx += $6; wrx += $7; wtx += $8 }
        else if (k == "pwi" && $5 == "uid") { mah += $6 }
    }
    END {
        printf "cpu_ms: %d (user %d + sys %d, %.0f/min)\n", user + sys, user, sys, (user + sys) / min
        printf "wakeup_alarms: %d\n", alarms
        printf "jobs: %d (%d ms)\n", jobs, job_ms
        printf "wakelock_ms: %d (top: %s %d ms)\n", wl, (top == "" ? "-" : top), top_ms
        printf "net_bytes: %d (rx %d, tx %d; wifi %d/%d, mobile %d/%d)\n", mrx + mtx + wrx + wtx, mrx + wrx, mtx + wtx, wrx, wtx, mrx, mtx
        printf "power_est_mah: %.2f\n", mah
    }' "$OUT/batterystats-checkin.txt" >"$OUT/summary.body"

FRAMES=$(awk -F': ' '/^Total frames rendered:/ { print $2; exit }' "$OUT/gfxinfo.txt")
JANKY=$(awk '/^Janky frames:/ { print $3, $4; exit }' "$OUT/gfxinfo.txt")

STATUS=0
WARN=""
if [[ -n "$EXPECT_STATE" && "$END_STATE" != "$EXPECT_STATE" ]]; then
    WARN+=" playback=$END_STATE(expected $EXPECT_STATE)"; STATUS=2
fi
if [[ "$END_SCREEN" != "$EXPECT_SCREEN" || "$SCREEN_BAD" -gt 0 ]]; then
    WARN+=" screen=$END_SCREEN(expected $EXPECT_SCREEN, wrong in $SCREEN_BAD samples)"; STATUS=2
fi

{
    echo "perf: $SCENARIO ${MINUTES}m | $PKG $VERSION | $DEVICE | $(date '+%Y-%m-%d %H:%M')"
    cat "$OUT/summary.body"
    echo "frames: ${FRAMES:-0} total, janky ${JANKY:-0}"
    echo "thermal: status $TH_START->$TH_END, skin ${SKIN_START:-?}->${SKIN_END:-?}C (max ${SKIN_MAX:-?})"
    echo "check: playback=$END_STATE screen=$END_SCREEN${WARN:+ WARN:$WARN}"
} | tee "$OUT/summary.txt"
rm -f "$OUT/summary.body"

exit "$STATUS"
