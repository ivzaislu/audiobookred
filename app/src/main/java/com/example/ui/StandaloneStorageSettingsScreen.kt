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
