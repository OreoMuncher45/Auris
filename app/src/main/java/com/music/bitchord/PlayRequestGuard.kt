package com.music.bitchord

/**
 * [action], run only if no later play request has started by the time it is
 * called.
 *
 * [begin] claims a new request — it runs now, at the tap — and returns its
 * token; [current] reads the newest token when the songs arrive. A card's
 * Play or Shuffle may first wait on fetching the release's tracks, and a
 * slow fetch must not land after the listener has played something else (or
 * tapped another card) and take over the queue or flip the shuffle setting.
 */
internal inline fun <T> latestPlayRequestOnly(
    begin: () -> Int,
    crossinline current: () -> Int,
    crossinline action: (T) -> Unit,
): (T) -> Unit {
    val token = begin()
    return { value -> if (current() == token) action(value) }
}
