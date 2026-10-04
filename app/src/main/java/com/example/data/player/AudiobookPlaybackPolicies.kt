package com.example.data.player

/** Pure audiobook playback policies kept separate from Media3 plumbing for testing. */
internal fun smartRewindMsForPause(pauseDurationMs: Long): Long = when {
    pauseDurationMs < 30_000L -> 0L
    pauseDurationMs < 5 * 60_000L -> 5_000L
    pauseDurationMs < 30 * 60_000L -> 10_000L
    else -> 20_000L
}

internal fun shouldOfferUndoSeek(
    oldChapterIndex: Int,
    oldPositionMs: Long,
    newChapterIndex: Int,
    newPositionMs: Long,
): Boolean {
    if (oldChapterIndex != newChapterIndex) return true
    return kotlin.math.abs(newPositionMs - oldPositionMs) >= 30_000L
}
