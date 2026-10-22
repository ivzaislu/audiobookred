package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.data.model.PersonDto
import com.example.data.textsource.RemoteTextBook
import com.example.ui.theme.abredAccentText

/** Uses the audiobook metadata layout, typography and navigation controls without TextButton insets. */
@Composable
internal fun TextBookMetadataSection(
    book: RemoteTextBook,
    sourceName: String,
    enabled: Boolean,
    onCollection: (String) -> Unit,
) {
    BookDetailMetadataCard {
        BookDetailMetadataRow("Автор") {
            BookDetailPeopleValues(
                people = book.authors.map { PersonDto(it.id, it.name) },
                interactive = true,
                enabled = enabled,
                onPerson = { person -> book.authors.firstOrNull { it.id == person.id }?.let { onCollection(it.url) } },
            )
        }
        book.series?.let { series ->
            BookDetailSeriesMetadataRow(
                name = series.name,
                subtitle = book.seriesNumber?.let { "Книга $it" }.orEmpty(),
                onOpen = { onCollection(series.url) },
                enabled = enabled,
                valueColor = MaterialTheme.colorScheme.abredAccentText,
            )
        }
        book.year?.let { year ->
            BookDetailMetadataRow("Год") {
                Text(year, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        BookDetailMetadataRow("Источник") {
            Text(sourceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
