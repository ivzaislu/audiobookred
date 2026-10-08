package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.settings.HOME_CACHE_DAY_OPTIONS
import com.example.data.settings.HomePopularDefaultPeriod
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.HomeSettingsViewModel

@Composable
internal fun PreparedHomeSettingsScreen(
    onBack: () -> Unit,
) {
    val vm: HomeSettingsViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item {
            AbredSubpageHeader(
                title = "Главная",
                onBack = onBack,
            )
        }

        item { AbredSettingsSectionTitle("Разделы") }

        item {
            AbredSettingsSwitchRow(
                title = "Новинки",
                subtitle = "Показывать подборку новых книг на Главной.",
                checked = settings.homeShowNew,
                onCheckedChange = vm::setShowNew,
            )
        }
        item {
            AbredSettingsSwitchRow(
                title = "Популярное",
                subtitle = "Показывать подборку популярных книг на Главной.",
                checked = settings.homeShowPopular,
                onCheckedChange = vm::setShowPopular,
            )
        }
        item {
            AbredSettingsSwitchRow(
                title = "Продолжить слушать",
                subtitle = "Показывать последнюю незавершённую книгу.",
                checked = settings.homeShowContinue,
                onCheckedChange = vm::setShowContinue,
            )
        }
        item {
            AbredSettingsSwitchRow(
                title = "Скачанные",
                subtitle = "Показывать скачанные книги отдельным разделом.",
                checked = settings.homeShowDownloads,
                onCheckedChange = vm::setShowDownloads,
            )
        }

        item { AbredSettingsSectionTitle("Кэш") }

        item {
            AbredSettingsDropdownRow(
                title = "Обновлять подборки",
                subtitle = "При старте сначала используется сохранённый кэш. После истечения срока подборки обновляются в фоне.",
                selected = settings.homeCacheDays,
                options = HOME_CACHE_DAY_OPTIONS,
                optionLabel = { days ->
                    when (days) {
                        1 -> "1 день"
                        2 -> "2 дня"
                        3 -> "3 дня"
                        5 -> "5 дней"
                        10 -> "10 дней"
                        else -> "$days дней"
                    }
                },
                onSelected = vm::setCacheDays,
            )
        }

        item { AbredSettingsSectionTitle("Популярное") }

        item {
            AbredSettingsDropdownRow(
                title = "Период по умолчанию",
                subtitle = "Какой период открывать при входе на Главную.",
                selected = settings.homePopularDefaultPeriod,
                options = HomePopularDefaultPeriod.entries,
                optionLabel = { period ->
                    when (period) {
                        HomePopularDefaultPeriod.TODAY -> "Сегодня"
                        HomePopularDefaultPeriod.WEEK -> "Неделя"
                        HomePopularDefaultPeriod.MONTH -> "Месяц"
                    }
                },
                onSelected = vm::setPopularDefaultPeriod,
            )
        }

        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}
