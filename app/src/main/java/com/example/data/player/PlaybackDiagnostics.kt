package com.example.data.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.example.BuildConfig

/**
 * Event-only logcat trace for intermittent restart/focus reports.
 * Call with a Player only on its application thread. Never log stream URLs,
 * request headers, book titles or exception messages here.
 */
internal fun tracePlaybackEvent(
    event: String,
    player: Player? = null,
    details: String = "",
) {
    if (!BuildConfig.DEBUG) return

    val snapshot = if (player == null) {
        ""
    } else {
        " player=${System.identityHashCode(player)}" +
            " state=${player.playbackState}" +
            " ready=${player.playWhenReady} playing=${player.isPlaying}" +
            " suppression=${player.playbackSuppressionReason}" +
            " repeat=${player.repeatMode} shuffle=${player.shuffleModeEnabled}" +
            " chapter=${player.currentMediaItemIndex} count=${player.mediaItemCount}" +
            " position=${player.currentPosition} duration=${player.duration}"
    }
    Log.i("AbredPlaybackTrace", "t=${SystemClock.elapsedRealtime()} event=$event$snapshot $details")
}
