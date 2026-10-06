package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

/** Stable Library presentation sections used by LibraryScreenV2. */
@Composable
internal fun LibraryTilesV2(
    selected: LibraryTabV2,
    counts: Map<LibraryTabV2, Int>,
    onSelect: (LibraryTabV2) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            LibraryTileV2(
                tab = LibraryTabV2.History,
                count = counts[LibraryTabV2.History] ?: 0,
                selected = selected == LibraryTabV2.History,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.History) },
            )
            LibraryTileV2(
                tab = LibraryTabV2.Favorites,
                count = counts[LibraryTabV2.Favorites] ?: 0,
                selected = selected == LibraryTabV2.Favorites,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Favorites) },
            )
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.ScreenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            LibraryTileV2(
                tab = LibraryTabV2.Series,
                count = counts[LibraryTabV2.Series] ?: 0,
                selected = selected == LibraryTabV2.Series,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Series) },
            )
            LibraryTileV2(
                tab = LibraryTabV2.Bookmarks,
                count = counts[LibraryTabV2.Bookmarks] ?: 0,
                selected = selected == LibraryTabV2.Bookmarks,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Bookmarks) },
            )
            LibraryTileV2(
                tab = LibraryTabV2.Device,
                count = counts[LibraryTabV2.Device] ?: 0,
                selected = selected == LibraryTabV2.Device,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Device) },
            )
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
    }
}

@Composable
private fun LibraryTileV2(
    tab: LibraryTabV2,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val title = when (tab) {
        LibraryTabV2.History -> "История"
        LibraryTabV2.Favorites -> "Избранное"
        LibraryTabV2.Series -> "Циклы"
        LibraryTabV2.Bookmarks -> "Закладки"
        LibraryTabV2.Device -> "Скачанные"
    }
    val icon = when (tab) {
        LibraryTabV2.History -> Icons.Default.History
        LibraryTabV2.Favorites -> Icons.Default.Favorite
        LibraryTabV2.Series -> Icons.Default.LibraryBooks
        LibraryTabV2.Bookmarks -> Icons.Default.Bookmark
        LibraryTabV2.Device -> Icons.Default.DownloadForOffline
    }

    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(Modifier.padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconSmall),
            )
            Spacer(Modifier.height(AbredSpacing.Xxs))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
