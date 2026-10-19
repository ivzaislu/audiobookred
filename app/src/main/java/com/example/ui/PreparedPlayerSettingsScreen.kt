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
import com.example.ui.theme.SettingsDesign
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
        verticalArrangement = Arrangement.spacedBy(SettingsDesign.CompactItemSpacing),
    ) {
        item(key = "player-settings-header") {
            AbredSubpageHeader(
                title = "Плеер",
                onBack = onBack,
            )
        }

        item {
            PlayerSettingsSwitch(
                title = "Умная перемотка после паузы",
                subtitle = "После долгой паузы немного возвращаться назад.",
                checked = settings.smartRewindAfterPause,
                onCheckedChange = vm::setSmartRewindAfterPause,
            )
        }

        item { AbredInnerSettingsSectionTitle("Главы", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }
        item {
            PlayerSettingsSwitch(
                title = "Короткие названия глав",
                subtitle = "В плеере и мини-плеере: «Глава 1», «Глава 2» вместо исходных названий.",
                checked = settings.simplifyChapterTitles,
                onCheckedChange = vm::setSimplifyChapterTitles,
            )
        }

        item {
            PlayerSettingsSwitch(
                title = "Следующая глава автоматически",
                subtitle = "Продолжать воспроизведение после окончания главы.",
                checked = settings.autoNextChapter,
                onCheckedChange = vm::setAutoNextChapter,
            )
        }

        item { AbredInnerSettingsSectionTitle("Скорость", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }
        item {
            PlayerChoiceCard(
                title = "Скорость по умолчанию",
                subtitle = "Для книг без сохранённой скорости.",
                selected = settings.defaultSpeed,
                options = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f),
                optionLabel = ::playerSpeedLabel,
                onSelected = vm::setDefaultSpeed,
            )
        }
        item {
            PlayerSettingsSwitch(
                title = "Помнить скорость книги",
                subtitle = "Восстанавливать при возвращении к книге.",
                checked = settings.rememberBookSpeed,
                onCheckedChange = vm::setRememberBookSpeed,
            )
        }
        item { AbredInnerSettingsSectionTitle("Перемотка", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }
        item {
            PlayerChoiceCard(
                title = "Назад",
                subtitle = null,
                selected = settings.rewindSeconds,
                options = listOf(5, 10, 15, 30),
                optionLabel = { it.toString() + " сек" },
                onSelected = vm::setRewindSeconds,
            )
        }
        item {
            PlayerChoiceCard(
                title = "Вперёд",
                subtitle = null,
                selected = settings.forwardSeconds,
                options = listOf(10, 15, 30, 60),
                optionLabel = { it.toString() + " сек" },
                onSelected = vm::setForwardSeconds,
            )
        }

        item { AbredInnerSettingsSectionTitle("Прогресс", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }
        item {
            PlayerSettingsSwitch(
                title = "Показывать процент прослушанного",
                subtitle = "Процент и полоска прогресса в интерфейсе.",
                checked = settings.showProgressPercent,
                onCheckedChange = vm::setShowProgressPercent,
            )
        }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}

