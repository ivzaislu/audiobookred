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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerChaptersSheet(
    book: BookDetailDto,
    currentChapterIndex: Int,
    isPlaying: Boolean,
    simplifyChapterTitles: Boolean,
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
                                    chapterDisplayLabel(
                                        title = item.title,
                                        chapterIndex = index,
                                        simplify = simplifyChapterTitles,
                                    ),
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
