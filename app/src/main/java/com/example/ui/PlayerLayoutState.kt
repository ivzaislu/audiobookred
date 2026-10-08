package com.example.ui

import androidx.compose.ui.unit.Dp
import com.example.data.player.PlayerUiState
import com.example.data.player.SleepTimerState
import com.example.ui.viewmodel.PlaybackPreparationUiState

internal data class PlayerLayoutState(
    val player: PlayerUiState,
    val preparation: PlaybackPreparationUiState,
    val rewindSeconds: Int,
    val forwardSeconds: Int,
    val sleepTimer: SleepTimerState,
    val skipSilenceEnabled: Boolean,
    val simplifyChapterTitles: Boolean,
    val overall: Double,
    val largeText: Boolean,
    val extraLargeText: Boolean,
    val maxHeight: Dp,
    val ruTrackerBook: Boolean,
)
