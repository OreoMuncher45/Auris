package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A sheet drawn in the page's own window has to manage focus itself: a form
 * on a sheet that is going lets go of the focus at once, so the keyboard goes
 * down with it and a Done can't submit what the listener just dismissed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppSheetFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSheetGoingTakesTheFocusWithIt() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var focused = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppSheet(item = item, onDismiss = { item = null }) {
                        BasicTextField(
                            value = "Road trip",
                            onValueChange = {},
                            modifier = Modifier
                                .testTag(FIELD)
                                .onFocusChanged { focused = it.isFocused },
                        )
                    }
                }
            }
        }
        item = "form"
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD).performClick()
        compose.waitForIdle()
        assertTrue("the form takes the focus while the sheet is up", focused)

        compose.mainClock.autoAdvance = false
        item = null
        Snapshot.sendApplyNotifications()
        // A few frames into the exit (220 ms), well before it ends.
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { }
        // Still drawn for its exit, and already holding nothing.
        assertFalse("the form lets go of the focus as the sheet goes", focused)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private companion object {
        const val FIELD = "field"
    }
}
