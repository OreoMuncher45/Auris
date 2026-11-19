package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The slot a ⋮ leaves its position in. A wrong answer here doesn't crash: it
 * hangs a menu off a button that wasn't the one tapped, or off one tapped
 * long ago.
 */
class MenuOriginTest {

    private val button = Rect(left = 900f, top = 400f, right = 1_000f, bottom = 500f)

    @Before
    fun clear() {
        MenuOrigin.consume()
    }

    @Test
    fun theButtonJustTappedIsWhereTheMenuOpens() {
        MenuOrigin.record(button)
        assertEquals(button, MenuOrigin.consume())
    }

    @Test
    fun aTapIsSpentByTheMenuItOpened() {
        MenuOrigin.record(button)
        MenuOrigin.consume()
        assertNull("a second opening must not reuse the first one's button", MenuOrigin.consume())
    }

    @Test
    fun theLaterOfTwoTapsWins() {
        val later = Rect(left = 40f, top = 1_200f, right = 140f, bottom = 1_300f)
        MenuOrigin.record(button)
        MenuOrigin.record(later)
        assertEquals(later, MenuOrigin.consume())
    }

    @Test
    fun aTapNobodyAskedAboutGoesStale() {
        MenuOrigin.record(button)
        Thread.sleep(600)
        assertNull("an opening long after the tap must not claim it", MenuOrigin.consume())
    }
}
