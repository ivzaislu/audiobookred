package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.StorageSettingsViewModel
import com.example.ui.viewmodel.StorageUiState

@Composable
internal fun StorageOverviewCard(state: StorageUiState) {
    AbredSettingsCard {
        Text(
            "Использование памяти",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(AbredSpacing.Sm))
        StorageMetric("Очищаемый кэш", formatStorageBytes(state.cacheBytes), emphasized = true)
        StorageMetric("Локальная библиотека", formatStorageBytes(state.profileBytes))
        StorageMetric("Аудиофайлы", formatStorageBytes(state.downloadedBytes))
        StorageMetric("Room + WAL", formatStorageBytes(state.databaseBytes))
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            "Кэш можно удалить без потери истории, прогресса, избранного, закладок и скачанных книг.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageMetric(label: String, value: String, emphasized: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = AbredSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            color = if (emphasized) {
                MaterialTheme.colorScheme.abredAccentText
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
