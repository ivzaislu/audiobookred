package com.example.data.player

/**
 * Holds controller actions while MediaController is connecting.
 * Actions are generation-scoped so work queued before a profile switch cannot
 * mutate the controller after the new profile becomes active.
 */
internal class PlaybackControllerActionQueue<T> {
    private data class Entry<T>(
        val generation: Long,
        val action: (T) -> Unit,
    )

    private val entries = mutableListOf<Entry<T>>()

    fun enqueue(generation: Long, action: (T) -> Unit) {
        entries += Entry(generation, action)
    }

    fun drain(currentGeneration: Long): List<(T) -> Unit> {
        if (entries.isEmpty()) return emptyList()
        val actions = entries
            .asSequence()
            .filter { it.generation == currentGeneration }
            .map { it.action }
            .toList()
        entries.clear()
        return actions
    }

    fun clear() {
        entries.clear()
    }

    internal fun size(): Int = entries.size
}
