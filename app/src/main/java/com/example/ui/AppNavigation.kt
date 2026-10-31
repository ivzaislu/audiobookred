package com.example.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
internal enum class TopLevelDestination : NavKey {
    Home,
    Catalog,
    Library,
    Settings,
}

@Serializable
internal enum class BrowseDestinationKind {
    Author,
    Narrator,
    Genre,
    Search,
    Source,
}

@Serializable
internal data class BrowseDestination(
    val kind: BrowseDestinationKind,
    val id: String,
    val name: String,
    val excludeSource: String? = null,
    val seriesName: String? = null,
) : NavKey

@Serializable
internal data class BookDestination(
    val bookId: String,
    val downloaded: Boolean = false,
) : NavKey

@Serializable
internal data object PlayerDestination : NavKey

@Serializable
internal data class CanonicalSeriesDestination(
    val seriesId: String,
    val bookId: String? = null,
) : NavKey

@Serializable
internal data class SourceSeriesDestination(
    val bookId: String,
    val provider: String? = null,
) : NavKey

@Serializable
internal data class AudioSeriesDestination(
    val seriesId: String,
    val bookId: String? = null,
) : NavKey

@Serializable
internal data class TextBookDestination(val bookId: String) : NavKey

@Serializable
internal data class TextCycleDestination(val seriesKey: String) : NavKey

@Serializable
internal data class TextReaderDestination(val bookId: String) : NavKey

@Serializable
internal data class TextAuthorDestination(
    val sourceCode: String, val authorName: String,
    val entryId: String = java.util.UUID.randomUUID().toString(),
) : NavKey

/** Return to an existing text destination instead of stacking another copy of its saved state. */
internal fun <T> MutableList<T>.openOrReturnTo(destination: T) {
    val existing = indexOfFirst { it == destination }
    if (existing < 0) add(destination)
    else while (lastIndex > existing) removeAt(lastIndex)
}
