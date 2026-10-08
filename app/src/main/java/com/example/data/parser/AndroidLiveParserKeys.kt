package com.example.data.parser

import com.example.data.source.StandaloneSourceRegistry

internal fun parseLiveBookKey(value: String): Pair<String, String>? {
    val separator = value.indexOf(':')
    if (separator <= 0 || separator >= value.lastIndex) return null
    val source = value.substring(0, separator).trim().lowercase()
    val externalId = value.substring(separator + 1).trim()
    if (source.isBlank() || externalId.isBlank()) return null
    if (!isValidLiveBookExternalId(source, externalId)) return null
    return source to externalId
}

internal fun sourceSeriesCapabilityAllows(bookId: String): Boolean {
    val source = parseLiveBookKey(bookId)?.first ?: return false
    return StandaloneSourceRegistry.supportsSeries(source)
}

internal fun isSafeLiveEntityRef(value: String): Boolean {
    val parts = value.trim().split(':', limit = 3)
    if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return false
    return isSafeRouteToken(parts[2].trim())
}

private fun isValidLiveBookExternalId(source: String, externalId: String): Boolean = when (source) {
    AUDIOPOLKA_SOURCE, UKNIG_SOURCE, RUTRACKER_SOURCE -> externalId.all(Char::isDigit)
    AUDIOBOO_SOURCE, MYAUDIOBOOKS_SOURCE ->
        !externalId.contains("..") &&
            !externalId.contains('\\') &&
            externalId.none { it.isISOControl() || it.isWhitespace() || it == ':' } &&
            !containsEncodedRouteControl(externalId) &&
            Regex("^[^/?#]+/\\d+[^/?#]*\\.html$", RegexOption.IGNORE_CASE).matches(externalId)
    AUDIOKNIGA_LIFE_SOURCE ->
        !externalId.contains("..") &&
            !externalId.contains('\\') &&
            externalId.none { it.isISOControl() || it.isWhitespace() || it == ':' } &&
            !containsEncodedRouteControl(externalId) &&
            Regex("^(?:[^/?#]+/)+\\d+[^/?#]*\\.html$", RegexOption.IGNORE_CASE).matches(externalId)
    KNIGAVUHE_SOURCE -> isSafeSinglePathSegment(externalId)
    BAZAKNIG_SOURCE ->
        !externalId.contains("..") &&
            Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._~-]*$").matches(externalId)
    else -> externalId.none(Char::isISOControl)
}

private fun isSafeSinglePathSegment(value: String): Boolean = isSafeRouteToken(value)

private fun isSafeRouteToken(value: String): Boolean =
    value.isNotBlank() &&
        !value.contains("..") &&
        value.none {
            it == '/' ||
                it == '\\' ||
                it == '?' ||
                it == '#' ||
                it == ':' ||
                it.isISOControl() ||
                it.isWhitespace()
        } &&
        !containsEncodedRouteControl(value)

private fun containsEncodedRouteControl(value: String): Boolean {
    val lower = value.lowercase()
    return ENCODED_ROUTE_CONTROLS.any(lower::contains)
}

private val ENCODED_ROUTE_CONTROLS = listOf(
    "%2f",
    "%5c",
    "%3f",
    "%23",
    "%3a",
    "%2e",
    "%25",
)

internal const val AUDIOPOLKA_SOURCE = "audiopolka"
