package com.example.util

import kotlinx.coroutines.CancellationException

/**
 * Result wrapper for suspend work that preserves structured coroutine cancellation.
 * Ordinary Exceptions remain available to existing UI/data fallback handling.
 */
suspend inline fun <T> runCatchingCancellable(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
