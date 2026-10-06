package com.example.data.model

/**
 * Minimal Book Detail read model built from a list card.
 *
 * It is intentionally metadata-only: chapters and source variants remain empty
 * until the real detail endpoint refreshes the Room payload. This gives Book Detail
 * an immediate title/author/cover fallback without replacing an existing full detail.
 */
internal fun BookCardDto.toDetailShell(): BookDetailDto = BookDetailDto(
    id = id,
    title = title,
    authors = authors,
    narrators = narrators,
    genres = genres,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
    isFavorite = isFavorite,
    progressPercent = progressPercent,
    sourceCodes = sourceCodes,
    primarySource = primarySource,
    selectedSource = primarySource.ifBlank { sourceCodes.firstOrNull().orEmpty() },
    sourceSeriesName = sourceSeriesName,
    sourceSeriesPosition = sourceSeriesPosition,
    audioSeries = audioSeries,
    series = series,
)