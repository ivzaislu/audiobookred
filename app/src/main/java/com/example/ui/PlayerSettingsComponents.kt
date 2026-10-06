package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.AbredSpacing

@Composable
internal fun PlayerChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: LazyListScope.() -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(icon)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(AbredSpacing.Sm))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            content = content,
        )
    }
}

@Composable
internal fun PlayerSettingsSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(icon, active = checked)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

internal fun playerSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() + "×" else speed.toString() + "×"
