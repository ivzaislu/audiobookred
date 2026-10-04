package com.example.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test
    fun successIsReturnedAsResult() = runBlocking {
        val result = runCatchingCancellable { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun ordinaryExceptionIsReturnedAsFailure() = runBlocking {
        val failure = IllegalStateException("boom")
        val result = runCatchingCancellable<Int> { throw failure }

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        runBlocking {
            runCatchingCancellable<Unit> { throw CancellationException("cancel") }
        }
    }

    @Test(expected = AssertionError::class)
    fun fatalErrorIsRethrown() {
        runBlocking {
            runCatchingCancellable<Unit> { throw AssertionError("fatal") }
        }
    }
}
