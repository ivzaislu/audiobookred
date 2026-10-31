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
    TextBookMetadataSection(
        authors = book.authors.map { PersonDto(it.id, it.name) },
        seriesName = book.series?.name.orEmpty(),
        seriesNumber = book.seriesNumber,
        sourceName = sourceName,
        year = book.year,
        enabled = enabled,
        onAuthor = { person -> book.authors.firstOrNull { it.id == person.id }?.let { onCollection(it.url) } },
        onSeries = { book.series?.let { onCollection(it.url) } },
    )
}

@Composable
internal fun TextBookMetadataSection(
    authors: List<PersonDto>,
    seriesName: String,
    seriesNumber: String?,
    sourceName: String,
    year: String? = null,
    enabled: Boolean = true,
    onAuthor: ((PersonDto) -> Unit)? = null,
    onSeries: (() -> Unit)? = null,
    extraContent: @Composable () -> Unit = {},
) {
    BookDetailMetadataCard {
        BookDetailMetadataRow("Автор") {
            BookDetailPeopleValues(
                people = authors,
                interactive = onAuthor != null,
                enabled = enabled,
                onPerson = { onAuthor?.invoke(it) },
            )
        }
        if (seriesName.isNotBlank()) {
            BookDetailSeriesMetadataRow(
                name = seriesName,
                subtitle = seriesNumber?.takeIf { it.isNotBlank() }?.let { "Книга $it" }.orEmpty(),
                onOpen = { onSeries?.invoke() },
                enabled = enabled && onSeries != null,
                valueColor = MaterialTheme.colorScheme.abredAccentText,
            )
        }
        year?.let { year ->
            BookDetailMetadataRow("Год") {
                Text(year, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        extraContent()
        BookDetailMetadataRow("Источник") {
            Text(sourceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
