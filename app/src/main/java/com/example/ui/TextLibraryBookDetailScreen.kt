package com.example.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.textbook.TextBookEntity
import com.example.data.textbook.TextBookMetadata

/** The catalog's printed-book card with local reading and listening actions. */
@Composable
internal fun TextLibraryBookDetailScreen(
    book: TextBookEntity,
    metadata: TextBookMetadata,
    card: BookCardDto?,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onSeries: (String) -> Unit,
    actions: @Composable () -> Unit = { TextBookReadingActions(onRead, onListen) },
    onAuthor: ((com.example.data.model.PersonDto) -> Unit)? = null,
) {
    val series = card?.series?.firstOrNull { it.isPrimary } ?: card?.series?.firstOrNull()
    TextBookDetailPage(
        book = BookDetailDto(id = book.id, title = book.title, coverUrl = metadata.coverUri),
        onBack = onBack,
        description = metadata.description,
        descriptionKey = book.id,
        actions = actions,
        metadata = {
            TextBookMetadataSection(
                authors = card?.authors.orEmpty().ifEmpty {
                    metadata.author.takeIf { it.isNotBlank() }?.let {
                        listOf(com.example.data.model.PersonDto("local:author", it))
                    }.orEmpty()
                },
                seriesName = series?.name ?: card?.sourceSeriesName.orEmpty(),
                seriesNumber = series?.position?.toString() ?: card?.sourceSeriesPosition?.toString(),
                sourceName = card?.sourceMeta?.takeIf { it.isNotBlank() } ?: "Импортированная книга",
                onSeries = series?.let { item -> { onSeries(item.id) } },
                onAuthor = onAuthor,
                extraContent = {
                    BookDetailMetadataRow("Формат") { Text(metadata.format) }
                    BookDetailMetadataRow("Глав") { Text(book.chapterCount.toString()) }
                },
            )
        },
    )
}
