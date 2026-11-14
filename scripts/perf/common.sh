# Shared helpers for the perf scripts. Sourced, not run:
#
#   source "$(dirname "$0")/common.sh"

# Package to measure: the R8 benchmark build unless PKG says otherwise.
PKG="${PKG:-com.dev.bitchord.benchmark}"

# `adb shell` with \r stripped (some devices add it) and stdin detached, so a
# call inside a `while read` loop can't swallow the loop's input.
adb_sh() { adb shell "$@" </dev/null | tr -d '\r'; }

# PID of $PKG, or exit 1 with a message naming the calling script.
app_pid_or_die() {
    local pid
    pid=$(adb_sh pidof "$PKG" || true)
    [[ -n "$pid" ]] || { echo "$(basename "$0"): $PKG is not running" >&2; exit 1; }
    echo "$pid"
}

# Kernel clock ticks per second, for the utime/stime fields of /proc/*/stat.
clock_ticks_per_s() { adb_sh getconf CLK_TCK 2>/dev/null || echo 100; }
