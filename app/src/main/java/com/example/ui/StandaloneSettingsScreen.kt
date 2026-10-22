package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.GeneralSettingsViewModel

/** Top-level settings for the standalone APK. */
@Composable
internal fun StandaloneSettingsScreen(
    vm: GeneralSettingsViewModel,
    onStorage: () -> Unit,
    onHome: () -> Unit,
    onPlayer: () -> Unit,
    onReader: () -> Unit,
    onSources: () -> Unit,
    onExternalServices: () -> Unit,
) {
    var showDataSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showDataSettings) { showDataSettings = false }
    if (showDataSettings) {
        StandaloneDataSettingsScreen(
            vm = vm,
            onBack = { showDataSettings = false },
        )
        return
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val updateHost = LocalContext.current.findMainActivity()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = AbredSpacing.ScreenHorizontal,
                end = AbredSpacing.ScreenHorizontal,
                top = AbredSpacing.ScreenVertical,
                bottom = AbredSpacing.Lg,
            ),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
        ) {
            item { SettingsHeader(compact = true) }

            item { SettingsTitle("Приложение", compactTop = true) }
            item {
                SettingsCard {
                    StandaloneNavigationRow(
                        title = "Главная",
                        subtitle = "Разделы и подборки",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.Home,
                        onClick = onHome,
                    )
                    StandaloneNavigationRow(
                        title = "Плеер",
                        subtitle = "Скорость и перемотка",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.Headphones,
                        onClick = onPlayer,
                    )
                    StandaloneNavigationRow(
                        title = "Чтение и озвучка",
                        subtitle = "Оформление текста, голос и произношение",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.MenuBook,
                        onClick = onReader,
                    )
                }
            }

            item { SettingsGroupDivider() }
            item { SettingsTitle("Каталог и сервисы", compactTop = true) }
            item {
                SettingsCard {
                    StandaloneNavigationRow(
                        title = "Источники",
                        subtitle = "Источники каталога и поиска",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.Hub,
                        onClick = onSources,
                    )
                    StandaloneNavigationRow(
                        title = "RuTracker и TorrServe",
                        subtitle = "Вход и адрес torrent-сервера",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.Link,
                        onClick = onExternalServices,
                    )
                }
            }

            item { SettingsGroupDivider() }
            item { SettingsTitle("Данные", compactTop = true) }
            item {
                SettingsCard {
                    StandaloneNavigationRow(
                        title = "Хранилище",
                        subtitle = "Кэш и скачанные книги",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.Storage,
                        onClick = onStorage,
                    )
                    StandaloneNavigationRow(
                        title = "Резервная копия",
                        subtitle = "Экспорт и восстановление данных",
                        chevronOpacity = AbredOpacity.Chevron,
                        icon = Icons.Default.ImportExport,
                        onClick = { showDataSettings = true },
                    )
                }
            }

            item { SettingsGroupDivider() }
            item { SettingsTitle("Интерфейс", compactTop = true) }
            item {
                ThemeModeSelector(
                    selected = settings.themeMode,
                    onSelected = vm::setThemeMode,
                )
            }
            item {
                StandaloneSwitchCard(
                    title = "Закреплять нижнюю панель",
                    subtitle = "Навигация на экранах книг, циклов и подборок",
                    trailingControl = true,
                    checked = settings.pinBottomNavigation,
                    onChecked = vm::setPinBottomNavigation,
                )
            }

            item { SettingsGroupDivider() }
            item { SettingsTitle("О приложении", compactTop = true) }
            item {
                AboutAppCard(
                    onCheckUpdates = { updateHost?.checkForUpdatesManually() },
                    canCheckUpdates = updateHost != null,
                )
            }

            item { SettingsGroupDivider() }
            item { SettingsTitle("Диагностика", compactTop = true) }
            item { DiagnosticsSettingsSection() }

            item { SettingsGroupDivider() }
            item { SettingsTitle("Сервис", compactTop = true) }
            item {
                StandaloneActionRow(
                    title = "Сбросить настройки",
                    subtitle = "Восстановить исходные параметры. Библиотека и прогресс сохранятся.",
                    icon = Icons.Default.RestartAlt,
                    onClick = vm::resetSettings,
                    destructive = true,
                    showIcon = false,
                )
            }

            item { Spacer(Modifier.height(AbredSpacing.Md)) }
        }
    }
}

/** Quiet separation between settings groups, without individual row borders. */
@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = AbredSpacing.Xs),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.SectionOutline),
    )
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

