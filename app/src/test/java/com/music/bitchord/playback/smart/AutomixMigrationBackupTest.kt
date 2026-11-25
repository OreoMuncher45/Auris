package com.music.bitchord.playback.smart

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The migration's marker describes this install, not the listener's
 * settings: a backup neither carries it nor clears it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AutomixMigrationBackupTest {

    @Test
    fun aSettingsBackupNeitherCarriesNorClearsTheMigrationMarker() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
        val prefs = app.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean(AutomixMigration.KEY_DONE, false))

        assertFalse(AutomixMigration.KEY_DONE in AppSettings.exportPrefs())

        // A backup made before the move has no marker at all.
        AppSettings.importPrefs(mapOf("crossfade_seconds" to 5))
        assertTrue(prefs.getBoolean(AutomixMigration.KEY_DONE, false))
    }
}
