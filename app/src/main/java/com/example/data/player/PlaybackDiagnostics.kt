package com.example.data.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import com.example.BuildConfig
import com.example.data.diagnostics.AppDiagnostics
import com.example.data.diagnostics.DiagnosticHttpPolicy
import com.example.data.source.StandaloneSourceRegistry

/**
 * Event-only logcat trace for intermittent restart/focus reports.
 * Call with a Player only on its application thread. Never log stream URLs,
 * request headers, book titles or exception messages here.
 */
@OptIn(UnstableApi::class)
internal fun tracePlaybackEvent(
    event: String,
    player: Player? = null,
    details: String = "",
) {
    val recording = AppDiagnostics.isRecording
    if (!BuildConfig.DEBUG && !recording) return
    if (recording) runCatching {
        val fields = Regex("([A-Za-z]+)=([^\\s]+)").findAll(details)
            .associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()
        if (player != null) {
            fields.putAll(mapOf(
                "state" to player.playbackState.toString(),
                "ready" to player.playWhenReady.toString(),
                "playing" to player.isPlaying.toString(),
                "suppression" to player.playbackSuppressionReason.toString(),
                "repeat" to player.repeatMode.toString(),
                "shuffle" to player.shuffleModeEnabled.toString(),
                "chapter" to player.currentMediaItemIndex.toString(),
                "count" to player.mediaItemCount.toString(),
                "position" to player.currentPosition.toString(),
                "duration" to player.duration.toString(),
                "durationKnown" to (player.duration != C.TIME_UNSET).toString(),
                "queueLast" to (player.mediaItemCount > 0 && player.currentMediaItemIndex == player.mediaItemCount - 1).toString(),
            ))
            val source = player.currentMediaItem?.mediaMetadata?.extras
                ?.getString(PlaybackMetadata.EXTRA_SOURCE_CODE)
            fields["source"] = StandaloneSourceRegistry.normalize(source)
                .takeIf(StandaloneSourceRegistry::isActive) ?: "other"
        }
        PlaybackDiagnosticLabels.enrich(event, fields)
        if (event == "player_error") {
            player?.playerError?.let { error ->
                fields["errorName"] = error.errorCodeName
                generateSequence<Throwable>(error) { it.cause }.take(8)
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
                        fields["status"] = it.responseCode.toString()
                        fields["resourceKey"] = DiagnosticHttpPolicy.resourceKey(it.dataSpec.uri.toString())
                        it.dataSpec.uri.host?.let { host -> fields["hostKey"] = DiagnosticHttpPolicy.hostKey(host) }
                    }
            }
        }
        AppDiagnostics.record(event, fields)
    }
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
