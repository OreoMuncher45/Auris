package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a menu's download row counts as "seen": a failure it states once is
 * cleared when the menu closes, so a row that was composed but scrolled out
 * of sight must not count.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SeenWhenOnScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun halfOnScreenIsTheLine() {
        assertTrue(isHalfOnScreen(height = 100, visibleHeight = 100f))
        assertTrue(isHalfOnScreen(height = 100, visibleHeight = 50f))
        assertFalse(isHalfOnScreen(height = 100, visibleHeight = 49f))
        assertFalse(isHalfOnScreen(height = 100, visibleHeight = 0f))
        assertFalse("a row with no size is never seen", isHalfOnScreen(height = 0, visibleHeight = 0f))
    }

    @Test
    fun aRowBelowTheMenusVisiblePartIsNotSeenUntilItIsScrolledTo() {
        var seen = 0
        val scroll = ScrollState(0)
        compose.setContent {
            // A menu capped at 200dp, with the failure row below six rows' worth.
            Box(Modifier.height(200.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    Spacer(Modifier.height(300.dp))
                    Spacer(Modifier.fillMaxWidth().height(48.dp).seenWhenOnScreen { seen++ })
                }
            }
        }
        compose.waitForIdle()
        assertEquals("composed, but out of sight", 0, seen)

        runBlocking { scroll.scrollTo(scroll.maxValue) }
        compose.waitForIdle()
        assertTrue("scrolled into the menu's visible part, it has been seen", seen > 0)
    }

    @Test
    fun aRowAlreadyInViewIsSeenAtOnce() {
        var seen = 0
        compose.setContent {
            Box(Modifier.height(200.dp)) {
                Spacer(Modifier.fillMaxWidth().height(48.dp).seenWhenOnScreen { seen++ })
            }
        }
        compose.waitForIdle()
        assertTrue(seen > 0)
    }
}
