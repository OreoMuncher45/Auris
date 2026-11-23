package com.music.bitchord.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CandidateValidationTest {

    @Test
    fun `a read with nothing under validation goes ahead at once`() = runTest {
        val validation = CandidateValidation()
        var settled = false
        launch {
            validation.awaitSettled()
            settled = true
        }
        runCurrent()
        assertTrue(settled)
    }

    @Test
    fun `a read started during validation waits for it to end`() = runTest {
        val validation = CandidateValidation()
        val accountCheck = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch { validation.during { accountCheck.await(); events += "validated" } }
        runCurrent()
        assertTrue(validation.active)

        launch {
            validation.awaitSettled()
            events += "read"
        }
        runCurrent()
        assertEquals(emptyList<String>(), events)

        accountCheck.complete(Unit)
        runCurrent()
        assertEquals(listOf("validated", "read"), events)
        assertFalse(validation.active)
    }

    @Test
    fun `a failed validation still releases waiting reads`() = runTest {
        val validation = CandidateValidation()
        runCatching { validation.during { error("account check failed") } }
        assertFalse(validation.active)
        var settled = false
        launch {
            validation.awaitSettled()
            settled = true
        }
        runCurrent()
        assertTrue(settled)
    }
}
