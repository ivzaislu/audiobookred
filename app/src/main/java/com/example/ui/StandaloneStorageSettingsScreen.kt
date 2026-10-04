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

/** Storage controls for the standalone APK. */
@Composable
internal fun StandaloneStorageSettingsScreen(
    vm: StorageSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "storage-settings-header") {
            AbredSubpageHeader(
                title = "Хранилище",
                subtitle = "Кэш, локальные данные и скачанные аудиокниги",
                icon = Icons.Default.Storage,
                onBack = onBack,
            )
        }
        item { StorageOverviewCard(state) }

        item { AbredSettingsSectionTitle("Кэш") }
        item {
            LocalSwitchCard(
                title = "Кэшировать каталог и страницы",
                subtitle = "Быстрее открывать уже просмотренные экраны и карточки книг.",
                icon = Icons.Default.Cached,
                checked = settings.catalogCacheEnabled,
                onChecked = vm::setCatalogCacheEnabled,
            )
        }
        item { CacheControlCard(state, settings.catalogCacheEnabled, vm::clearCatalogCache) }

        item { AbredSettingsSectionTitle("Локальные данные") }
        item { LocalUserDataCard(state) }

        item { AbredSettingsSectionTitle("Скачанные книги") }
        item {
            LocalSwitchCard(
                title = "Только по Wi‑Fi",
                subtitle = "Ожидающие и новые загрузки не будут использовать мобильную сеть.",
                icon = Icons.Default.Wifi,
                checked = settings.downloadWifiOnly,
                onChecked = vm::setDownloadWifiOnly,
            )
        }
        item { DownloadStorageCard(state) }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}

@Composable
private fun StorageOverviewCard(state: StorageUiState) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(Icons.Default.PieChart)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Text(
                "Использование памяти",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
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

@Composable
private fun CacheControlCard(
    state: StorageUiState,
    cacheEnabled: Boolean,
    onClear: () -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(Icons.Default.DeleteSweep)
            Spacer(Modifier.width(AbredSpacing.Sm))
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

@Composable
private fun LocalUserDataCard(state: StorageUiState) {
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

@Composable
private fun DownloadStorageCard(state: StorageUiState) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(Icons.Default.DownloadForOffline)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    "Аудиофайлы",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${state.downloadedBooks} книг · ${formatStorageBytes(state.downloadedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            "Управление скачанными книгами находится в Библиотека → На устройстве. Очистка кэша аудиофайлы не удаляет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalSwitchCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(icon, active = checked)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun StatusSurface(message: String, error: Boolean) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
