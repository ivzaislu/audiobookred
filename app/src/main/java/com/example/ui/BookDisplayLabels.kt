package com.example.ui

import com.example.data.model.BookCardDto
import com.example.data.source.StandaloneSourceRegistry

internal fun sourceDisplayLabel(code: String?): String {
    val clean = code?.trim().orEmpty()
    if (clean.isBlank()) return "Все"
    return StandaloneSourceRegistry.displayNameOrNull(clean) ?: clean
}

internal fun BookCardDto.sourceDisplayLabel(): String {
    val explicit = primarySource.ifBlank { sourceCodes.firstOrNull().orEmpty() }
    if (explicit.isNotBlank()) return sourceDisplayLabel(explicit)

    // Live standalone cards use source-qualified ids such as audiopolka:12345.
    // Older cached cards may not have primarySource/sourceCodes populated, so keep
    // the provider label visible by deriving it from the stable live id prefix.
    val idSource = id.substringBefore(':', missingDelimiterValue = "")
        .trim()
        .lowercase()
        .takeIf(StandaloneSourceRegistry::isActive)
        .orEmpty()
    return if (idSource.isBlank()) "" else sourceDisplayLabel(idSource)
}

internal fun BookCardDto.seriesDisplayLabel(): String {
    val source = primarySource.ifBlank { sourceCodes.firstOrNull().orEmpty() }
    if (source.equals("rutracker", ignoreCase = true)) return ""

    val canonical = series.firstOrNull { it.isPrimary } ?: series.firstOrNull()
    if (canonical != null) return "${canonical.position?.let { "№$it · " }.orEmpty()}${canonical.name}"
    val sourceSeries = sourceSeriesName.trim()
    if (sourceSeries.isNotBlank()) return "${sourceSeriesPosition?.let { "№$it · " }.orEmpty()}$sourceSeries"
    return ""
}
