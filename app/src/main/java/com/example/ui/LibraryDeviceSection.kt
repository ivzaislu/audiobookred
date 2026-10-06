package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        items(
            count = downloads.size,
            key = { index -> "library-device-${downloads[index].bookSourceId}" },
        ) { index ->
            val item = downloads[index]
            LibraryDeviceBookCard(
                item = item,
                onOpen = { onOpen(item.bookSourceId) },
                onPlay = { onPlay(item.bookSourceId) },
                onPause = { onPause(item.bookSourceId) },
                onResume = { onResume(item.bookSourceId) },
                onRetry = { onRetry(item.bookSourceId) },
                onRemove = { onRemove(item.bookSourceId) },
            )
        }
    }
}
