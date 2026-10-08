package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadBookEntity
import com.example.ui.theme.AbredSpacing

/**
 * Compact download row inspired by a file/download manager rather than a book card.
 * Download state, byte progress and controls stay visible without making every item
 * look like a large catalog card.
 */
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
    val total = item.totalSizeBytes
    val fraction = total
        ?.takeIf { it > 0L }
        ?.let { (item.downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    val sourceLabel = item.sourceName
        .trim()
        .takeIf { it.isNotEmpty() }
        ?: item.sourceCode.trim().takeIf { it.isNotEmpty() }?.let(::sourceDisplayLabel).orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = item.state == "completed",
                onClickLabel = "Открыть скачанную книгу",
                onClick = onOpen,
            )
            .padding(vertical = AbredSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbredBookCover(
            model = item.coverUrl,
            modifier = Modifier
                .width(52.dp)
                .height(78.dp),
        )

        Spacer(Modifier.width(AbredSpacing.Sm))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (sourceLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(3.dp))
            Text(
                text = libraryDownloadStatusLabel(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == "failed") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.state != "completed") {
                fraction?.let {
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    AbredBookProgress(
                        progressPercent = it.toDouble() * 100.0,
                        showPercentLabel = true,
                    )
                }
            }

            item.error.takeIf { it.isNotBlank() }?.let { error ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(AbredSpacing.Xs))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (item.state) {
                "completed" -> IconButton(onClick = onPlay) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Слушать",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                "downloading", "queued" -> IconButton(onClick = onPause) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Пауза",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                "paused" -> IconButton(onClick = onResume) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Продолжить загрузку",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                "failed" -> IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Повторить",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Удалить с устройства",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
