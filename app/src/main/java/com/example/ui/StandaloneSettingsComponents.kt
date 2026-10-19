package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.SettingsDesign
import com.example.ui.theme.abredOutlineBorder

@Composable
internal fun SettingsHeader(compact: Boolean = false) {
    AbredBrandHeader(
        sectionTitle = "Настройки",
        height = if (compact) SettingsDesign.BrandHeaderHeight else AbredSizes.RootHeaderHeight,
        contentPadding = PaddingValues(
            top = if (compact) SettingsDesign.BrandHeaderTopPadding else AbredSpacing.None,
        ),
    )
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    AbredSettingsCard(
        contentPadding = PaddingValues(AbredSpacing.None),
        content = content,
    )
}

/** Kept for the few standalone cards that still intentionally use an outline. */
@Composable
internal fun settingsCardBorder() = abredOutlineBorder(opacity = AbredOpacity.Muted)

@Composable
internal fun StandaloneNavigationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    chevronOpacity: Float = AbredOpacity.Muted,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AbredSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(AbredSpacing.Micro))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(AbredSpacing.Sm))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = chevronOpacity),
            modifier = Modifier.size(AbredSizes.IconSmall),
        )
    }
}

@Composable
internal fun StandaloneSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    trailingControl: Boolean = false,
) {
    AbredSettingsSwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onChecked,
        trailingControl = trailingControl,
    )
}

@Composable
internal fun StandaloneNavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    SettingsCard {
        StandaloneNavigationRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            onClick = onClick,
        )
    }
}

@Composable
internal fun StandaloneSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    trailingControl: Boolean = false,
) {
    SettingsCard {
        StandaloneSwitchRow(
            title = title,
            subtitle = subtitle,
            checked = checked,
            onChecked = onChecked,
            trailingControl = trailingControl,
        )
    }
}

@Composable
internal fun StandaloneActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    showIcon: Boolean = true,
) {
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AbredSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Box(
                modifier = Modifier.size(AbredSizes.SettingsIconContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(AbredSpacing.Micro))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SettingsTitle(
    text: String,
    compactTop: Boolean = false,
) {
    AbredSettingsSectionTitle(title = text, compactTop = compactTop)
}

