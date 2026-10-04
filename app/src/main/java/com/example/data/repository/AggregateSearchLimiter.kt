package com.example.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Shared concurrency budget for public-source fan-out.
 *
 * The limiter is deliberately independent from provider timeout/cancellation
 * policy. In particular, a browser challenge may legitimately keep one permit
 * while the user completes it, without launching requests to every other source
 * at the same time.
 */
internal class AggregateSearchLimiter(
    permits: Int,
) {
    private val semaphore = Semaphore(permits.coerceAtLeast(1))

    suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}

/**
 * Starts lightweight child coroutines in source order, while [limiter] bounds
 * the number that may execute provider work simultaneously. awaitAll preserves
 * input order, so existing result interleaving remains stable.
 */
internal suspend fun <T, R> boundedProviderMap(
    values: List<T>,
    limiter: AggregateSearchLimiter,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    values
        .map { value ->
            async {
                limiter.withPermit {
                    transform(value)
                }
            }
        }
        .awaitAll()
}
