package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.PersonDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun BookDetailSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AbredSpacing.Md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
internal fun CompactPeopleLine(
    prefix: String,
    people: List<PersonDto>,
    emptyText: String = "",
    interactive: Boolean = true,
    onPerson: (PersonDto) -> Unit,
) {
    var expanded by remember(people) { mutableStateOf(false) }
    if (people.isEmpty()) {
        if (emptyText.isNotBlank()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Box {
        val rowModifier = if (interactive) {
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (people.size == 1) onPerson(people.first()) else expanded = true
                }
        } else {
            Modifier.fillMaxWidth()
        }
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$prefix: ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                people.joinToString(", ") { it.name },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (interactive && people.size > 1) {
                Spacer(Modifier.width(AbredSpacing.Xxs))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Выбрать",
                    modifier = Modifier.size(AbredSizes.IconSmall),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = interactive && expanded,
            onDismissRequest = { expanded = false },
        ) {
            people.forEach { person ->
                DropdownMenuItem(
                    text = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onPerson(person)
                    },
                )
            }
        }
    }
}
