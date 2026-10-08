package com.example.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

internal data class PlayerPlaybackControlsStyle(
    val chapterTopSpacing: Dp,
    val chapterHorizontalPadding: Dp,
    val chapterVerticalPadding: Dp,
    val chapterMaxLines: Int,
    val timelineTopSpacing: Dp,
    val transportTopSpacing: Dp,
    val quickActionsTopSpacing: Dp,
    val bottomSpacing: Dp,
    val playSize: Dp,
    val seekSize: Dp,
    val compactIcons: Boolean,
    val stackedQuickActions: Boolean,
)

@Composable
internal fun PlayerPlaybackControls(
    layoutState: PlayerLayoutState,
    actions: PlayerLayoutActions,
    style: PlayerPlaybackControlsStyle,
) {
    val state = layoutState.player
    val chapterTitle = state.book
        ?.chapters
        ?.getOrNull(state.chapterIndex)
        ?.title

    Spacer(Modifier.height(style.chapterTopSpacing))
    PlayerChapterSelector(
        chapterTitle = chapterTitle,
        chapterIndex = state.chapterIndex,
        simplifyChapterTitles = layoutState.simplifyChapterTitles,
        horizontalPadding = style.chapterHorizontalPadding,
        verticalPadding = style.chapterVerticalPadding,
        maxLines = style.chapterMaxLines,
        onOpen = actions.onOpenChapters,
    )

    Spacer(Modifier.height(style.timelineTopSpacing))
    PlayerTimeline(
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        onSeekTo = actions.onSeekTo,
    )

    Spacer(Modifier.height(style.transportTopSpacing))
    PlayerTransportControls(
        rewindSeconds = layoutState.rewindSeconds,
        forwardSeconds = layoutState.forwardSeconds,
        isPlaying = state.isPlaying,
        playSize = style.playSize,
        seekSize = style.seekSize,
        compactIcons = style.compactIcons,
        onPreviousChapter = actions.onPreviousChapter,
        onSeekBy = actions.onSeekBy,
        onTogglePlayback = actions.onTogglePlayback,
        onNextChapter = actions.onNextChapter,
    )

    Spacer(Modifier.height(style.quickActionsTopSpacing))
    PlayerQuickActions(
        speed = state.speed,
        sleepTimer = layoutState.sleepTimer,
        skipSilenceEnabled = layoutState.skipSilenceEnabled,
        stacked = style.stackedQuickActions,
        onSpeed = actions.onOpenSpeed,
        onSleepTimer = actions.onOpenSleepTimer,
        onToggleSilence = actions.onToggleSilence,
        onAddBookmark = actions.onAddBookmark,
    )

    Spacer(Modifier.height(style.bottomSpacing))
}
