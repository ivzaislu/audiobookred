package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.settings.HOME_CACHE_DAY_OPTIONS
import com.example.data.settings.HomePopularDefaultPeriod
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.SettingsDesign
import com.example.ui.theme.AbredSizes
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
        verticalArrangement = Arrangement.spacedBy(SettingsDesign.CompactItemSpacing),
    ) {
        item {
            AbredSubpageHeader(
                title = "Главная",
                onBack = onBack,
            )
        }

        item { AbredInnerSettingsSectionTitle("Разделы", first = true, bottomPadding = SettingsDesign.CompactGroupBottomPadding) }

        item {
            AbredSettingsSwitchRow(
                trailingControl = true,
                verticalPadding = SettingsDesign.HomeSwitchVerticalPadding,
                alignControlWithTitle = true,
                title = "Новинки",
                checked = settings.homeShowNew,
                onCheckedChange = vm::setShowNew,
            )
        }
        item {
            AbredSettingsSwitchRow(
                trailingControl = true,
                verticalPadding = SettingsDesign.HomeSwitchVerticalPadding,
                alignControlWithTitle = true,
                title = "Популярное",
                checked = settings.homeShowPopular,
                onCheckedChange = vm::setShowPopular,
            )
        }
        item {
            AbredSettingsSwitchRow(
                trailingControl = true,
                verticalPadding = SettingsDesign.HomeSwitchVerticalPadding,
                alignControlWithTitle = true,
                title = "Продолжить слушать",
                checked = settings.homeShowContinue,
                onCheckedChange = vm::setShowContinue,
            )
        }
        item {
            AbredSettingsSwitchRow(
                trailingControl = true,
                verticalPadding = SettingsDesign.HomeSwitchVerticalPadding,
                alignControlWithTitle = true,
                title = "Скачанные",
                checked = settings.homeShowDownloads,
                onCheckedChange = vm::setShowDownloads,
            )
        }

        item { AbredInnerSettingsSectionTitle("Подборки", bottomPadding = SettingsDesign.CompactGroupBottomPadding) }

        item {
            AbredSettingsDropdownRow(
                modifier = Modifier.heightIn(min = AbredSizes.MinimumTouchTarget),
                verticalPadding = SettingsDesign.InnerChoiceVerticalPadding,
                title = "Обновлять подборки",
                subtitle = "Обновление в фоне после указанного срока.",
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

        item {
            AbredSettingsDropdownRow(
                modifier = Modifier.heightIn(min = AbredSizes.MinimumTouchTarget),
                verticalPadding = SettingsDesign.InnerChoiceVerticalPadding,
                title = "Период популярного",
                subtitle = "При открытии главной страницы.",
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

