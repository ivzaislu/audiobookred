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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.GeneralSettingsViewModel

/** Top-level settings for the standalone APK. */
@Composable
internal fun StandaloneSettingsScreen(
    vm: GeneralSettingsViewModel,
    onStorage: () -> Unit,
    onPlayer: () -> Unit,
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
        item { SettingsHeader() }

        item { SettingsTitle("Разделы", compactTop = true) }
        item {
            StandaloneNavigationCard(
                title = "Хранилище",
                subtitle = "Кэш, локальные данные и скачанные книги",
                icon = Icons.Default.Storage,
                onClick = onStorage,
            )
        }
        item {
            StandaloneNavigationCard(
                title = "Плеер",
                subtitle = "Скорость, перемотка и сохранение позиции",
                icon = Icons.Default.Headphones,
                onClick = onPlayer,
            )
        }
        item {
            StandaloneNavigationCard(
                title = "RuTracker и TorrServe",
                subtitle = "Логины, пароли и адрес torrent-сервера",
                icon = Icons.Default.Link,
                onClick = onExternalServices,
            )
        }
        item {
            StandaloneNavigationCard(
                title = "Данные и резервная копия",
                subtitle = "Экспорт и восстановление библиотеки, прогресса и настроек",
                icon = Icons.Default.ImportExport,
                onClick = { showDataSettings = true },
            )
        }

        item { SettingsTitle("Внешний вид") }
        item {
            SettingsCard {
                SettingsCardHeader(
                    icon = Icons.Default.Palette,
                    title = "Тема приложения",
                    subtitle = "Три независимых режима оформления",
                )
                Spacer(Modifier.height(AbredSpacing.Sm))
                ThemeModeSelector(
                    selected = settings.themeMode,
                    onSelected = vm::setThemeMode,
                )
            }
        }

        item { SettingsTitle("Интерфейс") }
        item {
            StandaloneSwitchCard(
                title = "Закреплять нижнюю панель",
                subtitle = "Показывать навигацию на экранах книги, цикла и подборок.",
                icon = Icons.Default.ViewAgenda,
                checked = settings.pinBottomNavigation,
                onChecked = vm::setPinBottomNavigation,
            )
        }

        item { SettingsTitle("О приложении") }
        item {
            AboutAppCard(
                onCheckUpdates = { updateHost?.checkForUpdatesManually() },
                canCheckUpdates = updateHost != null,
            )
        }

        item { SettingsTitle("Сервис") }
        item {
            SettingsCard {
                SettingsCardHeader(
                    icon = Icons.Default.RestartAlt,
                    title = "Сбросить настройки",
                    subtitle = "Вернуть параметры приложения к значениям по умолчанию. Данные библиотеки не удаляются.",
                    error = true,
                )
                Spacer(Modifier.height(AbredSpacing.Sm))
                Button(
                    onClick = vm::resetSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(AbredSizes.IconSmall))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text("Сбросить настройки")
                }
            }
        }

            item { Spacer(Modifier.height(AbredSpacing.Md)) }
        }
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
