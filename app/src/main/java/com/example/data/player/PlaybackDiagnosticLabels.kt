package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player

/** Keep numeric SDK values and add an event-specific name; never infer a human action from a seek alone. */
@OptIn(UnstableApi::class)
internal object PlaybackDiagnosticLabels {
    fun enrich(event: String, fields: MutableMap<String, String>) {
        fields["state"]?.toIntOrNull()?.let { state ->
            fields["stateName"] = when (state) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown"
            }
        }
        val reason = fields["reason"]?.toIntOrNull() ?: return
        fields["reasonName"] = when (event) {
            "position_discontinuity" -> when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "auto_transition"
                Player.DISCONTINUITY_REASON_SEEK -> "seek"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "seek_adjustment"
                Player.DISCONTINUITY_REASON_SKIP -> "skip"
                Player.DISCONTINUITY_REASON_REMOVE -> "remove"
                Player.DISCONTINUITY_REASON_INTERNAL -> "internal"
                else -> "unknown"
            }
            "media_transition" -> when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto_transition"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
                else -> "unknown"
            }
            "timeline" -> when (reason) {
                Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> "playlist_changed"
                Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE -> "source_update"
                else -> "unknown"
            }
            "play_when_ready" -> when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user_request"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end_of_item"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed_too_long"
                else -> "unknown"
            }
            "suppression" -> when (reason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "transient_focus_loss"
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "unsuitable_output"
                else -> "unknown"
            }
            else -> "unknown"
        }
    }
}
