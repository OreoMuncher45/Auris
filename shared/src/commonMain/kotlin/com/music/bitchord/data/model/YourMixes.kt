package com.music.bitchord.data.model

/**
 * A "Mixed for you" card and its English title. The English title says what
 * kind of mix it is whatever language [item] is shown in; see
 * [com.music.bitchord.data.YtMusicRepository.mixedForYouKeyed]. Null when the
 * English read had no such card: its shown title may be in another language,
 * and sorting that by the English patterns would misfile it.
 */
data class KeyedMix(val item: ShelfItem, val englishTitle: String?)

/**
 * The cards of Radio's "Your Mixes" carousel, out of the "Mixed for you" page.
 *
 * The page lists every mix in one run. Only the listener's own mixes and the
 * named ones go in the carousel; the mood mixes ("Chill Mix 2", "Feel Good
 * Supermix") stay out, because Radio's Stations for You already covers moods.
 *
 * Sorted by each card's English title, which names the kind of mix in every
 * language; the cards keep their own titles to show.
 *
 * - The listener's own come first, in the page's order: the numbered My Mixes,
 *   and the ones YouTube titles by what they sound like ("Modern Punjabi
 *   Beats") rather than by number.
 * - The named mixes ([NAMED_MIXES]) follow, in a fixed order.
 *
 * A card with no English title is left out: there is nothing to tell its kind
 * by, and a translated mood mix would otherwise pass for one of the
 * listener's own.
 */
fun yourMixes(mixes: List<KeyedMix>): List<ShelfItem> {
    val yours = mutableListOf<ShelfItem>()
    val named = mutableListOf<Pair<Int, ShelfItem>>()
    mixes.distinctBy { it.item.browseId ?: it.item.title }.forEach { mix ->
        val key = mix.englishTitle?.trim() ?: return@forEach
        val rank = NAMED_MIXES.indexOf(key.lowercase())
        val numbered = NUMBERED_MIX.matchEntire(key)
        when {
            rank >= 0 -> named += rank to mix.item
            MOOD_SUPERMIX.matches(key) -> Unit
            numbered != null && !numbered.groupValues[1].equals("My", ignoreCase = true) -> Unit
            else -> yours += mix.item
        }
    }
    return yours + named.sortedBy { it.first }.map { it.second }
}

/** The named mixes' English titles, lower-case, in the order the carousel shows them. */
private val NAMED_MIXES = listOf(
    "my supermix", "discover mix", "new release mix", "replay mix", "archive mix", "liked music",
)

/** "Feel Good Supermix". "My Supermix" is a named mix, matched before this. */
private val MOOD_SUPERMIX = Regex("""^(.+?)\s+supermix$""", RegexOption.IGNORE_CASE)

/** "Feel Good Mix 2" → "Feel Good", 2. "My Mix 2" is the listener's own. */
private val NUMBERED_MIX = Regex("""^(.+?)\s+mix\s*(\d+)$""", RegexOption.IGNORE_CASE)
