#!/usr/bin/env bash
#
# Per-thread CPU of the app over a few seconds: which threads are burning the
# CPU, by name. The tight loop for finding *where* a cost lives; measure.sh is
# the number that goes in the PR.
#
#   scripts/perf/threads.sh [seconds] [top-n]     (defaults 10, 15)
#
# PKG as in measure.sh. Sets nothing up: put the app in the state you care
# about first (e.g. playing with the screen off). Prints the process total as
# a share of one core, then the top threads by CPU over the window.

set -euo pipefail

PKG="${PKG:-com.dev.bitchord.benchmark}"
SECS="${1:-10}"
TOP="${2:-15}"

sh_() { adb shell "$@" </dev/null | tr -d '\r'; }

pid=$(sh_ pidof "$PKG" || true)
[[ -n "$pid" ]] || { echo "threads.sh: $PKG is not running" >&2; exit 1; }
hz=$(sh_ getconf CLK_TCK 2>/dev/null || echo 100)

# One line per thread: tid, utime+stime (ticks), name. The comm field is in
# parentheses and may contain spaces, so split on the last ')'.
snap() {
    sh_ "for f in /proc/$pid/task/*/stat; do cat \$f 2>/dev/null; echo; done" | awk '
        NF {
            l = index($0, "("); r = 0
            for (i = length($0); i > 0; i--) if (substr($0, i, 1) == ")") { r = i; break }
            name = substr($0, l + 1, r - l - 1); gsub(/ /, "_", name)
            n = split(substr($0, r + 2), f, " ")
            print $1, f[12] + f[13], name
        }'
}

a=$(snap)
sleep "$SECS"
b=$(snap)

awk -v s="$SECS" -v hz="$hz" -v top="$TOP" '
    NR == FNR { t0[$1] = $2; next }
    { d = $2 - ($1 in t0 ? t0[$1] : 0); tot += d; if (d > 0) printf "%d %s %s\n", d, $1, $3 }
    END { printf "TOTAL %d\n", tot > "/dev/stderr" }
' <(echo "$a") <(echo "$b") 2>"${TMPDIR:-/tmp}/threads.total.$$" | sort -rn | head -n "$TOP" |
    awk -v s="$SECS" -v hz="$hz" '{ printf "%6.1f%%  %-7s %s\n", 100 * $1 / hz / s, $2, $3 }' >"${TMPDIR:-/tmp}/threads.rows.$$"

tot=$(awk '{ print $2 }' "${TMPDIR:-/tmp}/threads.total.$$")
awk -v t="$tot" -v s="$SECS" -v hz="$hz" 'BEGIN { printf "process: %.1f%% of one core over %ds\n", 100 * t / hz / s, s }'
cat "${TMPDIR:-/tmp}/threads.rows.$$"
rm -f "${TMPDIR:-/tmp}/threads.total.$$" "${TMPDIR:-/tmp}/threads.rows.$$"
