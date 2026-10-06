package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.data.model.BookDetailDto
import kotlin.math.roundToInt

internal fun detailSourceCode(book: BookDetailDto): String =
    book.selectedSource.ifBlank { book.primarySource.ifBlank { book.sourceCodes.firstOrNull().orEmpty() } }

internal fun playbackButtonLabel(progress: Double, showProgressPercent: Boolean): String {
    if (progress <= 0.05) return "Слушать"
    return if (showProgressPercent) {
        "Продолжить · ${progress.roundToInt()}%"
    } else {
        "Продолжить"
    }
}

internal fun detailFormatSeconds(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

internal fun detailFormatMillis(ms: Long): String = detailFormatSeconds(ms.coerceAtLeast(0) / 1000)
