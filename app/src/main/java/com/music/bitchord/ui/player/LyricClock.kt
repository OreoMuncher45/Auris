package com.music.bitchord.ui.player

import kotlin.math.abs

/** Polling jitter must not rewind a word highlight or briefly reactivate the previous line. */
internal fun reconcileLyricPosition(displayedMs: Long, reportedMs: Long): Long =
    if (abs(displayedMs - reportedMs) <= 250L) maxOf(displayedMs, reportedMs)
    else reportedMs
