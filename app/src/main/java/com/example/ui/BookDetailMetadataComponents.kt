package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun GenreLabelChip(name: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = name,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .padding(
                    horizontal = AbredSpacing.Sm,
                    vertical = AbredSpacing.Xs,
                ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun DurationPill(durationSeconds: Long) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AbredSpacing.Xs,
                vertical = AbredSpacing.Xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                null,
                Modifier.size(AbredSizes.IconSmall),
            )
            Spacer(Modifier.width(AbredSpacing.Xxs))
            Text(
                detailFormatSeconds(durationSeconds),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
