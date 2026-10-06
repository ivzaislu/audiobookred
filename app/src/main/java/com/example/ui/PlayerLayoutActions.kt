package com.example.ui

import com.example.data.model.PersonDto

internal class PlayerLayoutActions(
    val onSeekTo: (Long) -> Unit,
    val onPreviousChapter: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onTogglePlayback: () -> Unit,
    val onNextChapter: () -> Unit,
    val onOpenChapters: () -> Unit,
    val onOpenSpeed: () -> Unit,
    val onOpenSleepTimer: () -> Unit,
    val onToggleSilence: () -> Unit,
    val onAddBookmark: () -> Unit,
    val onBack: () -> Unit,
    val onOpenBook: (String) -> Unit,
    val onAuthor: (PersonDto) -> Unit,
    val onNarrator: (PersonDto) -> Unit,
    val onSeries: (String) -> Unit,
)
