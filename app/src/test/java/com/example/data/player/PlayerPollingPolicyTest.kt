package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPollingPolicyTest {
    @Test
    fun activePlaybackUsesPositionTicker() {
        assertEquals(
            PlayerPollingMode.POSITION,
            playerPollingMode(
                isPlaying = true,
                sleepTimerMode = SleepTimerMode.OFF,
                hasUndoSeek = false,
            )
        )
    }

    @Test
    fun playingWinsOverTransientUiState() {
        assertEquals(
            PlayerPollingMode.POSITION,
            playerPollingMode(
                isPlaying = true,
                sleepTimerMode = SleepTimerMode.MINUTES,
                hasUndoSeek = true,
            )
        )
    }

    @Test
    fun pausedMinuteTimerUsesTransientTicker() {
        assertEquals(
            PlayerPollingMode.TRANSIENT,
            playerPollingMode(
                isPlaying = false,
                sleepTimerMode = SleepTimerMode.MINUTES,
                hasUndoSeek = false,
            )
        )
    }

    @Test
    fun pausedUndoWindowUsesTransientTicker() {
        assertEquals(
            PlayerPollingMode.TRANSIENT,
            playerPollingMode(
                isPlaying = false,
                sleepTimerMode = SleepTimerMode.OFF,
                hasUndoSeek = true,
            )
        )
    }

    @Test
    fun pausedIdlePlayerDoesNotPoll() {
        assertEquals(
            PlayerPollingMode.NONE,
            playerPollingMode(
                isPlaying = false,
                sleepTimerMode = SleepTimerMode.OFF,
                hasUndoSeek = false,
            )
        )
    }

    @Test
    fun endOfChapterTimerIsEventDrivenWhilePaused() {
        assertEquals(
            PlayerPollingMode.NONE,
            playerPollingMode(
                isPlaying = false,
                sleepTimerMode = SleepTimerMode.END_OF_CHAPTER,
                hasUndoSeek = false,
            )
        )
    }
}
