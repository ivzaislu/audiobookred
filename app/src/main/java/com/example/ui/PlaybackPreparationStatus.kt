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
internal fun PlaybackPreparationStatus(
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
