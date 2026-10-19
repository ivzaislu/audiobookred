package com.example.data.player

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticLabelsTest {
    @Test fun sameNumericReasonIsDecodedAccordingToEvent() {
        val seek = mutableMapOf("reason" to Player.DISCONTINUITY_REASON_SEEK.toString())
        PlaybackDiagnosticLabels.enrich("position_discontinuity", seek)
        assertEquals("seek", seek["reasonName"])
        val transition = mutableMapOf("reason" to Player.MEDIA_ITEM_TRANSITION_REASON_AUTO.toString())
        PlaybackDiagnosticLabels.enrich("media_transition", transition)
        assertEquals("auto_transition", transition["reasonName"])
    }

    @Test fun audioFocusLossIsNotLabelledAsManualPause() {
        val fields = mutableMapOf("reason" to Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS.toString(),
            "state" to Player.STATE_READY.toString())
        PlaybackDiagnosticLabels.enrich("play_when_ready", fields)
        assertEquals("audio_focus_loss", fields["reasonName"])
        assertEquals("ready", fields["stateName"])
    }

    @Test fun unknownValuesRemainExplicitlyUnknown() {
        val fields = mutableMapOf("reason" to "9876", "state" to "9876")
        PlaybackDiagnosticLabels.enrich("media_transition", fields)
        assertEquals("unknown", fields["reasonName"])
        assertEquals("unknown", fields["stateName"])
        assertEquals("9876", fields["reason"])
    }
}
