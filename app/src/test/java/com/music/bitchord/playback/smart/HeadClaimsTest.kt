package com.music.bitchord.playback.smart

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeadClaimsTest {

    @Test
    fun anEvictedHeadGivesItsClaimBackSoTheSamePrefixCanRebuildIt() {
        val attempts = ConcurrentHashMap<String, Long>()
        val claims = HeadClaims()
        attempts["vid#analysis"] = 600_000L
        claims.record("vid", "vid#analysis", 600_000L)

        claims.release("vid", attempts)

        // Nothing left for the growth filter to refuse the unchanged prefix on.
        assertFalse(attempts.containsKey("vid#analysis"))
    }

    @Test
    fun aNewerClaimOnTheSameCopyIsKept() {
        val attempts = ConcurrentHashMap<String, Long>()
        val claims = HeadClaims()
        claims.record("vid", "vid#analysis", 600_000L)
        // A later attempt on a grown prefix took the claim since.
        attempts["vid#analysis"] = 1_200_000L

        claims.release("vid", attempts)

        assertEquals(1_200_000L, attempts["vid#analysis"])
    }

    @Test
    fun aHeadSupersededByAWholeTrackResultHasNothingToGiveBack() {
        val attempts = ConcurrentHashMap<String, Long>()
        val claims = HeadClaims()
        attempts["vid#analysis"] = 600_000L
        claims.record("vid", "vid#analysis", 600_000L)

        claims.drop("vid")
        claims.release("vid", attempts)

        assertEquals(600_000L, attempts["vid#analysis"])
    }
}
