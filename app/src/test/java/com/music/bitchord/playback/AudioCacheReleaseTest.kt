package com.music.bitchord.playback

import android.app.Application
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cache's background cleanups can reach it after it was released, which a
 * test does in its teardown. That used to fail in [AudioCache]'s scope with no
 * one to catch it, and kotlinx-coroutines-test then failed whichever
 * coroutine test ran next with UncaughtExceptionsBeforeTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AudioCacheReleaseTest {

    private val field = AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
        AudioCache.init(app)
    }

    @After fun tearDown() {
        runBlocking { AudioCache.cancelBackgroundWorkForTest() }
        field.set(AudioCache, null)
        AudioCache::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(AudioCache, null)
    }

    @Test
    fun `a cleanup that reaches a released cache ends quietly, and the next coroutine test runs`() {
        val cache = field.get(AudioCache) as SimpleCache
        cache.release()
        val cleanup = AudioCache.dropForeignEntries(keep = emptySet())
        assertNotNull(cleanup)
        runBlocking { cleanup!!.join() }
        // A failed child of the cache's scope ends cancelled; a quiet one completes.
        assertFalse("the cleanup failed on the released cache", cleanup!!.isCancelled)
        // What used to fail as UncaughtExceptionsBeforeTest.
        runTest { }
    }

    @Test
    fun `a cleanup that reaches an unset cache ends quietly`() {
        val cache = field.get(AudioCache) as SimpleCache
        val cleanup = AudioCache.dropForeignEntries(keep = emptySet())
        // Unset only after the job started its walk or before: either way it must not fail.
        cache.release()
        field.set(AudioCache, null)
        runBlocking { cleanup?.join() }
        assertFalse(cleanup?.isCancelled == true)
        runTest { }
    }
}
