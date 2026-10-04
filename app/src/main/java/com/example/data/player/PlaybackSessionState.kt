package com.example.data.player

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Pure Media3 session-state checks shared by the UI-side player facade.
 * Keeping these rules outside AudiobookPlayerManager makes recovery decisions
 * consistent across toggle(), polling and async cache recovery.
 */
internal fun hasUsablePlaybackMedia(
    mediaItemCount: Int,
    hasCurrentMediaItem: Boolean,
    currentMediaItemIndex: Int,
): Boolean =
    mediaItemCount > 0 &&
        hasCurrentMediaItem &&
        currentMediaItemIndex != C.INDEX_UNSET

internal fun playbackSessionNeedsRecovery(
    mediaItemCount: Int,
    hasCurrentMediaItem: Boolean,
    currentMediaItemIndex: Int,
    playbackState: Int,
    hasPlayerError: Boolean,
): Boolean =
    !hasUsablePlaybackMedia(
        mediaItemCount = mediaItemCount,
        hasCurrentMediaItem = hasCurrentMediaItem,
        currentMediaItemIndex = currentMediaItemIndex,
    ) ||
        playbackState == Player.STATE_IDLE ||
        playbackState == Player.STATE_ENDED ||
        hasPlayerError

/**
 * Replaces the current Media3 playlist while preserving the outgoing book first.
 *
 * PlaybackService also persists normal stop/pause callbacks, but a book-to-book
 * switch must not depend on callback ordering around stop()/clearMediaItems().
 * Persisting the current controller position synchronously before the swap keeps
 * A -> B -> A resume deterministic even when the two books use different sources.
 * The next book's speed is still applied only after its media items are installed.
 */
internal inline fun replacePlaybackMedia(
    persistCurrent: () -> Unit,
    stopCurrent: () -> Unit,
    clearCurrent: () -> Unit,
    setNextMedia: () -> Unit,
    applyNextSpeed: () -> Unit,
    prepareNext: () -> Unit,
) {
    persistCurrent()
    stopCurrent()
    clearCurrent()
    setNextMedia()
    applyNextSpeed()
    prepareNext()
}
