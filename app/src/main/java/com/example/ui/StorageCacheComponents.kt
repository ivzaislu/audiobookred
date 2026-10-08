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
internal fun CacheControlCard(
    state: StorageUiState,
    cacheEnabled: Boolean,
    onClear: () -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Очистка кэша",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Страницы ${formatStorageBytes(state.roomCacheBytes)} · обложки ${formatStorageBytes(state.posterCacheBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading || state.clearing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            if (cacheEnabled) {
                "Кэш заполняется автоматически при просмотре каталога, поиске и открытии книг."
            } else {
                "Кэширование выключено. Уже сохранённые данные можно удалить вручную."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AbredSpacing.Sm))
        Button(
            onClick = onClear,
            enabled = !state.clearing && state.cacheBytes > 0L,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(if (state.clearing) "Очищаю кэш…" else "Очистить ${formatStorageBytes(state.cacheBytes)}")
        }
        state.message?.let {
            Spacer(Modifier.height(AbredSpacing.Xs))
            StatusSurface(it, error = false)
        }
        state.error?.let {
            Spacer(Modifier.height(AbredSpacing.Xs))
            StatusSurface(it, error = true)
        }
    }
}
