package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.local.DownloadBookEntity
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.LibraryDesign

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

    var filter by remember { mutableStateOf("all") }
    val completedRuVoice = downloads.count(::isCompletedRuVoiceAudiobook)
    val visible = remember(downloads, filter) {
        when (filter) {
            "voice" -> downloads.filter(::isCompletedRuVoiceAudiobook)
            "regular" -> downloads.filterNot(::isRuVoiceAudioExport)
            else -> downloads
        }
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
                    start = LibraryDesign.DeviceCoverWidth + AbredSpacing.Sm,
                    bottom = AbredSpacing.Xxs,
                ),
            )
        }

        item(key = "library-device-filters") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            ) {
                FilterChip(
                    selected = filter == "all", onClick = { filter = "all" },
                    label = { Text("Все") },
                )
                FilterChip(
                    selected = filter == "voice", onClick = { filter = "voice" },
                    label = { Text("Озвученные ($completedRuVoice)") },
                )
                FilterChip(
                    selected = filter == "regular", onClick = { filter = "regular" },
                    label = { Text("Скачанные") },
                )
            }
        }

        if (visible.isEmpty()) {
            item(key = "library-device-empty-filter") {
                Text(
                    if (filter == "voice")
                        "Готовых озвученных книг пока нет. Когда сохранение RuVoice завершится, книга появится здесь."
                    else "В этом разделе пока нет книг.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = AbredSpacing.Lg),
                )
            }
        }

        itemsIndexed(
            items = visible,
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

            if (index != visible.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = LibraryDesign.DeviceCoverWidth + AbredSpacing.Sm),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.Soft),
                )
            }
        }
    }
}

