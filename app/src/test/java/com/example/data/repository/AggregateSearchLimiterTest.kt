package com.example.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregateSearchLimiterTest {
    @Test
    fun boundedMapPreservesInputOrder() = runBlocking {
        val limiter = AggregateSearchLimiter(permits = 2)

        val result = boundedProviderMap(
            values = listOf(1, 2, 3, 4),
            limiter = limiter,
        ) { value ->
            // Finish in a different order than the input to prove awaitAll keeps
            // the source ordering used by aggregate search interleaving.
            delay((5 - value) * 10L)
            value * 10
        }

        assertEquals(listOf(10, 20, 30, 40), result)
    }

    @Test
    fun oneLimiterBoundsConcurrencyAcrossParallelFanouts() = runBlocking {
        val limiter = AggregateSearchLimiter(permits = 2)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        suspend fun work(value: Int): Int {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(40L)
                return value
            } finally {
                active.decrementAndGet()
            }
        }

        val results = coroutineScope {
            val first = async {
                boundedProviderMap(
                    values = listOf(1, 2, 3),
                    limiter = limiter,
                    transform = ::work,
                )
            }
            val second = async {
                boundedProviderMap(
                    values = listOf(4, 5, 6),
                    limiter = limiter,
                    transform = ::work,
                )
            }
            first.await() + second.await()
        }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), results)
        assertTrue("max concurrency was ${maxActive.get()}", maxActive.get() <= 2)
        assertEquals(0, active.get())
    }

    @Test
    fun invalidPermitCountStillAllowsProgress() = runBlocking {
        val limiter = AggregateSearchLimiter(permits = 0)

        assertEquals(
            listOf("ok"),
            boundedProviderMap(listOf("ok"), limiter) { it },
        )
    }
}
