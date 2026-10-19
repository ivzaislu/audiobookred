package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.SettingsDesign

/**
 * Shared settings container.
 *
 * Settings stay on the app background. Hierarchy is carried by section labels,
 * spacing and typography instead of raised/tinted cards.
 */
@Composable
internal fun AbredSettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = AbredSpacing.Xxs,
        vertical = AbredSpacing.Xs,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        tonalElevation = AbredElevation.Flat,
        shadowElevation = AbredElevation.Flat,
    ) {
        Column(
            Modifier.padding(contentPadding),
            content = content,
        )
    }
}

@Composable
internal fun AbredSettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    compactTop: Boolean = false,
    topPadding: Dp = AbredSpacing.Lg,
    bottomPadding: Dp = AbredSpacing.Xs,
    startPadding: Dp = AbredSpacing.Xxs,
) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AbredOpacity.SectionLabel),
        modifier = modifier.padding(
            top = if (compactTop) AbredSpacing.None else topPadding,
            start = startPadding,
            bottom = bottomPadding,
        ),
    )
}

/** Compact layout shared by settings subpages; root settings keeps its defaults. */
@Composable
internal fun AbredInnerSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    AbredSettingsCard(
        contentPadding = PaddingValues(vertical = SettingsDesign.InnerRowVerticalPadding),
        content = content,
    )
}

@Composable
internal fun AbredInnerSettingsSectionTitle(
    title: String,
    first: Boolean = false,
    bottomPadding: Dp = SettingsDesign.InnerGroupBottomPadding,
) {
    Column {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    vertical = SettingsDesign.InnerGroupTopPadding / 2,
                ),
                color = MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = AbredOpacity.SectionOutline,
                ),
            )
        }
        AbredSettingsSectionTitle(
            title = title,
            compactTop = true,
            bottomPadding = bottomPadding,
            startPadding = AbredSpacing.None,
        )
    }
}

/**
 * Shared toggle row; the root settings screen opts into a trailing control.
 * AudioBookRed still owns all colors, typography and Material behavior.
 */
@Composable
internal fun AbredSettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailingControl: Boolean = false,
    verticalPadding: Dp = AbredSpacing.Xs,
    alignControlWithTitle: Boolean = false,
    enabled: Boolean = true,
) {
    // Match the first title line to the centre of the unchanged Material touch target.
    val titleTopPadding = if (alignControlWithTitle) {
        val lineHeight = with(LocalDensity.current) {
            MaterialTheme.typography.bodyLarge.lineHeight.toDp()
        }
        ((AbredSizes.MinimumTouchTarget - lineHeight) / 2).coerceAtLeast(AbredSpacing.None)
    } else {
        AbredSpacing.None
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = verticalPadding),
        verticalAlignment = if (alignControlWithTitle) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (!trailingControl) {
            Box(
                modifier = Modifier.width(SettingsDesign.ValueColumnWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
        }
        Column(Modifier.weight(1f).padding(top = titleTopPadding)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(AbredSpacing.Micro))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailingControl) {
            Spacer(Modifier.width(AbredSpacing.Sm))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

/**
 * Flat Audiobookshelf-like value row.
 *
 * The value/dropdown area is its own trailing anchor, so the menu opens under
 * the value on the right instead of using the full row as its popup anchor.
 */
@Composable
internal fun <T> AbredSettingsDropdownRow(
    title: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    verticalPadding: Dp = AbredSpacing.Sm,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(AbredSpacing.Micro))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(AbredSpacing.Md))

        Box {
            Row(
                modifier = Modifier.padding(vertical = AbredSpacing.Xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    optionLabel(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AbredOpacity.Chevron),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

