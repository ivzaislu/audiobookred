package com.example.ui.viewmodel

import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesProgressBookDto
import com.example.data.model.SeriesBriefDto
import com.example.data.textbook.TextBookEntity
import com.example.data.textbook.TextBookMetadata
import com.example.data.textbook.TextLibraryActivity
import com.example.data.textbook.textLibraryCycles
import com.example.data.textbook.textLibraryHistory

internal data class TextLibraryMembership(
    val history: List<BookCardDto> = emptyList(),
    val series: List<MySeriesDto> = emptyList(),
    val seriesBooks: Map<String, List<BookCardDto>> = emptyMap(),
    val activityTimes: Map<String, Long> = emptyMap(),
)

internal fun textLibraryMembership(
    books: List<TextBookEntity>, metadata: Map<String, TextBookMetadata>, activity: Map<String, TextLibraryActivity>,
    listeningProgress: Map<String, Double> = emptyMap(),
): TextLibraryMembership {
    val cards = books.associate { book ->
        val info = metadata[book.id] ?: TextBookMetadata()
        val record = activity[book.id]
        book.id to BookCardDto(id = book.id, title = book.title, coverUrl = info.coverUri,
            progressPercent = listeningProgress[book.id]?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0,
            authors = info.author.takeIf { it.isNotBlank() }?.let { listOf(PersonDto("local:author", it)) }.orEmpty(),
            sourceMeta = record?.sourceName.orEmpty(), primarySource = record?.sourceCode.orEmpty(),
            sourceSeriesName = record?.seriesName.orEmpty(), sourceSeriesPosition = record?.seriesNumber?.toIntOrNull(),
            series = record?.takeIf { it.seriesKey.isNotBlank() && it.seriesName.isNotBlank() }?.let {
                listOf(SeriesBriefDto(id = it.seriesKey, name = it.seriesName,
                    position = it.seriesNumber.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite),
                    isPrimary = true, provider = "text:${it.sourceCode}", externalId = it.seriesId))
            }.orEmpty())
    }
    val groups = textLibraryCycles(activity.values, cards.keys)
    return TextLibraryMembership(
        history = textLibraryHistory(activity.values, cards.keys).mapNotNull { cards[it.bookId] },
        series = groups.map { members ->
            val recent = members.maxBy { it.lastActivityAtMs }
            val next = members.dropWhile { it.bookId != recent.bookId }.drop(1).firstOrNull { it.lastActivityAtMs == 0L }
            fun progress(record: TextLibraryActivity) = SeriesProgressBookDto(
                book = cards.getValue(record.bookId), position = record.seriesNumber.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite),
                state = if (record.lastActivityAtMs > 0) "in_progress" else "not_started",
            )
            MySeriesDto(id = recent.seriesKey, name = recent.seriesName, provider = "text:${recent.sourceCode}",
                externalId = recent.seriesId, sourceName = recent.sourceName,
                availableCount = members.size, totalCount = members.size,
                inProgressCount = members.count { it.lastActivityAtMs > 0 }, notStartedCount = members.count { it.lastActivityAtMs == 0L },
                lastActivityAt = recent.lastActivityAtMs.toString(), currentBook = progress(recent), nextBook = next?.let(::progress))
        },
        seriesBooks = groups.associate { members -> members.first().seriesKey to members.mapNotNull { cards[it.bookId] } },
        activityTimes = activity.mapValues { it.value.lastActivityAtMs },
    )
}
