package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which "Mixed for you" cards Radio's Your Mixes carousel keeps, and in what order. */
class YourMixesTest {

    private fun mix(id: String, shown: String, english: String = shown) =
        KeyedMix(ShelfItem(shown, "", null, null, id), english)

    @Test
    fun keepsOwnAndNamedMixesAndDropsMoods() {
        val page = listOf(
            mix("d", "Discover Mix"),
            mix("c1", "Chill Mix 1"),
            mix("m1", "My Mix 1"),
            mix("fs", "Feel Good Supermix"),
            mix("r", "Replay Mix"),
            mix("m2", "My Mix 2"),
            mix("p", "Modern Punjabi Beats"),
            mix("s", "My Supermix"),
            mix("n", "New Release Mix"),
        )
        assertEquals(
            listOf("m1", "m2", "p", "s", "d", "n", "r"),
            yourMixes(page).map { it.browseId },
        )
    }

    @Test
    fun sortsByEnglishTitleButShowsTheCardsOwn() {
        val page = listOf(
            mix("d", "Mix Découverte", "Discover Mix"),
            mix("c", "Mix Détente 1", "Chill Mix 1"),
            mix("m", "Mon mix 1", "My Mix 1"),
        )
        assertEquals(listOf("Mon mix 1", "Mix Découverte"), yourMixes(page).map { it.title })
    }

    @Test
    fun dropsARepeatedCard() {
        val page = listOf(mix("m1", "My Mix 1"), mix("m1", "My Mix 1"))
        assertEquals(1, yourMixes(page).size)
    }
}
