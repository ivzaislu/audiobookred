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


internal fun chapterDisplayLabel(title: String?, chapterIndex: Int): String {
    val fallback = "Глава ${chapterIndex + 1}"
    val clean = title?.trim().orEmpty()
    if (clean.isBlank()) return fallback
    return if (clean.startsWith("Глава", ignoreCase = true)) clean else "$fallback · $clean"
}

internal fun sleepTimerQuickLabel(timer: SleepTimerState): String = when (timer.mode) {
    SleepTimerMode.OFF -> "Таймер"
    SleepTimerMode.END_OF_CHAPTER -> "До главы"
    SleepTimerMode.MINUTES -> {
        val remainingMinutes = ((timer.remainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
        "$remainingMinutes мин"
    }
}

internal fun sleepTimerStatusLabel(timer: SleepTimerState): String = when (timer.mode) {
    SleepTimerMode.OFF -> "Плеер остановится автоматически в выбранный момент."
    SleepTimerMode.END_OF_CHAPTER -> "Активен: остановка в конце текущей главы."
    SleepTimerMode.MINUTES -> {
        val remainingMinutes = ((timer.remainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
        "Активен: осталось примерно $remainingMinutes мин."
    }
}

internal fun speedLabel(speed: Float): String {
    val rounded = if (abs(speed - speed.roundToInt()) < 0.01f) {
        speed.roundToInt().toString()
    } else {
        "%.2f".format(speed).trimEnd('0').trimEnd('.')
    }
    return "${rounded}×"
}

internal fun playerOverallProgressPercent(state: PlayerUiState): Double {
    val book = state.book ?: return 0.0
    return estimateOverallProgressPercent(
        book = book,
        chapterIndex = state.chapterIndex,
        positionMs = state.positionMs,
        currentDurationMs = state.durationMs,
    )
}

internal fun playerRemainingLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "−00:00"
    return "−${playerFormatMillis((durationMs - positionMs).coerceAtLeast(0L))}"
}

internal fun playerFormatSeconds(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

internal fun playerFormatMillis(ms: Long): String = playerFormatSeconds(ms.coerceAtLeast(0L) / 1000L)
