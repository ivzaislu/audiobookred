package com.example.ui.viewmodel

/**
 * Navigation-only description of a series screen.
 *
 * The actual title, counts, description and entries are loaded by Paging 3 from
 * the paged series endpoints. A source book id is kept only when the screen was
 * opened from a book, so Android can offer every cycle known for that book.
 */
sealed interface SeriesRoute {
    data class Canonical(
        val seriesId: String,
        val bookId: String? = null,
    ) : SeriesRoute

    data class Source(
        val bookId: String,
        val provider: String? = null,
    ) : SeriesRoute

    data class Audio(
        val seriesId: String,
        val bookId: String? = null,
    ) : SeriesRoute
}