package com.example.ui.viewmodel

import com.example.data.backup.UserDataRestoreGate
import com.example.domain.playback.PreparedPlayback

internal suspend fun executePreparedPlayback(
    prepare: suspend () -> PreparedPlayback?,
    preparationEpoch: Long = UserDataRestoreGate.currentPlaybackWriteEpoch(),
    isCurrent: () -> Boolean = { true },
    play: (PreparedPlayback) -> Unit,
): Boolean {
    if (!isCurrent()) return false
    val prepared = prepare()?.copy(playbackWriteEpoch = preparationEpoch) ?: return false
    // Preparation may suspend for network/Room work. A completed book, another
    // request, a pause or a seek can invalidate recovery while that work is running.
    if (!isCurrent() || !UserDataRestoreGate.playbackWriteEpochCurrent(preparationEpoch)) return false
    play(prepared)
    return true
}
