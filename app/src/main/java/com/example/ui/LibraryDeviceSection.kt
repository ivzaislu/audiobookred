package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadBookEntity
import com.example.ui.theme.AbredSpacing

@Composable
internal fun DeviceBooksListV2(
    downloads: List<DownloadBookEntity>,
    queryActive: Boolean,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (downloads.isEmpty()) {
        LibraryEmptyState(if (queryActive) "Ничего не найдено" else "На устройстве пока нет книг")
        return
    }

    val storedBytes = remember(downloads) {
        downloads.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
    ) {
        item(key = "library-device-summary") {
            Text(
                text = "${downloads.size} книг · ${libraryFormatBytes(storedBytes)} на устройстве",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = 52.dp + AbredSpacing.Sm,
                    bottom = AbredSpacing.Xxs,
                ),
            )
        }

        itemsIndexed(
            items = downloads,
            key = { _, item -> "library-device-${item.bookSourceId}" },
        ) { index, item ->
            LibraryDeviceBookCard(
                item = item,
                onOpen = { onOpen(item.bookSourceId) },
                onPlay = { onPlay(item.bookSourceId) },
                onPause = { onPause(item.bookSourceId) },
                onResume = { onResume(item.bookSourceId) },
                onRetry = { onRetry(item.bookSourceId) },
                onRemove = { onRemove(item.bookSourceId) },
            )

            if (index != downloads.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp + AbredSpacing.Sm),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
        }
    }
}
