package com.music.bitchord.data.canvas

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A token cached for one Spotify session is never handed to another.
 *
 * Both interleavings an account switch opens are driven by hand: the cookie
 * write that lands before its [SpotifyToken.invalidate], and a call that was
 * queued for the harvest lock while the switch happened. No harvest runs —
 * [SpotifyToken.init] is never called, so a miss answers null — which is the
 * point: the only way to get a token back here is a cache hit.
 */
@RunWith(AndroidJUnit4::class)
// The plain Application, as in the other Robolectric tests: the manifest one
// loads Java 21 bytecode a JDK 17 test worker can't define.
@Config(sdk = [34], application = Application::class)
class SpotifyTokenSessionTest {

    private val future get() = System.currentTimeMillis() + 3_600_000L

    private fun set(name: String, value: Any?) {
        SpotifyToken::class.java.getDeclaredField(name).apply { isAccessible = true }.set(SpotifyToken, value)
    }

    private fun mutex(): Mutex =
        SpotifyToken::class.java.getDeclaredField("harvestMutex").apply { isAccessible = true }.get(SpotifyToken) as Mutex

    @Before
    fun clean() {
        SpotifyToken.invalidate()
        // Nothing on disk to restore from: this test drives the memory cache.
        set("restored", true)
    }

    @After
    fun reset() {
        AppSettings.spotifySpdcToken.value = ""
        SpotifyToken.invalidate()
    }

    @Test
    fun aTokenCachedForTheOldCookieIsNotReturnedForTheNewOne() = runBlocking {
        set("cachedAccessToken", "token-for-A")
        set("cachedAccessCookie", "cookie-A")
        set("accessTokenExpiresAtMs", future)

        // The settings write has landed; its invalidate() has not run yet.
        AppSettings.spotifySpdcToken.value = "cookie-B"
        assertNull(SpotifyToken.accessToken())

        // The same cache still answers the session it was minted for.
        AppSettings.spotifySpdcToken.value = "cookie-A"
        assertEquals("token-for-A", SpotifyToken.accessToken())
    }

    @Test
    fun aCallQueuedAcrossAnInvalidationDoesNotTakeTheNextSessionsToken() = runBlocking {
        val lock = mutex()
        lock.lock()
        // Queued for the harvest lock with nothing cached.
        val queued = async(start = CoroutineStart.UNDISPATCHED) { SpotifyToken.anonymousToken() }

        // The switch happens while it waits, and the next session's token is
        // cached before the lock comes free.
        SpotifyToken.invalidate()
        set("cachedAnonymousToken", "visitor-token-after-switch")
        set("anonymousTokenExpiresAtMs", future)
        lock.unlock()

        assertNull(queued.await())
    }
}
