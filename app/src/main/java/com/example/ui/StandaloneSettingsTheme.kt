package com.example.ui

import androidx.compose.runtime.Composable
import com.example.data.settings.AppThemeMode

private val THEME_MODE_ORDER = listOf(
    AppThemeMode.DARK,
    AppThemeMode.LIGHT,
    AppThemeMode.SYSTEM,
)

private val AppThemeMode.settingsLabel: String
    get() = when (this) {
        AppThemeMode.DARK -> "Тёмная"
        AppThemeMode.LIGHT -> "Светлая"
        AppThemeMode.SYSTEM -> "Система"
    }

@Composable
internal fun ThemeModeSelector(
    selected: AppThemeMode,
    onSelected: (AppThemeMode) -> Unit,
) {
    AbredSettingsDropdownRow(
        title = "Тема",
        selected = selected,
        options = THEME_MODE_ORDER,
        optionLabel = { it.settingsLabel },
        onSelected = onSelected,
    )
}
