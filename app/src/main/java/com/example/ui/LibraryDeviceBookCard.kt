package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.local.DownloadBookEntity
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSpacing

@Composable
internal fun LibraryDeviceBookCard(
    item: DownloadBookEntity,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val metrics = libraryBookRowTextMetrics()
    val largeText = metrics.largeText
    val total = item.totalSizeBytes
    val fraction = total
        ?.takeIf { it > 0L }
        ?.let { (item.downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    val sourceLabel = item.sourceCode
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.let(::sourceDisplayLabel)
        .orEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = item.state == "completed",
                onClickLabel = "Открыть скачанную книгу",
                onClick = onOpen,
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = abredBookCardBorder(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryBookRowMainContent(
                coverUrl = item.coverUrl,
                sourceLabel = sourceLabel,
                sourceReserve = metrics.sourceReserve,
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = metrics.titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    libraryDownloadStatusLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (largeText) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                fraction?.let {
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    AbredBookProgress(
                        progressPercent = it.toDouble() * 100.0,
                    )
                }
                item.error.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (item.state) {
                    "completed" -> IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, "Слушать")
                    }
                    "downloading", "queued" -> IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, "Пауза")
                    }
                    "paused" -> IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, "Продолжить загрузку")
                    }
                    "failed" -> IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, "Повторить")
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        "Удалить с устройства",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
