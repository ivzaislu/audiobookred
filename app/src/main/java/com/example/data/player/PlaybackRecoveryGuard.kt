package com.example.data.player

/** Main-thread generation for an asynchronous recovery of the current playback. */
internal class PlaybackRecoveryGuard {
    private var generation = 0L

    fun capture(): Long = generation

    fun invalidate() {
        generation += 1L
    }

    fun isCurrent(request: Long): Boolean = request == generation
}
