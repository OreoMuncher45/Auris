package com.music.bitchord.data.model

/**
 * The feed with its quick-picks row guaranteed to be there, and at the front.
 *
 * YouTube does not always send one. Some accounts get a home without a
 * quick-picks row at all, some get it only on a later page, and its title is
 * written in the account's own language — so on a device with a language the
 * app cannot read, the row was there and went unnoticed, and the page had no
 * quick picks until it was refreshed into a shape that had one.
 *
 * So the row is taken from the feed when the feed has one, and built from
 * the feed's own rows when it does not. What stands in for it is the
 * account's own home offering, which is the best answer available, and it is
 * always an answer: the row is never missing from a Home page.
 *
 * The row keeps the title the feed gave it — the heading is the account's
 * language — and only the stand-in carries the app's own title.
 */
fun List<HomeShelf>.withQuickPicks(): List<HomeShelf> {
    // The feed's own row wins, and only one of them: a page that has both
    // the account's row and a stand-in would show two quick-picks rows, and
    // the second one would be the stand-in — the feed's answer buried under
    // its own copy.
    var kept = false
    val withoutDuplicates = filter { shelf ->
        if (!isQuickPicksShelfTitle(shelf.title)) return@filter true
        if (kept) return@filter false
        kept = true
        true
    }
    val picks = withoutDuplicates.firstOrNull { isQuickPicksShelfTitle(it.title) }
    // A row that came without songs is not a row that loaded: on some
    // accounts the feed sends the heading and nothing under it, and the
    // page shows a heading with an empty space under it until a refresh
    // brings the songs. The stand-in is built instead.
    if (picks != null && picks.items.isNotEmpty()) return withoutDuplicates
    val fallback = quickPicksFallback() ?: return withoutDuplicates
    return withoutDuplicates.filterNot { isQuickPicksShelfTitle(it.title) } + fallback
}

/**
 * The stand-in row: the songs of the feed's own rows, minus the ones that
 * are recents or history — the recommendations the feed is offering.
 *
 * The order the rows came in is kept, so the stand-in reads like the page it
 * stands in.
 */
private fun List<HomeShelf>.quickPicksFallback(): HomeShelf? {
    val rows = filterNot { shelf ->
        val title = shelf.title.lowercase()
        title.contains("recent") || title.contains("history") || title.contains("listen again")
    }
    val items = rows.flatMap { shelf -> shelf.items }
        .filter { !it.videoId.isNullOrBlank() }
        .distinctBy { it.videoId }
        .take(MAX_QUICK_PICKS_FALLBACK)
    if (items.isEmpty()) return null
    return HomeShelf(
        title = QUICK_PICKS_FALLBACK_TITLE,
        subtitle = "",
        items = items,
    )
}

/** How many songs the stand-in row carries. */
private const val MAX_QUICK_PICKS_FALLBACK = 20

/**
 * The shelves in the order the listener has set.
 *
 * The order is the feed's own: a shelf moved to the top is at the top of
 * the feed, not painted over the top of it. That is the whole difference
 * between the order being real and the order being a picture of one.
 *
 * A shelf the order does not name keeps the place the feed gave it, after
 * the ones it does name.
 */
fun orderHomeShelves(shelves: List<HomeShelf>, savedOrder: List<String>): List<HomeShelf> {
    val effective = if (savedOrder.isEmpty()) {
        defaultHomeShelfOrder(shelves)
    } else {
        // An order saved before the quick-picks row was in the list does not
        // name it, and the row would sink to the bottom of a page it belongs
        // at the top of.
        val known = savedOrder.filter { title -> shelves.any { it.title == title } }
        val picks = shelves.firstOrNull { isQuickPicksShelfTitle(it.title) }
        if (picks != null && known.none { isQuickPicksShelfTitle(it) }) {
            listOf(picks.title) + known
        } else {
            known
        }
    }
    val rank = effective.withIndex().associate { (index, title) -> title to index }
    return shelves.sortedBy { rank[it.title] ?: Int.MAX_VALUE }
}

/**
 * The order the shelves stand in when the listener has not set one.
 *
 * The quick-picks row is first, and the feed's own rows follow it in the
 * feed's order: it is the row the page is built around, it carries the
 * Flow and Replay cards, and it is the one row the feed may leave out.
 */
fun defaultHomeShelfOrder(shelves: List<HomeShelf>): List<String> {
    val picks = shelves.firstOrNull { isQuickPicksShelfTitle(it.title) }
    val rest = shelves.filterNot { isQuickPicksShelfTitle(it.title) }
    return buildList {
        if (picks != null) add(picks.title)
        addAll(rest.map { it.title })
    }
}
