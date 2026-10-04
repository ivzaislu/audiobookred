package com.example.data.parser

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BazaKnigSingleFlightTest {
    @Test
    fun concurrentSameKeySharesOneInFlightOperation() = runBlocking {
        val singleFlight = BazaKnigSingleFlight<Int>()
        val calls = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            singleFlight.run("bazaknig:91570") {
                calls.incrementAndGet()
                firstStarted.complete(Unit)
                releaseFirst.await()
                42
            }
        }

        firstStarted.await()
        val duplicateBlockStarted = CompletableDeferred<Unit>()
        val second = async(Dispatchers.Default) {
            singleFlight.run("bazaknig:91570") {
                calls.incrementAndGet()
                duplicateBlockStarted.complete(Unit)
                99
            }
        }

        assertNull(withTimeoutOrNull(200) { duplicateBlockStarted.await() })
        releaseFirst.complete(Unit)

        assertEquals(42, first.await())
        assertEquals(42, second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellingFirstCallerDoesNotCancelIndependentWaiter() = runBlocking {
        val singleFlight = BazaKnigSingleFlight<Int>()
        val calls = AtomicInteger(0)
        val operationStarted = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            singleFlight.run("bazaknig:91570") {
                calls.incrementAndGet()
                operationStarted.complete(Unit)
                releaseOperation.await()
                42
            }
        }
        operationStarted.await()

        val duplicateBlockStarted = CompletableDeferred<Unit>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("bazaknig:91570") {
                calls.incrementAndGet()
                duplicateBlockStarted.complete(Unit)
                99
            }
        }

        first.cancelAndJoin()
        assertNull(withTimeoutOrNull(200) { duplicateBlockStarted.await() })
        releaseOperation.complete(Unit)

        assertEquals(42, waiter.await())
        assertEquals(1, calls.get())
    }
}
