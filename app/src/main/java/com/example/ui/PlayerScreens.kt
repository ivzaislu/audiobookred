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

@Composable
internal fun TorrServerPreparationMiniPlayer(
    preparation: PlaybackPreparationUiState,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = AbredSizes.BottomBarContentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.Xs, vertical = AbredSpacing.Xxs)
            .semantics {
                contentDescription = buildString {
                    append(preparation.title.ifBlank { "TorrServer загрузка" })
                    if (preparation.detail.isNotBlank()) {
                        append(", ")
                        append(preparation.detail)
                    }
                    if (preparation.step > 0 && preparation.totalSteps > 0) {
                        append(", этап ")
                        append(preparation.step)
                        append(" из ")
                        append(preparation.totalSteps)
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AbredSpacing.Sm,
                    vertical = AbredSpacing.Sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preparation.ready) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.Icon),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(AbredSizes.Icon),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = preparation.title.ifBlank { "TorrServer загрузка" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preparation.detail.isNotBlank()) {
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Text(
                        text = preparation.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (preparation.step > 0 && preparation.totalSteps > 0) {
                Spacer(Modifier.width(AbredSpacing.Sm))
                Text(
                    text = "${preparation.step}/${preparation.totalSteps}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.abredAccentText,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun MiniPlayer(
    state: PlayerUiState,
    showProgressPercent: Boolean,
    onPreviousChapter: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNextChapter: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chapter = state.book?.chapters?.getOrNull(state.chapterIndex)
    val bookProgress = playerOverallProgressPercent(state)
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val progress = when {
        showProgressPercent && bookProgress > 0.0 -> (bookProgress / 100.0).toFloat()
        state.durationMs > 0 -> state.positionMs.toFloat() / state.durationMs.toFloat()
        else -> 0f
    }.coerceIn(0f, 1f)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = AbredSizes.BottomBarContentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.Xs, vertical = AbredSpacing.Xxs)
            .graphicsLayer { translationX = dragOffsetX }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragOffsetX += amount
                    },
                    onDragEnd = {
                        if (shouldDismissMiniPlayer(dragOffsetX)) onDismiss() else dragOffsetX = 0f
                    },
                    onDragCancel = { dragOffsetX = 0f },
                )
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Скрыть мини-плеер") {
                        onDismiss()
                        true
                    }
                )
            }
            .clickable(onClickLabel = "Открыть плеер", onClick = onOpen),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(
                    start = AbredSpacing.Sm,
                    top = AbredSpacing.Xxs,
                    end = AbredSpacing.Xs,
                    bottom = AbredSpacing.Xxs,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCoverImage(
                    state.book?.coverUrl,
                    null,
                    Modifier
                        .size(
                            width = AbredSizes.MiniPlayerCoverWidth,
                            height = AbredSizes.MiniPlayerCoverHeight,
                        )
                        .clip(MaterialTheme.shapes.medium),
                )
                Spacer(Modifier.width(AbredSpacing.Sm))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = AbredSpacing.Xxs),
                ) {
                    Text(
                        state.book?.title.orEmpty(),
                        maxLines = when {
                            extraLargeText -> 3
                            largeText -> 2
                            else -> 1
                        },
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chapterDisplayLabel(chapter?.title, state.chapterIndex),
                            modifier = Modifier.weight(1f),
                            maxLines = Int.MAX_VALUE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (showProgressPercent && bookProgress > 0.05) {
                            Spacer(Modifier.width(AbredSpacing.Xxs))
                            Text(
                                "${bookProgress.roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.abredAccentText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                IconButton(onClick = onPreviousChapter, modifier = Modifier.size(AbredSizes.MinimumTouchTarget)) {
                    Icon(Icons.Default.SkipPrevious, "Предыдущая глава", Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) "Пауза" else "Играть",
                    )
                }
                IconButton(onClick = onNextChapter, modifier = Modifier.size(AbredSizes.MinimumTouchTarget)) {
                    Icon(Icons.Default.SkipNext, "Следующая глава", Modifier.size(20.dp))
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(AbredSizes.MiniPlayerProgressTrack),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

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
    val chapter = book?.chapters?.getOrNull(state.chapterIndex)
    val ruTrackerBook = book?.let(::isRuTrackerBook) == true
    val primarySeries = if (ruTrackerBook) {
        null
    } else {
        book?.series?.firstOrNull { it.isPrimary } ?: book?.series?.firstOrNull()
    }
    var sliderValue by remember(state.positionMs) { mutableFloatStateOf(state.positionMs.toFloat()) }
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var consumedUndoExpiry by remember { mutableLongStateOf(-1L) }
    val chaptersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val sleepTimerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val speedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptics = LocalHapticFeedback.current
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
        val compact = maxHeight < 760.dp
        val horizontalPadding = if (tiny) AbredSpacing.Sm else AbredSpacing.Lg
        val coverWidth = when {
            extraLargeText -> 112.dp
            largeText -> 128.dp
            tiny -> 112.dp
            compact -> 144.dp
            else -> 176.dp
        }
        val sectionGap = if (tiny || largeText) {
            AbredSpacing.Xxs
        } else {
            AbredSpacing.Xs
        }
        val playSize = if (tiny && !largeText) 64.dp else 72.dp
        val seekSize = when {
            extraLargeText -> 60.dp
            largeText -> 56.dp
            tiny -> 48.dp
            else -> 52.dp
        }
        val undo = state.undoSeek?.takeIf {
            it.chapterIndex == state.chapterIndex && it.expiresAtElapsedMs != consumedUndoExpiry
        }
        val playerError = state.error?.takeIf { it.isNotBlank() }
        val hasTransientOverlay =
            (preparation.active && book != null) || playerError != null || undo != null || state.isLoading

        if (landscape && book != null) {
            val landscapeCoverWidth = when {
                extraLargeText -> 88.dp
                largeText -> 104.dp
                maxHeight < 400.dp -> 112.dp
                else -> 128.dp
            }
            val landscapePlaySize = 64.dp
            val landscapeSeekSize = AbredSizes.MinimumTouchTarget
            val landscapeInfoWeight = if (extraLargeText) 0.32f else 0.38f
            val landscapeControlsWeight = 1f - landscapeInfoWeight
            val landscapeOverlayReserve = when {
                !hasTransientOverlay -> 0.dp
                extraLargeText -> 120.dp
                largeText -> 88.dp
                else -> 64.dp
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = AbredSpacing.Sm,
                        end = AbredSpacing.Sm,
                        top = AbredSpacing.Xxs,
                        bottom = AbredSpacing.Xxs,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AbredSizes.MinimumTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Свернуть")
                    }
                    Text(
                        if (preparation.active) "Подготовка воспроизведения" else "Сейчас играет",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(AbredSizes.MinimumTouchTarget))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Sm),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(landscapeInfoWeight)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = AbredSpacing.Xxs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Card(
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier
                                .width(landscapeCoverWidth)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenBook(book.id)
                                    },
                                )
                                .clearAndSetSemantics {
                                    contentDescription = book.title
                                    customActions = listOf(
                                        CustomAccessibilityAction("Открыть книгу") {
                                            onOpenBook(book.id)
                                            true
                                        }
                                    )
                                },
                        ) {
                            BookCoverImage(
                                book.coverUrl,
                                book.title,
                                Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            )
                        }

                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                "${overall.roundToInt()}%",
                                modifier = Modifier.padding(
                                    horizontal = AbredSpacing.Xs,
                                    vertical = 2.dp,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = when {
                                extraLargeText -> Int.MAX_VALUE
                                largeText -> 4
                                else -> 2
                            },
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )

                        PlayerPeopleLine(
                            people = book.authors,
                            emptyText = "Автор не указан",
                            prefix = null,
                            tiny = true,
                            interactive = !ruTrackerBook,
                            onPerson = onAuthor,
                        )
                        if (book.narrators.isNotEmpty()) {
                            PlayerPeopleLine(
                                people = book.narrators,
                                emptyText = "",
                                prefix = "Читает: ",
                                tiny = true,
                                interactive = !ruTrackerBook,
                                onPerson = onNarrator,
                            )
                        }
                        primarySeries?.let { series ->
                            Text(
                                text = "${series.position?.let { "№$it · " }.orEmpty()}${series.name}",
                                modifier = Modifier
                                    .clickable(onClickLabel = "Открыть цикл") { onSeries(series.id) }
                                    .padding(horizontal = AbredSpacing.Xxs, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.abredAccentText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = when {
                                    extraLargeText -> Int.MAX_VALUE
                                    largeText -> 3
                                    else -> 1
                                },
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                    }

                    Column(
                        modifier = Modifier
                            .weight(landscapeControlsWeight)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(
                                end = AbredSpacing.Xxs,
                                bottom = landscapeOverlayReserve,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = "Открыть список глав") {
                                    showChaptersSheet = true
                                },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = AbredSpacing.Sm,
                                    vertical = AbredSpacing.Xxs,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(AbredSizes.IconSmall),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(AbredSpacing.Xs))
                                Text(
                                    chapterDisplayLabel(chapter?.title, state.chapterIndex),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = when {
                                        extraLargeText -> Int.MAX_VALUE
                                        largeText -> 3
                                        else -> 1
                                    },
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Slider(
                            value = sliderValue.coerceIn(
                                0f,
                                state.durationMs.coerceAtLeast(1).toFloat(),
                            ),
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { onSeekTo(sliderValue.toLong()) },
                            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                playerFormatMillis(state.positionMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                playerRemainingLabel(state.positionMs, state.durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        PlayerTransportControls(
                            rewindSeconds = rewindSeconds,
                            forwardSeconds = forwardSeconds,
                            isPlaying = state.isPlaying,
                            playSize = landscapePlaySize,
                            seekSize = landscapeSeekSize,
                            compactIcons = true,
                            onPreviousChapter = onPreviousChapter,
                            onSeekBy = onSeekBy,
                            onTogglePlayback = onTogglePlayback,
                            onNextChapter = onNextChapter,
                        )

                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        PlayerQuickActions(
                            speed = state.speed,
                            sleepTimer = sleepTimer,
                            skipSilenceEnabled = playerSettings.skipSilenceEnabled,
                            stacked = extraLargeText,
                            onSpeed = { showSpeedSheet = true },
                            onSleepTimer = { showSleepTimerSheet = true },
                            onToggleSilence = {
                                playerSettingsStore.setSkipSilenceEnabled(
                                    !playerSettings.skipSilenceEnabled
                                )
                            },
                            onAddBookmark = onAddBookmark,
                        )
                        Spacer(Modifier.height(AbredSpacing.Xs))
                    }
                }
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = AbredSpacing.Xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = AbredSizes.MinimumTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(AbredSizes.MinimumTouchTarget)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Свернуть")
                }
                Text(
                    if (preparation.active) "Подготовка воспроизведения" else "Сейчас играет",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(AbredSizes.MinimumTouchTarget))
            }

            if (book == null) {
                Box(
                    Modifier.fillMaxWidth().height(360.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preparation.active) {
                        PlaybackPreparationStatus(
                            preparation = preparation,
                            prominent = true,
                        )
                    } else {
                        Text(
                            "Нет активной книги",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Column
            }

            Spacer(Modifier.height(AbredSpacing.Xxs))
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = if (tiny) 2.dp else 6.dp),
                modifier = Modifier
                    .width(coverWidth)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenBook(book.id)
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = book.title
                        customActions = listOf(
                            CustomAccessibilityAction("Открыть книгу") {
                                onOpenBook(book.id)
                                true
                            }
                        )
                    },
            ) {
                BookCoverImage(
                    book.coverUrl,
                    book.title,
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }

            Spacer(Modifier.height(if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    "${overall.roundToInt()}% прослушано",
                    modifier = Modifier.padding(horizontal = AbredSpacing.Sm, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs))
            Text(
                book.title,
                style = if (tiny) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = when {
                    extraLargeText -> 4
                    largeText -> 3
                    tiny -> 2
                    else -> 2
                },
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(AbredSpacing.Xxs))
            PlayerPeopleLine(
                people = book.authors,
                emptyText = "Автор не указан",
                prefix = null,
                tiny = tiny,
                interactive = !ruTrackerBook,
                onPerson = onAuthor,
            )
            if (book.narrators.isNotEmpty()) {
                PlayerPeopleLine(
                    people = book.narrators,
                    emptyText = "",
                    prefix = "Читает: ",
                    tiny = tiny,
                    interactive = !ruTrackerBook,
                    onPerson = onNarrator,
                )
            }
            primarySeries?.let { series ->
                Text(
                    text = "${series.position?.let { "№$it · " }.orEmpty()}${series.name}",
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(onClickLabel = "Открыть цикл") { onSeries(series.id) }
                        .padding(horizontal = AbredSpacing.Xxs, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.abredAccentText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (largeText) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(sectionGap))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Открыть список глав") { showChaptersSheet = true },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AbredSpacing.Md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(AbredSizes.IconSmall),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text(
                        chapterDisplayLabel(chapter?.title, state.chapterIndex),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs))
            Slider(
                value = sliderValue.coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSeekTo(sliderValue.toLong()) },
                valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    playerFormatMillis(state.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    playerRemainingLabel(state.positionMs, state.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs))
            PlayerTransportControls(
                rewindSeconds = rewindSeconds,
                forwardSeconds = forwardSeconds,
                isPlaying = state.isPlaying,
                playSize = playSize,
                seekSize = seekSize,
                compactIcons = tiny,
                onPreviousChapter = onPreviousChapter,
                onSeekBy = onSeekBy,
                onTogglePlayback = onTogglePlayback,
                onNextChapter = onNextChapter,
            )

            Spacer(Modifier.height(if (tiny || largeText) AbredSpacing.Xs else AbredSpacing.Sm))
            PlayerQuickActions(
                speed = state.speed,
                sleepTimer = sleepTimer,
                skipSilenceEnabled = playerSettings.skipSilenceEnabled,
                stacked = false,
                onSpeed = { showSpeedSheet = true },
                onSleepTimer = { showSleepTimerSheet = true },
                onToggleSilence = {
                    playerSettingsStore.setSkipSilenceEnabled(!playerSettings.skipSilenceEnabled)
                },
                onAddBookmark = onAddBookmark,
            )

            Spacer(Modifier.height(AbredSpacing.Sm))
        }
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

@Composable
private fun PlayerTransientOverlay(
    modifier: Modifier,
    preparation: PlaybackPreparationUiState,
    hasBook: Boolean,
    playerError: String?,
    undo: UndoSeekPoint?,
    state: PlayerUiState,
    ruTrackerBook: Boolean,
    largeText: Boolean,
    onUndo: (UndoSeekPoint) -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            preparation.active && hasBook -> {
                PlaybackPreparationStatus(
                    preparation = preparation,
                    prominent = false,
                )
            }

            playerError != null -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = AbredSpacing.Md,
                            vertical = AbredSpacing.Sm,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(AbredSizes.IconSmall),
                        )
                        Spacer(Modifier.width(AbredSpacing.Sm))
                        Text(
                            playerError,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (largeText) 4 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            undo != null -> {
                Button(
                    onClick = { onUndo(undo) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                ) {
                    Icon(Icons.Default.Undo, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text(
                        "Вернуться к ${playerFormatMillis(undo.positionMs)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            state.isLoading -> {
                if (ruTrackerBook) {
                    PlaybackPreparationStatus(
                        preparation = PlaybackPreparationUiState(
                            active = true,
                            title = "TorrServer загрузка",
                            detail = "Буферизация аудиопотока",
                            step = 3,
                            totalSteps = 4,
                        ),
                        prominent = false,
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = AbredSpacing.Md,
                                vertical = AbredSpacing.Sm,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(AbredSizes.IconSmall),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(Modifier.width(AbredSpacing.Sm))
                            Text(
                                "Загрузка…",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChaptersSheet(
    book: BookDetailDto,
    currentChapterIndex: Int,
    isPlaying: Boolean,
    largeText: Boolean,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onPlayChapter: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AbredSpacing.Lg, vertical = AbredSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Главы",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${book.chapters.size} · сейчас ${currentChapterIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Закрыть")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f),
                contentPadding = PaddingValues(
                    start = AbredSpacing.Sm,
                    end = AbredSpacing.Sm,
                    bottom = AbredSpacing.Xl,
                ),
            ) {
                itemsIndexed(book.chapters, key = { _, item -> item.id }) { index, item ->
                    val selected = index == currentChapterIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AbredSpacing.Xxs)
                            .semantics { this.selected = selected }
                            .clickable(onClickLabel = "Воспроизвести главу") {
                                onPlayChapter(index)
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        border = if (selected) {
                            null
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        },
                    ) {
                        Row(
                            Modifier.padding(
                                horizontal = AbredSpacing.Sm,
                                vertical = AbredSpacing.Xs,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (selected && isPlaying) {
                                        Icons.Default.GraphicEq
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                            Spacer(Modifier.width(AbredSpacing.Xs))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title.ifBlank { "Глава ${index + 1}" },
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = if (largeText) 3 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.durationSeconds > 0) {
                                    Text(
                                        playerFormatSeconds(item.durationSeconds),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSpeedSheet(
    speed: Float,
    extraLargeText: Boolean,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        PlayerSheetContent(
            icon = Icons.Default.Speed,
            title = "Скорость",
            subtitle = "Сейчас ${speedLabel(speed)} · применяется сразу",
            onClose = onDismiss,
        ) {
            val speedRows = if (extraLargeText) {
                listOf(
                    listOf(0.75f, 1f),
                    listOf(1.25f, 1.5f),
                    listOf(1.75f, 2f),
                )
            } else {
                listOf(
                    listOf(0.75f, 1f, 1.25f),
                    listOf(1.5f, 1.75f, 2f),
                )
            }
            speedRows.forEach { rowSpeeds ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                ) {
                    rowSpeeds.forEach { presetSpeed ->
                        PlayerSpeedPreset(
                            speed = presetSpeed,
                            selected = abs(speed - presetSpeed) < 0.01f,
                            onClick = { onSetPlaybackSpeed(presetSpeed) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSleepTimerSheet(
    book: BookDetailDto,
    chapterIndex: Int,
    sleepTimer: SleepTimerState,
    sleepTimerStore: PlaybackSleepTimerStore,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        PlayerSheetContent(
            icon = Icons.Default.Bedtime,
            title = "Таймер сна",
            subtitle = sleepTimerStatusLabel(sleepTimer),
            onClose = onDismiss,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            ) {
                listOf(15, 30).forEach { minutes ->
                    OutlinedButton(
                        onClick = {
                            sleepTimerStore.setMinutes(minutes)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AbredSizes.MinimumTouchTarget),
                    ) {
                        Text(
                            "$minutes мин",
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            ) {
                listOf(45, 60).forEach { minutes ->
                    OutlinedButton(
                        onClick = {
                            sleepTimerStore.setMinutes(minutes)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AbredSizes.MinimumTouchTarget),
                    ) {
                        Text(
                            "$minutes мин",
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
            Button(
                onClick = {
                    sleepTimerStore.setEndOfChapter(
                        bookId = book.id,
                        sourceCode = book.selectedSource.takeIf { it.isNotBlank() },
                        chapterIndex = chapterIndex,
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AbredSizes.MinimumTouchTarget),
            ) {
                Icon(Icons.Default.QueueMusic, null, Modifier.size(AbredSizes.IconSmall))
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text(
                    "До конца текущей главы",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = Int.MAX_VALUE,
                )
            }
            if (sleepTimer.mode != SleepTimerMode.OFF) {
                TextButton(
                    onClick = {
                        sleepTimerStore.clear()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AbredSizes.MinimumTouchTarget),
                ) {
                    Icon(Icons.Default.TimerOff, null, Modifier.size(AbredSizes.IconSmall))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text(
                        "Выключить таймер",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = Int.MAX_VALUE,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSheetContent(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val heightFraction = when {
            extraLargeText -> 0.78f
            largeText -> 0.84f
            else -> 0.90f
        }
        val horizontalPadding = if (extraLargeText) {
            AbredSpacing.Sm
        } else {
            AbredSpacing.Lg
        }
        val verticalGap = if (extraLargeText) {
            AbredSpacing.Xs
        } else {
            AbredSpacing.Sm
        }

        Column(
            modifier = Modifier
                .widthIn(max = AbredSizes.PlayerSheetContentMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * heightFraction)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = if (extraLargeText) AbredSpacing.Md else AbredSpacing.Xl,
                ),
            verticalArrangement = Arrangement.spacedBy(verticalGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = AbredSpacing.Xs)
                        .size(AbredSizes.Icon),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(AbredSpacing.Sm))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = AbredSpacing.Xxs),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = Int.MAX_VALUE,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = Int.MAX_VALUE,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
                ) {
                    Icon(Icons.Default.Close, "Закрыть")
                }
            }

            content()
        }
    }
}

@Composable
private fun PlayerTransportControls(
    rewindSeconds: Int,
    forwardSeconds: Int,
    isPlaying: Boolean,
    playSize: Dp,
    seekSize: Dp,
    compactIcons: Boolean,
    onPreviousChapter: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = onPreviousChapter,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(Icons.Default.SkipPrevious, "Предыдущая глава", Modifier.size(28.dp))
        }
        PlayerSeekButton(
            seconds = rewindSeconds,
            backwards = true,
            size = seekSize,
            onClick = { onSeekBy(-rewindSeconds * 1_000L) },
        )
        FilledIconButton(
            onClick = onTogglePlayback,
            modifier = Modifier.size(playSize),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Пауза" else "Играть",
                Modifier.size(if (compactIcons) 34.dp else 40.dp),
            )
        }
        PlayerSeekButton(
            seconds = forwardSeconds,
            backwards = false,
            size = seekSize,
            onClick = { onSeekBy(forwardSeconds * 1_000L) },
        )
        IconButton(
            onClick = onNextChapter,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(Icons.Default.SkipNext, "Следующая глава", Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PlayerQuickActions(
    speed: Float,
    sleepTimer: SleepTimerState,
    skipSilenceEnabled: Boolean,
    stacked: Boolean,
    onSpeed: () -> Unit,
    onSleepTimer: () -> Unit,
    onToggleSilence: () -> Unit,
    onAddBookmark: () -> Unit,
) {
    @Composable
    fun RowScope.SpeedAction() {
        PlayerQuickAction(
            icon = null,
            textIcon = speedLabel(speed),
            label = "Скорость",
            active = abs(speed - 1f) > 0.01f,
            modifier = Modifier.weight(1f),
            onClick = onSpeed,
        )
    }

    @Composable
    fun RowScope.SleepAction() {
        PlayerQuickAction(
            icon = Icons.Default.Bedtime,
            textIcon = null,
            label = sleepTimerQuickLabel(sleepTimer),
            active = sleepTimer.mode != SleepTimerMode.OFF,
            modifier = Modifier.weight(1f),
            onClick = onSleepTimer,
        )
    }

    @Composable
    fun RowScope.SilenceAction() {
        PlayerQuickAction(
            icon = Icons.Default.GraphicEq,
            textIcon = null,
            label = "Тишина",
            active = skipSilenceEnabled,
            modifier = Modifier.weight(1f),
            onClick = onToggleSilence,
        )
    }

    @Composable
    fun RowScope.BookmarkAction() {
        PlayerQuickAction(
            icon = Icons.Default.BookmarkAdd,
            textIcon = null,
            label = "Закладка",
            active = false,
            modifier = Modifier.weight(1f),
            onClick = onAddBookmark,
        )
    }

    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                verticalAlignment = Alignment.Top,
            ) {
                SpeedAction()
                SleepAction()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                verticalAlignment = Alignment.Top,
            ) {
                SilenceAction()
                BookmarkAction()
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalAlignment = Alignment.Top,
        ) {
            SpeedAction()
            SleepAction()
            SilenceAction()
            BookmarkAction()
        }
    }
}

@Composable
private fun PlayerSeekButton(
    seconds: Int,
    backwards: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(size).clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (backwards) Icons.Default.Replay else {
                        if (seconds == 30) Icons.Default.Forward30 else Icons.Default.FastForward
                    },
                    contentDescription = if (backwards) "Назад $seconds секунд" else "Вперёд $seconds секунд",
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (backwards) "−$seconds" else "+$seconds",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PlayerQuickAction(
    icon: ImageVector?,
    textIcon: String?,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            contentColor = contentColor,
            border = if (active) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = if (largeText) 54.dp else AbredSizes.MinimumTouchTarget,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
                    textIcon != null -> Text(
                        textIcon,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.abredAccentText else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = when {
                extraLargeText -> 3
                largeText -> 2
                else -> 1
            },
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RowScope.PlayerSpeedPreset(
    speed: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val extraLargeText = abredExtraLargeFontScale()
    val buttonPadding = PaddingValues(
        horizontal = if (extraLargeText) AbredSpacing.Xxs else AbredSpacing.Xs,
        vertical = AbredSpacing.Xs,
    )

    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = AbredSizes.MinimumTouchTarget)
                .semantics { this.selected = true },
            contentPadding = buttonPadding,
        ) {
            Text(
                speedLabel(speed),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = AbredSizes.MinimumTouchTarget)
                .semantics { this.selected = false },
            contentPadding = buttonPadding,
        ) {
            Text(
                speedLabel(speed),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PlaybackPreparationStatus(
    preparation: PlaybackPreparationUiState,
    prominent: Boolean,
) {
    Surface(
        modifier = if (prominent) {
            Modifier.widthIn(max = 360.dp).fillMaxWidth()
        } else {
            Modifier.fillMaxWidth()
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (prominent) 3.dp else 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AbredSpacing.Md,
                vertical = if (prominent) AbredSpacing.Md else AbredSpacing.Sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preparation.ready) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Готово к воспроизведению",
                    modifier = Modifier.size(if (prominent) 30.dp else AbredSizes.IconSmall),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (prominent) 30.dp else AbredSizes.IconSmall),
                    strokeWidth = if (prominent) 3.dp else 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    preparation.title.ifBlank { "TorrServer" },
                    style = if (prominent) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
                if (preparation.detail.isNotBlank()) {
                    Text(
                        preparation.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preparation.step > 0 && preparation.totalSteps > 0) {
                    Text(
                        "Этап ${preparation.step} из ${preparation.totalSteps}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.abredAccentText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerPeopleLine(
    people: List<PersonDto>,
    emptyText: String,
    prefix: String?,
    tiny: Boolean,
    interactive: Boolean = true,
    onPerson: (PersonDto) -> Unit,
) {
    val largeText = abredLargeFontScale()
    if (people.isEmpty()) {
        if (emptyText.isNotBlank()) {
            Text(
                emptyText,
                style = if (tiny) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeText) 2 else 1,
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefix?.let {
            Text(
                it,
                modifier = Modifier.clearAndSetSemantics { },
                style = if (tiny) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        people.forEachIndexed { index, person ->
            if (index > 0) {
                Text(
                    ", ",
                    modifier = Modifier.clearAndSetSemantics { },
                    style = if (tiny) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val personModifier = if (interactive) {
                Modifier
                    .clickable(onClickLabel = "Открыть") { onPerson(person) }
                    .padding(horizontal = AbredSpacing.Xxs, vertical = 2.dp)
            } else {
                Modifier.padding(horizontal = AbredSpacing.Xxs, vertical = 2.dp)
            }
            Text(
                person.name,
                modifier = personModifier,
                style = if (tiny) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun chapterDisplayLabel(title: String?, chapterIndex: Int): String {
    val fallback = "Глава ${chapterIndex + 1}"
    val clean = title?.trim().orEmpty()
    if (clean.isBlank()) return fallback
    return if (clean.startsWith("Глава", ignoreCase = true)) clean else "$fallback · $clean"
}

private fun sleepTimerQuickLabel(timer: SleepTimerState): String = when (timer.mode) {
    SleepTimerMode.OFF -> "Таймер"
    SleepTimerMode.END_OF_CHAPTER -> "До главы"
    SleepTimerMode.MINUTES -> {
        val remainingMinutes = ((timer.remainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
        "$remainingMinutes мин"
    }
}

private fun sleepTimerStatusLabel(timer: SleepTimerState): String = when (timer.mode) {
    SleepTimerMode.OFF -> "Плеер остановится автоматически в выбранный момент."
    SleepTimerMode.END_OF_CHAPTER -> "Активен: остановка в конце текущей главы."
    SleepTimerMode.MINUTES -> {
        val remainingMinutes = ((timer.remainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
        "Активен: осталось примерно $remainingMinutes мин."
    }
}

private fun speedLabel(speed: Float): String {
    val rounded = if (abs(speed - speed.roundToInt()) < 0.01f) {
        speed.roundToInt().toString()
    } else {
        "%.2f".format(speed).trimEnd('0').trimEnd('.')
    }
    return "${rounded}×"
}

private fun playerOverallProgressPercent(state: PlayerUiState): Double {
    val book = state.book ?: return 0.0
    return estimateOverallProgressPercent(
        book = book,
        chapterIndex = state.chapterIndex,
        positionMs = state.positionMs,
        currentDurationMs = state.durationMs,
    )
}

private fun playerRemainingLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "−00:00"
    return "−${playerFormatMillis((durationMs - positionMs).coerceAtLeast(0L))}"
}

private fun playerFormatSeconds(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun playerFormatMillis(ms: Long): String = playerFormatSeconds(ms.coerceAtLeast(0L) / 1000L)
