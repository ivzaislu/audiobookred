package com.example.data.parser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Coalesces concurrent work for the same key into one in-flight operation.
 *
 * The shared operation has its own supervisor scope, so cancellation of the
 * caller that happened to create the flight does not cancel unrelated callers
 * already waiting for the same provider result. Completed entries are removed
 * immediately, keeping the map bounded by operations that are actually active.
 */
internal class BazaKnigSingleFlight<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Flight<T>(
        val result: Deferred<T>,
    )

    private val inFlight = mutableMapOf<String, Flight<T>>()

    suspend fun run(key: String, block: suspend () -> T): T {
        val flight = synchronized(inFlight) {
            inFlight[key] ?: Flight(
                scope.async(start = CoroutineStart.LAZY) { block() }
            ).also { created ->
                inFlight[key] = created
                created.result.invokeOnCompletion {
                    synchronized(inFlight) {
                        if (inFlight[key] === created) inFlight.remove(key)
                    }
                }
            }
        }

        flight.result.start()
        return flight.result.await()
    }
}
