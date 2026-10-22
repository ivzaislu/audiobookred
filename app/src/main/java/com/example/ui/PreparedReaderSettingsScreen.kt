package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.settings.ReaderAppearance
import com.example.data.settings.ReaderFont
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.SettingsDesign
import com.example.ui.viewmodel.PlayerSettingsViewModel
import com.example.ui.viewmodel.ReaderSettingsViewModel

/** Main preferences live alongside Player settings; the book only exposes listening controls. */
@Composable
internal fun PreparedReaderSettingsScreen(onBack: () -> Unit, onBeforeListen: () -> Unit) {
    BackHandler(onBack = onBack)
    val vm: ReaderSettingsViewModel = hiltViewModel()
    val playerVm: PlayerSettingsViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playerSettings by playerVm.settings.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.ScreenVertical),
        verticalArrangement = Arrangement.spacedBy(SettingsDesign.CompactItemSpacing),
    ) {
        item { AbredSubpageHeader(title = "Чтение и озвучка", onBack = onBack) }
        item { AbredInnerSettingsSectionTitle("Текст", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }
        item {
            PlayerChoiceCard("Размер текста", "Применяется ко всем текстовым книгам.", settings.fontSize,
                listOf(12, 14, 16, 18, 20, 22, 24, 28, 32), { it.toString() }, vm::setFontSize)
        }
        item {
            PlayerChoiceCard("Шрифт", null, settings.font, ReaderFont.entries.toList(),
                { if (it == ReaderFont.SERIF) "С засечками" else "Без засечек" }, vm::setFont)
        }
        item {
            PlayerChoiceCard("Межстрочный интервал", null, settings.lineSpacing, listOf(1.2f, 1.5f, 1.8f, 2f),
                { it.toString() }, vm::setLineSpacing)
        }
        item { PlayerSettingsSwitch("Широкие поля", null, settings.wideMargins, vm::setWideMargins) }
        item {
            PlayerChoiceCard("Фон чтения", "Не меняет оформление остальных экранов.", settings.appearance,
                ReaderAppearance.entries.toList(), { when (it) {
                    ReaderAppearance.APP -> "Как в приложении"
                    ReaderAppearance.LIGHT -> "Светлый"
                    ReaderAppearance.DARK -> "Тёмный"
                    ReaderAppearance.SEPIA -> "Сепия"
                } }, vm::setAppearance)
        }
        item { AbredInnerSettingsSectionTitle("Озвучка", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }
        item {
            PlayerSettingsSwitch("Следить за озвучкой",
                "Автоматически прокручивать текст к озвучиваемому фрагменту во всех книгах. Закладка чтения сохраняется отдельно.",
                settings.followNarration, vm::setFollowNarration)
        }
        item { Text("Движок озвучки: RuVoice TTS. Все книги озвучиваются офлайн только через RuVoice.") }
        item { RuVoiceInstallSection() }
        item { RuVoiceVoiceSelector() }
        item {
            PlayerChoiceCard("Скорость по умолчанию", "Общая с плеером; сохранённая скорость книги имеет приоритет.",
                playerSettings.defaultSpeed, listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), ::playerSpeedLabel, playerVm::setDefaultSpeed)
        }
        item { PlayerSettingsSwitch("Помнить скорость книги", null, playerSettings.rememberBookSpeed, playerVm::setRememberBookSpeed) }
        item {
            Text("Словари ударений, ё, интонация, паузы и дополнительные профили теперь настраиваются в RuVoice.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}
