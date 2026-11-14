#!/usr/bin/env bash
#
# Quick probe for the screen the app is on *right now*: frames rendered per
# second and CPU used, over a few seconds. The tight loop for iterating on a
# fix; measure.sh is the number that goes in the PR.
#
#   scripts/perf/probe.sh [seconds]        (default 10)
#
# PKG as in measure.sh. Sets nothing up: open the screen you care about first.
# A screen that only shows a moving progress bar should be in single digits of
# fps; one that sits near the display refresh rate is redrawing every frame.

set -euo pipefail

source "$(dirname "$0")/common.sh"
SECS="${1:-10}"

pid=$(app_pid_or_die)

# utime + stime of the whole process, in clock ticks (fields 14 and 15; the
# comm field has no spaces for an app process).
ticks() { adb_sh cat "/proc/$pid/stat" | awk '{ print $14 + $15 }'; }
hz=$(clock_ticks_per_s)

adb_sh dumpsys gfxinfo "$PKG" reset >/dev/null
t0=$(ticks)
sleep "$SECS"
t1=$(ticks)
frames=$(adb_sh dumpsys gfxinfo "$PKG" | awk -F': ' '/^Total frames rendered:/ && f == "" { f = $2 } END { print f + 0 }')

awk -v f="$frames" -v s="$SECS" -v d="$(( t1 - t0 ))" -v hz="$hz" 'BEGIN {
    printf "fps: %.1f (%d frames in %ds)\ncpu: %.0f%% of one core\n", f / s, f, s, 100 * d / hz / s
}'
