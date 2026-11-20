package com.music.bitchord.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follows a chain of continuation pages from [token], handing them to
 * [deliver] as they arrive, with the next request already on its way.
 *
 * A playlist's pages are a chain: each token is in the page before it, so
 * there is exactly one request worth making at any moment, and the floor for
 * the whole list is one round trip per page. The loop this replaces stayed
 * above that floor on every page. It waited a fixed head start before the
 * first request, and it only asked for page N+1 once Main had merged and
 * published page N. Here the request side runs on its own: it asks for the
 * next page the moment it has the token, and delivering is the only thing
 * that waits for the reader.
 *
 * - Still one request in flight at a time. This is the same pagination at
 *   the network's own pace, not a burst.
 * - [holdFirstMillis] holds back the first *delivery*, not the first request,
 *   so the first screen still draws before page two lands on it, and the
 *   head start no longer adds to the full list's time.
 * - Pages that land while [deliver] is still busy (a long frame, a fling)
 *   are handed over together on its next turn: one publish, not one each.
 * - [fetch] returning null (a failed page) ends the chain with what arrived.
 *   [nextToken] returning null ends it too; that is where a caller stops a
 *   feed that loops back on itself, or an endless mix after one page.
 * - [deliver] returning false (the page has closed) stops it, and the
 *   request in flight, if any, is cancelled.
 */
internal suspend fun <P : Any> followContinuations(
    token: String,
    fetch: suspend (String) -> P?,
    nextToken: (P) -> String?,
    holdFirstMillis: Long = 0,
    deliver: suspend (List<P>) -> Boolean,
): Unit = coroutineScope {
    val arrived = Channel<P>(Channel.UNLIMITED)
    val requests = launch {
        var next: String? = token
        while (next != null) {
            val page = fetch(next) ?: break
            arrived.send(page)
            next = nextToken(page)
        }
        arrived.close()
    }
    try {
        delay(holdFirstMillis)
        for (page in arrived) {
            val batch = mutableListOf(page)
            while (true) batch += arrived.tryReceive().getOrNull() ?: break
            if (!deliver(batch)) break
        }
    } finally {
        requests.cancel()
    }
}
