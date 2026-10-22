package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredSizes

/** Local cycle members remain accessible without the source website or RuVoice installed. */
@Composable
internal fun TextLibraryCycleScreen(
    series: MySeriesDto, books: List<BookCardDto>, onBack: () -> Unit,
    onBook: (String) -> Unit, onPlay: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxHeight().widthIn(max = AbredSizes.ContentMaxWidth).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(AbredSpacing.Sm)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К циклам") }
                Column(Modifier.weight(1f)) {
                    Text(series.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${series.sourceName} · На устройстве: ${books.size} книг", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                series.currentBook?.book?.id?.let { id ->
                    IconButton(onClick = { onPlay(id) }) { Icon(Icons.Default.PlayArrow, "Продолжить слушать") }
                }
            }
            LibraryBooksListV2(books = books, emptyText = "В этом цикле нет сохранённых книг",
                showProgressPercent = false, onBook = onBook)
        }
    }
}
