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
import org.junit.Assert.assertFalse
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

    /** A `SpotifyToken.CachedToken`, which is private to the object. */
    private fun cachedToken(token: String, cookie: String?): Any =
        Class.forName("com.music.bitchord.data.canvas.SpotifyToken\$CachedToken")
            .getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .newInstance(token, future, cookie)

    private fun get(name: String): Any? =
        SpotifyToken::class.java.getDeclaredField(name).apply { isAccessible = true }.get(SpotifyToken)

    /** The decision every cache hit makes, on the snapshot it read. */
    private fun usable(snapshot: Any, cookie: String?): Boolean =
        snapshot.javaClass.getDeclaredMethod("usableFor", String::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(snapshot, cookie, System.currentTimeMillis()) as Boolean

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
        // Token, cookie and expiry are one immutable value, so no reader can
        // see A's token beside B's cookie: the torn read has no field to tear.
        set("access", cachedToken("token-for-A", "cookie-A"))

        // The settings write has landed; its invalidate() has not run yet.
        AppSettings.spotifySpdcToken.value = "cookie-B"
        assertNull(SpotifyToken.accessToken())

        // The same cache still answers the session it was minted for.
        AppSettings.spotifySpdcToken.value = "cookie-A"
        assertEquals("token-for-A", SpotifyToken.accessToken())
    }

    @Test
    fun aLookupThatReadTheOldSnapshotCannotHandItToTheNewCookie() = runBlocking {
        // A lookup reads the one reference once, then decides on what it read
        // with usableFor(cookie, now). Pause it right there: it holds A's
        // snapshot while the cache moves on to B's.
        set("access", cachedToken("token-for-A", "cookie-A"))
        val heldByThePausedLookup = get("access")!!
        set("access", cachedToken("token-for-B", "cookie-B"))
        AppSettings.spotifySpdcToken.value = "cookie-B"

        // Resumed with B's cookie, the decision it makes on the snapshot it
        // holds refuses A's token...
        assertFalse(usable(heldByThePausedLookup, "cookie-B"))
        // ...and the cache as it now stands answers B with B's.
        assertEquals("token-for-B", SpotifyToken.accessToken())
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
        set("anonymous", cachedToken("visitor-token-after-switch", null))
        lock.unlock()

        assertNull(queued.await())
    }
}
