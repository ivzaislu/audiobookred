package com.example.data.player

import kotlinx.coroutines.channels.Channel

/**
 * Memory-bounded async write queue that keeps only the newest pending value for
 * each logical key. A single consumer drains snapshots in insertion order while
 * producers remain non-blocking.
 *
 * This is intentionally not a DROP_OLDEST channel: replacing by key preserves the
 * newest checkpoint for every independently playing book/source instead of losing
 * whichever update happened to be oldest globally.
 */
internal class CoalescingWriteQueue<K : Any, V : Any>(
    private val keyOf: (V) -> K,
) {
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val pending = LinkedHashMap<K, V>()

    @Volatile
    private var closed = false

    @Synchronized
    fun offer(value: V): Boolean {
        if (closed) return false
        pending[keyOf(value)] = value
        wakeUp.trySend(Unit)
        return true
    }

    suspend fun nextBatch(): List<V>? {
        while (true) {
            val signal = wakeUp.receiveCatching()
            val batch = drainPending()
            if (batch.isNotEmpty()) return batch
            if (signal.isClosed) return null
        }
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        wakeUp.close()
    }

    @Synchronized
    private fun drainPending(): List<V> {
        if (pending.isEmpty()) return emptyList()
        return pending.values.toList().also { pending.clear() }
    }
}
