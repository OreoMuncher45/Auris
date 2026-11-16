package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two opens of one track racing to pin it: exactly one wins, and the pin in
 * force is the winner's. The cache entry is shared, so two winners would mean
 * two servers' bytes in it.
 */
class StreamChoiceConcurrentPinTest {

    private val ids = (0 until ROUNDS).map { "race-$it" }

    @After
    fun tearDown() = ids.forEach(StreamChoice::forget)

    @Test
    fun onlyOneOfManyConcurrentPinsWins() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            for (id in ids) {
                val start = CountDownLatch(1)
                val done = CountDownLatch(THREADS)
                val winners = AtomicInteger()
                val winningUrl = AtomicReference<String>()
                repeat(THREADS) { n ->
                    pool.execute {
                        start.await()
                        val url = "https://example.invalid/$id/$n"
                        if (StreamChoice.remember(id, SourceStream(url), substituted = true)) {
                            winners.incrementAndGet()
                            winningUrl.set(url)
                        }
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(10, TimeUnit.SECONDS))
                assertEquals("winners for $id", 1, winners.get())
                assertNotNull(StreamChoice.of(id))
                assertEquals(winningUrl.get(), StreamChoice.of(id)?.url)
                // Keep the map small: StreamChoice holds a bounded number of pins.
                StreamChoice.forget(id)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 200
    }
}
