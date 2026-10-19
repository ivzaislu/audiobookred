package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.BuildConfig
import com.example.R
import com.example.data.source.StandaloneSourceRegistry
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AudioBookRedBrandRed
import com.example.ui.theme.SettingsDesign

@Composable
internal fun AboutAppCard(
    onCheckUpdates: () -> Unit,
    canCheckUpdates: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AbredSpacing.Xxs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AbredSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(SettingsDesign.AboutMarkSize)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "Логотип AudioBookRed",
                    modifier = Modifier.size(SettingsDesign.AboutMarkSize),
                )
            }

            Spacer(Modifier.width(AbredSpacing.Sm))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AudioBook",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Red",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AudioBookRedBrandRed,
                    )
                }
                Spacer(Modifier.height(AbredSpacing.Micro))
                Text(
                    "Версия ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${StandaloneSourceRegistry.activeSources.size} источников",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canCheckUpdates, onClick = onCheckUpdates)
                .padding(vertical = AbredSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Проверить обновления",
                style = MaterialTheme.typography.bodyLarge,
                color = if (canCheckUpdates) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = AbredOpacity.DisabledContent)
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (canCheckUpdates) AbredOpacity.DecorativeEnabled else AbredOpacity.DecorativeDisabled,
                ),
                modifier = Modifier.size(AbredSizes.IconSmall),
            )
        }

    }
}

