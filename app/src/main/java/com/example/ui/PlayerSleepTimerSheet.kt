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
internal fun PlayerSleepTimerSheet(
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
