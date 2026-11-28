package com.music.bitchord.playback

import android.app.Application
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A whole-track read-ahead pass captures its key once. When the key the
 * settings name changes between its ranges (Premium switched on), the pass
 * stops rather than writing under one key while checking another, and nothing
 * lands under the other key; a fresh pass then runs under the new one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WholePassRoutingTest {

    private val id = "abcdefghijk"
    private val chunk = 128L * 1024
    private val total = 3 * chunk
    private lateinit var cache: SimpleCache
    private var opens = 0

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
        AudioCache.init(app)
        cache = AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(AudioCache) as SimpleCache
        AudioCache::class.java.getDeclaredField("pendingQueue").apply { isAccessible = true }.set(AudioCache, listOf(id))
        AudioCache.setUpstream(
            DataSource.Factory {
                val inner = ByteArrayDataSource(ByteArray(total.toInt()) { 'A'.code.toByte() })
                object : DataSource by inner {
                    override fun open(dataSpec: DataSpec): Long {
                        // Premium becomes active while the first range is fetched.
                        if (opens++ == 0) AutomixAnalysisSource.rendition = { "premium" }
                        return inner.open(dataSpec)
                    }
                }
            },
        )
    }

    @After fun tearDown() {
        // The collector init() starts on the cache-size setting, stopped before
        // the cache goes, so none outlives its test.
        kotlinx.coroutines.runBlocking { AudioCache.cancelBackgroundWorkForTest() }
        AutomixAnalysisSource.rendition = { null }
        AudioCache::class.java.getDeclaredField("pendingQueue").apply { isAccessible = true }.set(AudioCache, emptyList<String>())
        AudioCache::class.java.getDeclaredField("upstreamFactory").apply { isAccessible = true }.set(AudioCache, null)
        cache.release()
        AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(AudioCache, null)
        AudioCache::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(AudioCache, null)
    }

    @Test
    fun aRenditionSwitchMidPassStopsThePassOnItsOwnKey() = runBlocking {
        val whole = AudioCache.cacheWholePass(id, total, chunk)

        // Stopped, for a fresh pass under the new routing.
        assertFalse(whole)
        // The range it fetched stayed on the key it captured, and was verified there.
        assertTrue(cache.getCachedBytes(id, 0, chunk) >= chunk)
        // Nothing was written under the other key.
        assertEquals(0L, cache.getCachedSpans("$id#premium").sumOf { if (it.isCached) it.length else 0L })
    }
}
