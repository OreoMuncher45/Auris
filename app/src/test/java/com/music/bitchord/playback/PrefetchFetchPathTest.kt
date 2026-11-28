package com.music.bitchord.playback

import android.app.Application
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A read-ahead of a YouTube track, through the real fetch path (its lock probe
 * included), under Premium: every byte lands under the Premium entry, and the
 * probe's check reads that entry, so the fetch does not stop after the probe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PrefetchFetchPathTest {

    private val id = "abcdefghijk"
    private val length = 256 * 1024
    private lateinit var cache: SimpleCache

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
        AudioCache.init(app)
        cache = AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(AudioCache) as SimpleCache
        AudioCache.setUpstream(DataSource.Factory { ByteArrayDataSource(ByteArray(length) { 'P'.code.toByte() }) })
    }

    @After fun tearDown() {
        // The collector init() starts on the cache-size setting, stopped before
        // the cache goes, so none outlives its test.
        kotlinx.coroutines.runBlocking { AudioCache.cancelBackgroundWorkForTest() }
        AutomixAnalysisSource.rendition = { null }
        AudioCache::class.java.getDeclaredField("upstreamFactory").apply { isAccessible = true }.set(AudioCache, null)
        cache.release()
        AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(AudioCache, null)
        AudioCache::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(AudioCache, null)
    }

    @Test
    fun underPremiumTheWholeRangePastTheProbeIsFetchedUnderThePremiumEntry() = runBlocking {
        AutomixAnalysisSource.rendition = { "premium" }

        AudioCache.fetch(id, 0, length.toLong())

        // Past the 64 KiB probe: all of it, under the key the bytes went to.
        assertEquals(length.toLong(), cache.getCachedBytes("$id#premium", 0, length.toLong()))
        // And nothing under the ordinary entry.
        assertEquals(0L, cache.getCachedSpans(id).sumOf { if (it.isCached) it.length else 0L })
    }

    @Test
    fun theOrdinaryRenditionStillFillsTheBareEntry() = runBlocking {
        AudioCache.fetch(id, 0, length.toLong())
        assertEquals(length.toLong(), cache.getCachedBytes(id, 0, length.toLong()))
    }

    @Test
    fun aRenditionThatFlipsBetweenReadsStillGivesAMatchingKeyAndAddress() {
        // The rendition switches on every read, as a Premium toggle landing
        // between two reads would: the key and the address must still agree.
        var reads = 0
        AutomixAnalysisSource.rendition = { if (reads++ % 2 == 0) null else "premium" }
        repeat(4) {
            val (key, uri) = AudioCache.readAheadTarget(id)
            val addressed = uri.getQueryParameter(AutomixAnalysisSource.RENDITION_PARAMETER)?.ifEmpty { null }
            assertEquals("key $key against address $uri", AutomixAnalysisSource.playbackKey(id, addressed), key)
        }
    }
}
