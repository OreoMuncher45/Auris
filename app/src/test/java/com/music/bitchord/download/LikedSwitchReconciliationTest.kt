package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Liked Music record On with the keep-liked setting Off showed the switch On
 * in Settings and on the page while every sync pass refused it.
 */
class LikedSwitchReconciliationTest {

    @Test
    fun `an existing enabled record turns the setting on when it was never chosen`() {
        assertEquals(
            LikedSwitchFix.SETTING_ON,
            likedSwitchReconciliation(recordOn = true, settingOn = false, settingChosen = false),
        )
    }

    @Test
    fun `an explicit off finishes turning the record off`() {
        assertEquals(
            LikedSwitchFix.RECORD_OFF,
            likedSwitchReconciliation(recordOn = true, settingOn = false, settingChosen = true),
        )
    }

    @Test
    fun `agreement and a setting already on change nothing`() {
        assertEquals(LikedSwitchFix.NONE, likedSwitchReconciliation(recordOn = true, settingOn = true, settingChosen = true))
        assertEquals(LikedSwitchFix.NONE, likedSwitchReconciliation(recordOn = false, settingOn = false, settingChosen = true))
    }

    /** An On whose record update never landed: the page showed Off and its tap could only ask for On again. */
    @Test
    fun `an on setting over an off record turns the record on`() {
        assertEquals(LikedSwitchFix.RECORD_ON, likedSwitchReconciliation(recordOn = false, settingOn = true, settingChosen = true))
        assertEquals(LikedSwitchFix.RECORD_ON, likedSwitchReconciliation(recordOn = false, settingOn = true, settingChosen = false))
    }

    @Test
    fun `a collection listing changes only when its tracks or their order do`() {
        val ids = listOf("a", "b")
        assertEquals(false, Downloads.collectionListingChanged(ids, ids))
        assertEquals(false, Downloads.collectionListingChanged(ids, listOf("a", "b")))
        assertEquals(true, Downloads.collectionListingChanged(ids, listOf("a", "b", "c")))
        assertEquals(true, Downloads.collectionListingChanged(ids, listOf("b", "a")))
        assertEquals(true, Downloads.collectionListingChanged(ids, null))
    }
}
