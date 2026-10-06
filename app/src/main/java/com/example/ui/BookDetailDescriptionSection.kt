package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSpacing

@Composable
internal fun DescriptionSection(
    description: String,
    stateKey: String,
) {
    var expanded by remember(stateKey) { mutableStateOf(false) }
    var hasOverflow by remember(stateKey) { mutableStateOf(false) }

    BookDetailSectionCard(
        modifier = Modifier.padding(top = AbredSpacing.Sm),
    ) {
        Text(
            "Описание",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) hasOverflow = result.hasVisualOverflow
            },
        )
        if (hasOverflow || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = AbredSpacing.Xxs),
            ) {
                Text(if (expanded) "Свернуть" else "Показать полностью")
            }
        }
    }
}
