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
internal fun PlayerQuickActions(
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
