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
