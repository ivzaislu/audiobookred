package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.local.DownloadBookEntity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredStroke
import com.example.ui.theme.BookDetailDesign

@Composable
internal fun CompactDownloadAction(
    download: DownloadBookEntity?,
    busy: Boolean,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    enabled: Boolean = true,
) {
    val action: (() -> Unit)? = when {
        busy -> null
        download == null -> onStart
        download.state == "paused" -> onResume
        download.state == "failed" -> onRetry
        else -> null
    }
    val icon = when {
        download?.state == "completed" -> Icons.Default.CheckCircle
        download?.state == "downloading" || download?.state == "queued" -> Icons.Default.DownloadForOffline
        download?.state == "paused" -> Icons.Default.PlayArrow
        download?.state == "failed" -> Icons.Default.Refresh
        else -> Icons.Default.Download
    }
    val description = when {
        busy -> "Подготовка загрузки"
        download == null -> "Скачать на устройство"
        download.state == "completed" -> "Книга скачана"
        download.state == "downloading" || download.state == "queued" -> "Книга скачивается"
        download.state == "paused" -> "Продолжить загрузку"
        download.state == "failed" -> "Повторить загрузку"
        else -> "Загрузка"
    }
    val completed = download?.state == "completed"

    Button(
        onClick = { action?.invoke() },
        enabled = enabled && action != null,
        modifier = Modifier.size(AbredSizes.ControlHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(AbredSpacing.None),
        colors = if (completed) {
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(BookDetailDesign.DownloadSpinnerSize),
                strokeWidth = AbredStroke.Spinner,
                color = LocalContentColor.current,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        } else {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(AbredSizes.Icon),
            )
        }
    }
}

