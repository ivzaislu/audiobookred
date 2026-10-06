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
internal fun LocalUserDataCard(state: StorageUiState) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(Icons.Default.LibraryBooks)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    "Библиотека на устройстве",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatStorageBytes(state.profileBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(AbredSpacing.Sm))
        DataCountRow("Избранное", state.favoriteBooks.toString(), "История", state.historyBooks.toString())
        Spacer(Modifier.height(AbredSpacing.Xs))
        DataCountRow("Прогресс", "${state.progressBooks} книг", "Закладки", "${state.bookmarkBooks} книг")
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            "Это пользовательские данные, а не кэш. Очистка кэша их не затрагивает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DataCountRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        DataCountTile(leftLabel, leftValue, Modifier.weight(1f))
        DataCountTile(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun DataCountTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(AbredSpacing.Sm)) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
