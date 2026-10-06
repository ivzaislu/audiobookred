package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun CatalogFilterButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingFaviconRes: Int? = null,
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AbredSizes.MinimumTouchTarget)
            .semantics { this.selected = active },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = if (active) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingFaviconRes != null) {
                SourceFaviconBadge(faviconRes = leadingFaviconRes)
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(AbredSpacing.Xxs))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconSmall),
            )
        }
    }
}

@Composable
internal fun SourceFaviconBadge(
    faviconRes: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(22.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.White,
        border = BorderStroke(1.dp, Color.White),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(faviconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun CatalogDropdownRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIconRes: Int? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AbredSizes.MinimumTouchTarget)
            .semantics { this.selected = selected },
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AbredSpacing.Sm,
                vertical = AbredSpacing.Xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIconRes != null) {
                SourceFaviconBadge(faviconRes = leadingIconRes)
                Spacer(Modifier.width(AbredSpacing.Xs))
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Spacer(Modifier.width(AbredSpacing.Xs))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
            }
        }
    }
}
