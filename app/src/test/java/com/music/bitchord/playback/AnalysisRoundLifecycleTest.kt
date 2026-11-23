package com.music.bitchord.playback

import android.app.Application
import android.os.Looper
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Analysis rounds against the two ways the listener throws cache away: a
 * whole wipe and one track's removal. A round still writing must be stopped
 * before either reports done, or its bytes land after the deletion.
 *
 * The round is stood in for by a job that keeps writing through its
 * cancellation until [writerDone] opens, as a blocking network read does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AnalysisRoundLifecycleTest {

    private val id = "abcdefghijk"
    private lateinit var cache: SimpleCache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerDone = CompletableDeferred<Unit>()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
        AudioCache.init(app)
        cache = field("cache") as SimpleCache
    }

    @After fun tearDown() {
        writerDone.complete(Unit)
        scope.cancel()
        cache.release()
        AudioCache::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(AudioCache, null)
        AudioCache::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(AudioCache, null)
    }

    private fun field(name: String): Any? =
        AudioCache::class.java.getDeclaredField(name).apply { isAccessible = true }.get(AudioCache)

    @Suppress("UNCHECKED_CAST")
    private val jobs get() = field("analysisJobs") as ConcurrentHashMap<String, Job>

    @Suppress("UNCHECKED_CAST")
    private val heads get() = field("analysisHeads") as ConcurrentHashMap<String, Boolean>

    /** Registers a round that does not stop until [writerDone], claiming [id]. */
    private fun liveRound(): Job {
        val round = scope.launch { withContext(NonCancellable) { writerDone.await() } }
        heads[id] = true
        jobs[id] = round
        return round
    }

    private fun idleMainUntil(timeoutMs: Long = 5_000, done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }

    @Test
    fun `a second wipe does not report done before the first wipe's writer has stopped`() {
        liveRound()
        var first = false
        var second = false
        AudioCache.clear { first = true }
        // The registry is already empty for this one.
        AudioCache.clear { second = true }

        assertFalse(idleMainUntil(timeoutMs = 300) { first || second })

        writerDone.complete(Unit)
        assertTrue(idleMainUntil { first && second })
    }
}
