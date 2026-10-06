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
internal fun PlayerTransportControls(
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
