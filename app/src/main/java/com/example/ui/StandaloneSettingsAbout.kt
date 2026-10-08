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
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.data.source.StandaloneSourceRegistry
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AudioBookRedBrandRed

@Composable
internal fun AboutAppCard(
    onCheckUpdates: () -> Unit,
    canCheckUpdates: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AbredSpacing.Xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "Логотип AudioBookRed",
                    modifier = Modifier.size(52.dp),
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
                Spacer(Modifier.height(2.dp))
                Text(
                    "Версия ${BuildConfig.VERSION_NAME} · ${StandaloneSourceRegistry.activeSources.size} источников",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (canCheckUpdates) 0.45f else 0.25f,
                ),
                modifier = Modifier.size(18.dp),
            )
        }

        Text(
            "Аудиокниги из нескольких источников в одном локальном плеере.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AbredSpacing.Xxs),
        )
    }
}
