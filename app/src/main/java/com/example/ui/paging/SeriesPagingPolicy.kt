package com.example.ui.paging

/**
 * Translate a provider's logical page size into Paging's next-key decision.
 *
 * Audioboo intentionally exposes only 12 logical series entries per request to
 * avoid unnecessary site traffic, even when Paging asks for a larger load size.
 * A short Audioboo page therefore is not an end-of-list signal while totalCount
 * says more entries exist.
 *
 * Audiopolka, уКниг and Baza-Knig may not expose a reliable collection total.
 * Their parsers can fall back to the number of entries on the logical page in
 * that case. A full page whose total equals its own size is therefore ambiguous
 * and must probe the next logical page instead of stopping early.
 */
internal fun nextSeriesPageKey(
    page: Int,
    requestedLimit: Int,
    provider: String,
    totalCount: Int,
    entriesCount: Int,
): Int? {
    if (entriesCount <= 0) return null

    val safePage = page.coerceAtLeast(1)
    val normalizedProvider = provider.trim().lowercase()
    val effectiveLimit = if (normalizedProvider == AUDIOBOO_PROVIDER) {
        requestedLimit.coerceIn(1, AUDIOBOO_LOGICAL_PAGE_SIZE)
    } else {
        requestedLimit.coerceAtLeast(1)
    }

    val pageLocalTotalFallback =
        normalizedProvider in PAGE_LOCAL_TOTAL_PROVIDERS &&
            totalCount == entriesCount &&
            entriesCount >= effectiveLimit

    val hasMore = if (totalCount > 0) {
        safePage.toLong() * effectiveLimit.toLong() < totalCount.toLong() || pageLocalTotalFallback
    } else {
        entriesCount >= effectiveLimit
    }

    if (!hasMore || safePage == Int.MAX_VALUE) return null
    return safePage + 1
}

private const val AUDIOBOO_PROVIDER = "audioboo"
private const val AUDIOBOO_LOGICAL_PAGE_SIZE = 12
private val PAGE_LOCAL_TOTAL_PROVIDERS = setOf("audiopolka", "uknig", "bazaknig")
