package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.BookDetailDto
import com.example.data.model.PersonDto
import com.example.data.player.PlaybackSleepTimerStore
import com.example.data.player.PlayerUiState
import com.example.data.player.SleepTimerMode
import com.example.data.player.SleepTimerState
import com.example.data.player.UndoSeekPoint
import com.example.data.player.estimateOverallProgressPercent
import com.example.data.settings.PlayerSettingsStore
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.PlaybackPreparationUiState
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PlayerScreen(
    state: PlayerUiState,
    preparation: PlaybackPreparationUiState,
    rewindSeconds: Int,
    forwardSeconds: Int,
    onAddBookmark: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPreviousChapter: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNextChapter: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onPlayChapter: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
    onSeries: (String) -> Unit,
) {
    val book = state.book
    val ruTrackerBook = book?.let(::isRuTrackerBook) == true
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var consumedUndoExpiry by remember { mutableLongStateOf(-1L) }
    val chaptersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val sleepTimerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val speedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current
    val sleepTimerStore = remember(context) { PlaybackSleepTimerStore(context) }
    val playerSettingsStore = remember(context) { PlayerSettingsStore(context) }
    val sleepTimer by sleepTimerStore.state.collectAsState()
    val playerSettings by playerSettingsStore.state.collectAsState()
    val overall = playerOverallProgressPercent(state)
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()

    DisposableEffect(sleepTimerStore, playerSettingsStore) {
        onDispose {
            sleepTimerStore.close()
            playerSettingsStore.close()
        }
    }

    BackHandler(enabled = showSpeedSheet) { showSpeedSheet = false }
    BackHandler(enabled = showSleepTimerSheet && !showSpeedSheet) { showSleepTimerSheet = false }
    BackHandler(enabled = showChaptersSheet && !showSleepTimerSheet && !showSpeedSheet) { showChaptersSheet = false }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val landscape = maxWidth > maxHeight
        val tiny = maxHeight < 650.dp
        val horizontalPadding = if (tiny) AbredSpacing.Sm else AbredSpacing.Lg
        val undo = state.undoSeek?.takeIf {
            it.chapterIndex == state.chapterIndex && it.expiresAtElapsedMs != consumedUndoExpiry
        }
        val playerError = state.error?.takeIf { it.isNotBlank() }
        val hasTransientOverlay =
            (preparation.active && book != null) || playerError != null || undo != null || state.isLoading

        val layoutState = PlayerLayoutState(
            player = state,
            preparation = preparation,
            rewindSeconds = rewindSeconds,
            forwardSeconds = forwardSeconds,
            sleepTimer = sleepTimer,
            skipSilenceEnabled = playerSettings.skipSilenceEnabled,
            simplifyChapterTitles = playerSettings.simplifyChapterTitles,
            overall = overall,
            largeText = largeText,
            extraLargeText = extraLargeText,
            maxHeight = maxHeight,
            ruTrackerBook = ruTrackerBook,
        )
        val layoutActions = PlayerLayoutActions(
            onSeekTo = onSeekTo,
            onPreviousChapter = onPreviousChapter,
            onSeekBy = onSeekBy,
            onTogglePlayback = onTogglePlayback,
            onNextChapter = onNextChapter,
            onOpenChapters = { showChaptersSheet = true },
            onOpenSpeed = { showSpeedSheet = true },
            onOpenSleepTimer = { showSleepTimerSheet = true },
            onToggleSilence = {
                playerSettingsStore.setSkipSilenceEnabled(!playerSettings.skipSilenceEnabled)
            },
            onAddBookmark = onAddBookmark,
            onBack = onBack,
            onOpenBook = onOpenBook,
            onAuthor = onAuthor,
            onNarrator = onNarrator,
            onSeries = onSeries,
        )

        if (landscape && book != null) {
            PlayerLandscapeContent(
                layoutState = layoutState,
                actions = layoutActions,
                hasTransientOverlay = hasTransientOverlay,
            )
        } else {
            PlayerPortraitContent(
                layoutState = layoutState,
                actions = layoutActions,
            )
        }

        PlayerTransientOverlay(
            modifier = Modifier
                .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .fillMaxWidth(if (landscape) 0.58f else 1f)
                .padding(
                    start = if (landscape) AbredSpacing.Sm else horizontalPadding,
                    end = horizontalPadding,
                    bottom = if (landscape) AbredSpacing.Xs else {
                        AbredSizes.PlayerOverlayBottomClearance
                    },
                ),
            preparation = preparation,
            hasBook = book != null,
            playerError = playerError,
            undo = undo,
            state = state,
            ruTrackerBook = ruTrackerBook,
            largeText = largeText,
            onUndo = {
                consumedUndoExpiry = it.expiresAtElapsedMs
                onSeekBy(it.positionMs - state.positionMs)
            },
        )
    }

    if (showChaptersSheet && book != null) {
        PlayerChaptersSheet(
            book = book,
            currentChapterIndex = state.chapterIndex,
            isPlaying = state.isPlaying,
            simplifyChapterTitles = playerSettings.simplifyChapterTitles,
            largeText = largeText,
            sheetState = chaptersSheetState,
            onDismiss = { showChaptersSheet = false },
            onPlayChapter = { index ->
                onPlayChapter(index)
                showChaptersSheet = false
            },
        )
    }

    if (showSpeedSheet && book != null) {
        PlayerSpeedSheet(
            speed = state.speed,
            extraLargeText = extraLargeText,
            sheetState = speedSheetState,
            onDismiss = { showSpeedSheet = false },
            onSetPlaybackSpeed = { speed ->
                onSetPlaybackSpeed(speed)
                showSpeedSheet = false
            },
        )
    }

    if (showSleepTimerSheet && book != null) {
        PlayerSleepTimerSheet(
            book = book,
            chapterIndex = state.chapterIndex,
            sleepTimer = sleepTimer,
            sleepTimerStore = sleepTimerStore,
            sheetState = sleepTimerSheetState,
            onDismiss = { showSleepTimerSheet = false },
        )
    }
}
