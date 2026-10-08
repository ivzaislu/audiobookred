package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.PlayerSettingsViewModel

/** Standalone player settings. No profile/sync UI is compiled into this screen. */
@Composable
internal fun PreparedPlayerSettingsScreen(
    onBack: () -> Unit,
) {
    val vm: PlayerSettingsViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "player-settings-header") {
            AbredSubpageHeader(
                title = "Плеер",
                onBack = onBack,
            )
        }

        item { AbredSettingsSectionTitle("Для аудиокниг") }
        item {
            PlayerSettingsSwitch(
                title = "Умная перемотка после паузы",
                subtitle = "После долгой паузы немного отматывать назад, чтобы легче вспомнить контекст.",
                checked = settings.smartRewindAfterPause,
                onCheckedChange = vm::setSmartRewindAfterPause,
            )
        }

        item { AbredSettingsSectionTitle("Главы") }
        item {
            PlayerSettingsSwitch(
                title = "Короткие названия глав",
                subtitle = "Показывать в мини-плеере и плеере «Глава 1», «Глава 2» и так далее вместо исходных названий.",
                checked = settings.simplifyChapterTitles,
                onCheckedChange = vm::setSimplifyChapterTitles,
            )
        }

        item { AbredSettingsSectionTitle("Скорость") }
        item {
            PlayerChoiceCard(
                title = "Скорость по умолчанию",
                subtitle = "Используется для книг без собственной сохранённой скорости.",
                selected = settings.defaultSpeed,
                options = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f),
                optionLabel = ::playerSpeedLabel,
                onSelected = vm::setDefaultSpeed,
            )
        }
        item {
            PlayerSettingsSwitch(
                title = "Помнить скорость для каждой книги",
                subtitle = "Восстанавливать скорость вместе с локальной позицией прослушивания.",
                checked = settings.rememberBookSpeed,
                onCheckedChange = vm::setRememberBookSpeed,
            )
        }
        item {
            PlayerSettingsSwitch(
                title = "Автоматически включать следующую главу",
                subtitle = "После окончания главы сразу продолжать воспроизведение.",
                checked = settings.autoNextChapter,
                onCheckedChange = vm::setAutoNextChapter,
            )
        }

        item { AbredSettingsSectionTitle("Перемотка") }
        item {
            PlayerChoiceCard(
                title = "Назад",
                subtitle = "Шаг кнопки перемотки назад.",
                selected = settings.rewindSeconds,
                options = listOf(5, 10, 15, 30),
                optionLabel = { it.toString() + " сек" },
                onSelected = vm::setRewindSeconds,
            )
        }
        item {
            PlayerChoiceCard(
                title = "Вперёд",
                subtitle = "Шаг кнопки перемотки вперёд.",
                selected = settings.forwardSeconds,
                options = listOf(10, 15, 30, 60),
                optionLabel = { it.toString() + " сек" },
                onSelected = vm::setForwardSeconds,
            )
        }

        item { AbredSettingsSectionTitle("Прогресс") }
        item {
            PlayerSettingsSwitch(
                title = "Показывать процент прослушанного",
                subtitle = "Отображать процент и полоску прогресса в интерфейсе.",
                checked = settings.showProgressPercent,
                onCheckedChange = vm::setShowProgressPercent,
            )
        }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}
