package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadBookEntity
import com.example.ui.theme.AbredSizes

@Composable
internal fun CompactDownloadAction(
    download: DownloadBookEntity?,
    busy: Boolean,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
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
        enabled = action != null,
        modifier = Modifier.size(AbredSizes.ControlHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
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
                modifier = Modifier.size(19.dp),
                strokeWidth = 2.dp,
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
