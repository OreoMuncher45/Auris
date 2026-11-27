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
        assertEquals(LikedSwitchFix.NONE, likedSwitchReconciliation(recordOn = false, settingOn = true, settingChosen = false))
    }
}
