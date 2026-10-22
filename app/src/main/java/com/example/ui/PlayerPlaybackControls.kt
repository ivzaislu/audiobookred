package com.example.ui

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.example.data.narration.*

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val textBook = state.book?.selectedSource == TEXT_NARRATION_SOURCE
    val starts = remember(state.book?.chapters, textBook) { if (textBook) textChapterStarts(state.book!!.chapters.map { it.id }) else emptyList() }
    val logicalIndex = if (textBook) starts.indexOfLast { it <= state.chapterIndex }.coerceAtLeast(0) else state.chapterIndex
    val chapterTitle = state.book
        ?.chapters
        ?.getOrNull(state.chapterIndex)
        ?.title

    Spacer(Modifier.height(style.chapterTopSpacing))
    PlayerChapterSelector(
        chapterTitle = chapterTitle,
        chapterIndex = logicalIndex,
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

    if (textBook) Text("Время текущего отрывка · следующие готовятся во время прослушивания", style = MaterialTheme.typography.bodySmall)

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
        showBookmark = !textBook,
    )

    Spacer(Modifier.height(style.bottomSpacing))
}

