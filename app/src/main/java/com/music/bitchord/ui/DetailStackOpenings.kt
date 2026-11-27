package com.music.bitchord.ui

import com.music.bitchord.data.model.DetailPage

/**
 * [this] with [transform] applied to the one page that is [browseId] at
 * [opening], and every other page left alone.
 *
 * Writes that land after a suspension (a continuation, a first response)
 * go through this rather than by browse id: the same page closed and opened
 * again is another opening, and an answer for the earlier one must not reach
 * it — nor an older copy further down the stack.
 */
internal inline fun List<DetailPage>.updateOpening(
    browseId: String,
    opening: Long,
    transform: (DetailPage) -> DetailPage,
): List<DetailPage> = map { if (it.browseId == browseId && it.opening == opening) transform(it) else it }

/**
 * The page on screen for [browseId]: its latest opening on the stack. An
 * older copy further down shows the listing as it was when it opened, so
 * anything that reads the page's rows to decide something now (is this song
 * already on the playlist?) reads this one.
 */
internal fun List<DetailPage>.latestOpening(browseId: String): DetailPage? =
    lastOrNull { it.browseId == browseId }

