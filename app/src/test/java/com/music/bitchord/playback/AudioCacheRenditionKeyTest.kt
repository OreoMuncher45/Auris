package com.music.bitchord.playback

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Two of YouTube's renditions of one track (the ordinary Opus and Premium's
 * higher rung) are different encodings, so each is filed under its own cache
 * entry and read back from its own entry, never the other's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AudioCacheRenditionKeyTest {

    private val id = "abcdefghijk"
    private val watch = Uri.parse("bitchord://watch?v=$id")
    private lateinit var cache: SimpleCache

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
        AudioCache.init(app)
        cache = AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(AudioCache) as SimpleCache
    }

    @After fun tearDown() {
        AutomixAnalysisSource.rendition = { null }
        // The cache's own background work first, so none of it runs on a released cache.
        AudioCache.cancelBackgroundWorkForTest()
        cache.release()
        AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(AudioCache, null)
        AudioCache::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(AudioCache, null)
    }

    @Test
    fun eachRenditionIsFiledAndReadUnderItsOwnEntry() {
        AutomixAnalysisSource.rendition = { null }
        val ordinary = AudioCache.cacheKeyOf(watch)
        AutomixAnalysisSource.rendition = { "premium" }
        val premium = AudioCache.cacheKeyOf(watch)

        assertEquals(id, ordinary)
        assertEquals("$id#premium", premium)
        assertNotEquals(ordinary, premium)

        // A switch back reads the ordinary entry again: both are kept.
        AutomixAnalysisSource.rendition = { null }
        assertEquals(ordinary, AudioCache.cacheKeyOf(watch))
    }

    @Test
    fun anotherRenditionsEntryIsNeverOfferedAsASibling() {
        AutomixAnalysisSource.rendition = { "premium" }
        val siblings = AudioCache.siblingKeysOf(id)
        assertEquals("$id#premium", siblings.first())
        assertFalse(id in siblings)
    }
}
