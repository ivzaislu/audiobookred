package com.example.ui

import com.example.data.local.DownloadBookEntity
import com.example.data.model.MySeriesDto
import kotlin.math.roundToInt

internal fun librarySeriesProgressDetail(item: MySeriesDto): String {
    val available = item.availableCount.coerceAtLeast(0)
    val completed = if (available > 0) {
        item.completedCount.coerceIn(0, available)
    } else {
        item.completedCount.coerceAtLeast(0)
    }

    return when {
        item.isCompleted -> "$completed из $available прослушано · Цикл завершён"
        item.currentBook != null -> {
            val position = item.currentBook.position?.let { "Книга ${it.toInt()}" } ?: "Текущая книга"
            "$completed из $available прослушано · $position — ${item.currentBook.progressPercent.roundToInt().coerceIn(0, 100)}%"
        }
        item.nextBook != null -> {
            val position = item.nextBook.position?.let { "Книга ${it.toInt()}" } ?: "Следующая книга"
            "$completed из $available прослушано · Далее: $position"
        }
        else -> "$completed из $available прослушано"
    }
}

internal fun libraryDownloadStatusLabel(item: DownloadBookEntity): String = buildString {
    append(
        when (item.state) {
            "completed" -> "Скачано"
            "downloading" -> "Скачивание"
            "paused" -> "Пауза"
            "failed" -> "Ошибка"
            else -> "В очереди"
        }
    )
    append(" · ${item.completedFiles}/${item.filesCount}")
    append(" · ${libraryFormatBytes(item.downloadedBytes)}")
    item.totalSizeBytes?.let { total ->
        append(" / ${libraryFormatBytes(total)}")
    }
}

internal fun libraryFormatBookmarkMillis(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(minutes, remainingSeconds)
    }
}

internal fun libraryFormatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    if (safe < 1024) return "$safe Б"
    val kb = safe / 1024.0
    if (kb < 1024) return "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    return "%.2f ГБ".format(mb / 1024.0)
}
