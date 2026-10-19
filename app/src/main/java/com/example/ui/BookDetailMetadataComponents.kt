package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.BookDetailDesign
import com.example.ui.theme.abredOutlineBorder

@Composable
internal fun GenreLabelChip(name: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = abredOutlineBorder(),
    ) {
        Text(
            text = name,
            modifier = Modifier
                .widthIn(max = BookDetailDesign.LegacyGenreMaxWidth)
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
        border = abredOutlineBorder(),
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

