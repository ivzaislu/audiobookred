package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.SettingsDesign

@Composable
internal fun <T> PlayerChoiceCard(
    title: String,
    subtitle: String?,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    AbredSettingsDropdownRow(
        modifier = Modifier.heightIn(min = AbredSizes.MinimumTouchTarget),
        verticalPadding = SettingsDesign.InnerChoiceVerticalPadding,
        title = title,
        subtitle = subtitle,
        selected = selected,
        options = options,
        optionLabel = optionLabel,
        onSelected = onSelected,
    )
}

@Composable
internal fun PlayerSettingsSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AbredSettingsSwitchRow(
        trailingControl = true,
        verticalPadding = SettingsDesign.CompactSwitchVerticalPadding,
        alignControlWithTitle = true,
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

internal fun playerSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "×" + speed.toInt().toString() else "×" + speed.toString()

