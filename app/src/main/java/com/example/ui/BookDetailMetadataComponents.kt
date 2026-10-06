package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.GenreDto
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun GenreOverflowChip(
    genres: List<GenreDto>,
    onGenre: (GenreDto) -> Unit,
) {
    var expanded by remember(genres) { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            label = { Text("+${(genres.size - 2).coerceAtLeast(0)}") },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            genres.drop(2).forEach { genre ->
                DropdownMenuItem(
                    text = { Text(genre.name, modifier = Modifier.widthIn(max = 260.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onGenre(genre)
                    },
                )
            }
        }
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
