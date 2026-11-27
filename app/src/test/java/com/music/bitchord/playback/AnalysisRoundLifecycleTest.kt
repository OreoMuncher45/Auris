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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        AudioCache.wipeWaiting = null
        // A wipe still running would touch the cache released below.
        (field("lastWipe") as Job?)?.let { wipe ->
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { wipe.join() } }
        }
        scope.cancel()
        // The cache's own background work first, so none of it runs on a released cache.
        AudioCache.cancelBackgroundWorkForTest()
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

    @Suppress("UNCHECKED_CAST")
    private val removing get() = (field("removing") as Map<*, *>).keys

    /** Registers a round that does not stop until [writerDone], claiming [id]. */
    private fun liveRound(): Job {
        // Undispatched, so its body is already inside NonCancellable when it is
        // registered: a default-start job cancelled before it ran would skip
        // the wait altogether and stand in for no writer at all.
        val round = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { writerDone.await() }
        }
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
        val waiting = java.util.concurrent.CountDownLatch(2)
        AudioCache.wipeWaiting = { waiting.countDown() }
        AudioCache.clear { first = true }
        // The registry is already empty for this one.
        AudioCache.clear { second = true }

        // Both wipes are in their wait while the writer is still held: the
        // race is exercised, not just given time to not happen.
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        assertFalse(idleMainUntil(timeoutMs = 300) { first || second })

        writerDone.complete(Unit)
        assertTrue(idleMainUntil { first && second })
    }

    @Test
    fun `removing a track stops its round before releasing the claim`() {
        val round = liveRound()
        val removal = scope.async { AudioCache.remove(id) }

        // Polled, not slept on: the removal has to have run far enough to
        // cancel the round before the rest says anything.
        assertTrue(idleMainUntil { round.isCancelled })
        // Still stopping: the claim keeps a replacement round out meanwhile.
        assertFalse(removal.isCompleted)
        assertTrue(heads.containsKey(id))
        // And reserved, so no round is admitted for it until the bytes are gone.
        assertTrue(removing.contains(id))

        writerDone.complete(Unit)
        assertTrue(idleMainUntil { removal.isCompleted })
        // Completed normally: a removal that threw would pass isCompleted too.
        kotlinx.coroutines.runBlocking { removal.await() }
        assertFalse(heads.containsKey(id))
        assertTrue(round.isCompleted)
        assertFalse(removing.contains(id))
    }
}
