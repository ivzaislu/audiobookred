package com.example.data.player

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionStateTest {
    @Test
    fun usableMediaRequiresCountCurrentItemAndValidIndex() {
        assertTrue(hasUsablePlaybackMedia(1, true, 0))
        assertFalse(hasUsablePlaybackMedia(0, true, 0))
        assertFalse(hasUsablePlaybackMedia(1, false, 0))
        assertFalse(hasUsablePlaybackMedia(1, true, C.INDEX_UNSET))
    }

    @Test
    fun activeReadySessionDoesNotNeedRecovery() {
        assertFalse(
            playbackSessionNeedsRecovery(
                mediaItemCount = 2,
                hasCurrentMediaItem = true,
                currentMediaItemIndex = 1,
                playbackState = Player.STATE_READY,
                hasPlayerError = false,
            )
        )
    }

    @Test
    fun missingMediaNeedsRecovery() {
        assertTrue(
            playbackSessionNeedsRecovery(
                mediaItemCount = 0,
                hasCurrentMediaItem = false,
                currentMediaItemIndex = C.INDEX_UNSET,
                playbackState = Player.STATE_READY,
                hasPlayerError = false,
            )
        )
    }

    @Test
    fun idleOrEndedSessionNeedsRecovery() {
        assertTrue(
            playbackSessionNeedsRecovery(
                mediaItemCount = 1,
                hasCurrentMediaItem = true,
                currentMediaItemIndex = 0,
                playbackState = Player.STATE_IDLE,
                hasPlayerError = false,
            )
        )
        assertTrue(
            playbackSessionNeedsRecovery(
                mediaItemCount = 1,
                hasCurrentMediaItem = true,
                currentMediaItemIndex = 0,
                playbackState = Player.STATE_ENDED,
                hasPlayerError = false,
            )
        )
    }

    @Test
    fun playerErrorNeedsRecoveryEvenWhenMediaIsUsable() {
        assertTrue(
            playbackSessionNeedsRecovery(
                mediaItemCount = 1,
                hasCurrentMediaItem = true,
                currentMediaItemIndex = 0,
                playbackState = Player.STATE_READY,
                hasPlayerError = true,
            )
        )
    }

    @Test
    fun outgoingBookIsPersistedBeforeMediaIsReplacedAndNewSpeedApplied() {
        val events = mutableListOf<String>()

        replacePlaybackMedia(
            persistCurrent = { events += "persist-old" },
            stopCurrent = { events += "stop-old" },
            clearCurrent = { events += "clear-old" },
            setNextMedia = { events += "set-new-media" },
            applyNextSpeed = { events += "apply-new-speed" },
            prepareNext = { events += "prepare-new" },
        )

        assertEquals(
            listOf(
                "persist-old",
                "stop-old",
                "clear-old",
                "set-new-media",
                "apply-new-speed",
                "prepare-new",
            ),
            events,
        )
    }
}
