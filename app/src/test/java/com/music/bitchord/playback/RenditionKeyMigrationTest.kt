package com.music.bitchord.playback

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.innertube.YOUTUBE_PREMIUM_RENDITION
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Entries keyed before renditions had keys of their own are dropped once, on
 * the first launch after: a partial one always, every one with Premium on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RenditionKeyMigrationTest {

    private val complete = "abcdefghijk"
    private val partial = "bcdefghijkl"
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val size = 1024

    @Before fun setUp() {
        AppSettings.init(app, AuthStore(app))
    }

    @After fun tearDown() {
        AppSettings.setYoutubePremiumAudio(false)
        release()
    }

    private fun cache(): SimpleCache =
        AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(AudioCache) as SimpleCache

    private fun release() {
        runCatching { cache().release() }
        AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(AudioCache, null)
        AudioCache::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(AudioCache, null)
    }

    private fun write(cache: SimpleCache, key: String, length: Long = size.toLong()) {
        val bytes = ByteArray(size) { 7 }
        val writer = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory { key }
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }
            .createDataSource()
        writer.open(DataSpec(Uri.parse("test://$key")))
        val buffer = ByteArray(size)
        while (writer.read(buffer, 0, size) != -1) Unit
        writer.close()
        // Longer than what is on disk: a partial entry.
        if (length > size) {
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) },
            )
        }
    }

    /** Fills the old-style entries, then relaunches the cache with the migration not yet run. */
    private fun relaunchWithOldEntries(premium: Boolean): Set<String> {
        AudioCache.init(app)
        val cache = cache()
        write(cache, complete)
        write(cache, partial, length = 4L * size)
        write(cache, "$complete#analysis")
        write(cache, "$complete#premium")
        release()
        app.getSharedPreferences("audio_cache_state", Context.MODE_PRIVATE).edit().remove("rendition_keys").commit()
        AppSettings.setYoutubePremiumAudio(premium)
        AudioCache.init(app)
        return cache().keys
    }

    @Test
    fun aPartialOldEntryGoesAndACompleteOneStaysWithPremiumOff() {
        val keys = relaunchWithOldEntries(premium = false)
        assertFalse(partial in keys)
        assertTrue(complete in keys)
        assertTrue("$complete#analysis" in keys)
        assertTrue("$complete#premium" in keys)
    }

    @Test
    fun everyOldEntryGoesWithPremiumOnButTheRenditionKeyedOnesStay() {
        val keys = relaunchWithOldEntries(premium = true)
        assertFalse(partial in keys)
        assertFalse(complete in keys)
        assertFalse("$complete#analysis" in keys)
        assertTrue("$complete#premium" in keys)
    }

    @Test
    fun itRunsOnce() {
        relaunchWithOldEntries(premium = false)
        val cache = cache()
        write(cache, partial, length = 4L * size)
        release()
        AudioCache.init(app)
        assertTrue(partial in cache().keys)
    }

    @Test
    fun oldKeysAreRecognisedAndRenditionKeysAreNot() {
        assertTrue(AudioCache.isPreRenditionYouTubeKey(complete))
        assertTrue(AudioCache.isPreRenditionYouTubeKey("$complete#analysis"))
        assertTrue(AudioCache.isPreRenditionYouTubeKey("$complete#analysis#alt2"))
        assertFalse(AudioCache.isPreRenditionYouTubeKey("$complete#premium"))
        assertFalse(AudioCache.isPreRenditionYouTubeKey("$complete#analysis-premium"))
        assertFalse(AudioCache.isPreRenditionYouTubeKey("$complete#alt2"))
        assertFalse(AudioCache.isPreRenditionYouTubeKey("source|track"))
    }

    @Test
    fun thePremiumSettingNamesTheRendition() {
        com.music.bitchord.data.innertube.AndroidStreamHooks.installEarly()
        AppSettings.setYoutubePremiumAudio(true)
        assertEquals(YOUTUBE_PREMIUM_RENDITION, AutomixAnalysisSource.rendition())
        AppSettings.setYoutubePremiumAudio(false)
        assertNull(AutomixAnalysisSource.rendition())
        AutomixAnalysisSource.rendition = { null }
    }
}
