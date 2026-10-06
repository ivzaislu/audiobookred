package com.example.data.player

/**
 * Allows one automatic remote-detail refresh for an errored playback identity.
 *
 * The gate is deliberately provider-agnostic so current and future live sources
 * get the same stale-URL recovery behavior without adding source-specific code.
 * Local downloaded media is excluded: a local file failure must never trigger a
 * network refresh. Successful playback or switching book/source re-arms the gate.
 */
internal class PlaybackRefreshRecoveryGate {
    private var observedPlaybackIdentity: String? = null
    private var attemptedForObservedIdentity = false

    fun shouldRecover(
        bookId: String?,
        sourceCode: String?,
        isPlaying: Boolean,
        isLocalFile: Boolean,
        error: String?,
    ): Boolean {
        val normalizedBookId = bookId?.trim()?.takeIf(String::isNotBlank)
        val normalizedSource = sourceCode.orEmpty().trim().lowercase()
        val playbackIdentity = normalizedBookId?.let { "$it|$normalizedSource" }

        if (playbackIdentity != observedPlaybackIdentity) {
            observedPlaybackIdentity = playbackIdentity
            attemptedForObservedIdentity = false
        }
        if (isPlaying) {
            attemptedForObservedIdentity = false
            return false
        }
        if (normalizedBookId == null ||
            isLocalFile ||
            error.isNullOrBlank() ||
            attemptedForObservedIdentity
        ) {
            return false
        }
        attemptedForObservedIdentity = true
        return true
    }
}
