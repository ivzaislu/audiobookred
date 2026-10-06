package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import com.example.data.model.SourceVariantDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun BookSourceDropdown(
    variants: List<SourceVariantDto>,
    selectedSource: String,
    onSelectSource: (String) -> Unit,
) {
    var expanded by remember(variants, selectedSource) { mutableStateOf(false) }
    val selected = variants.firstOrNull { it.sourceCode == selectedSource }
    val selectedLabel = selected?.sourceName?.takeIf { it.isNotBlank() }
        ?: selected?.sourceCode?.let(::sourceDisplayLabel)
        ?: sourceDisplayLabel(selectedSource)

    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AbredSpacing.Sm,
                        vertical = AbredSpacing.Xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text(
                    selectedLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Icon(Icons.Default.ExpandMore, null, Modifier.size(AbredSizes.IconSmall))
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            variants.forEach { variant ->
                val isSelected = variant.sourceCode == selectedSource
                val label = variant.sourceName.takeIf { it.isNotBlank() }
                    ?: sourceDisplayLabel(variant.sourceCode)
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            modifier = Modifier.widthIn(max = 260.dp),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelectSource(variant.sourceCode)
                    },
                )
            }
        }
    }
}
