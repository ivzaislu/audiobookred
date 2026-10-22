package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.textbook.TextBookEntity
import com.example.data.textbook.TextBookMetadata
import com.example.ui.theme.AbredStroke
import com.example.ui.theme.abredCardColors
import com.example.ui.theme.abredFlatCardElevation
import com.example.ui.theme.abredAccentText
import com.example.ui.theme.AbredSpacing

@Composable
internal fun TextBooksSection(
    books: List<TextBookEntity>, importing: Boolean, message: String?, queryActive: Boolean,
    metadata: Map<String, TextBookMetadata> = emptyMap(),
    savedAudioIds: Map<String, String> = emptyMap(),
    onPlaySaved: (String) -> Unit = {},
    onImport: () -> Unit, onOpen: (String) -> Unit, onRemove: (String) -> Unit, onPlay: (String) -> Unit,
) {
    var removing by remember { mutableStateOf<TextBookEntity?>(null) }
    removing?.let { book ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Удалить текстовую книгу?") },
            text = { Text("«${book.title}»: будет удалена импортированная копия и позиция чтения. Исходный файл у вас останется.") },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(book.id) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "text-import") {
            Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
                Button(onClick = onImport, enabled = !importing) { Text(if (importing) "Импортируем…" else "Импортировать книгу") }
                Text("TXT, FB2, EPUB и ZIP. Чтение, RuVoice и готовые WAV работают офлайн.", style = MaterialTheme.typography.bodySmall)
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (books.isEmpty()) Text(
                    if (queryActive) "Ничего не найдено" else "Импортируйте книгу с телефона. Текст, обложка и место чтения сохранятся в приложении.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(books, key = { it.id }) { book ->
            val info = metadata[book.id] ?: TextBookMetadata()
            val saved = savedAudioIds[book.id]
            val metrics = libraryBookRowTextMetrics()
            Card(
                onClick = { onOpen(book.id) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = abredCardColors(),
                elevation = abredFlatCardElevation(),
                border = BorderStroke(AbredStroke.Hairline, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.fillMaxWidth().padding(AbredSpacing.Sm), verticalAlignment = Alignment.Top) {
                    LibraryBookRowMainContent(info.coverUri, "", metrics.sourceReserve) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            maxLines = metrics.titleMaxLines, overflow = TextOverflow.Ellipsis)
                        if (info.author.isNotBlank()) {
                            Spacer(Modifier.height(AbredSpacing.Xxs))
                            Text(info.author, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (metrics.largeText) 2 else 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text("${info.format} · глав: ${book.chapterCount}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (saved != null) {
                            Spacer(Modifier.height(AbredSpacing.Xs))
                            Text("Озвучено RuVoice · WAV", color = MaterialTheme.colorScheme.abredAccentText,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column {
                        IconButton(onClick = { if (saved != null) onPlaySaved(saved) else onPlay(book.id) }) {
                            Icon(Icons.Default.PlayArrow, if (saved != null) "Слушать готовую книгу" else "Продолжить слушать")
                        }
                        IconButton(onClick = { removing = book }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить ${book.title}")
                        }
                    }
                }
            }
        }
    }
}
