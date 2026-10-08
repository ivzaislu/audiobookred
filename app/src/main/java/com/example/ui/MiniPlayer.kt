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
internal fun MiniPlayer(
    state: PlayerUiState,
    showProgressPercent: Boolean,
    simplifyChapterTitles: Boolean,
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
                            chapterDisplayLabel(
                                title = chapter?.title,
                                chapterIndex = state.chapterIndex,
                                simplify = simplifyChapterTitles,
                            ),
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
