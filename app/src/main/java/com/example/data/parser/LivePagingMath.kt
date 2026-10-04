package com.example.data.parser

internal data class PhysicalPageWindow(
    val page: Int,
    val skip: Int,
)

/**
 * Converts a logical Abred page into a physical provider page without allowing
 * Int arithmetic to wrap. Int inputs cannot overflow Long during multiplication.
 * A null result means the requested physical page no longer fits the provider's
 * Int-based URL contract and should be treated as an empty page.
 */
internal fun physicalPageWindow(
    page: Int,
    limit: Int,
    sitePageSize: Int,
): PhysicalPageWindow? {
    val safePage = page.coerceAtLeast(1)
    val safeLimit = limit.coerceAtLeast(1)
    val safeSitePageSize = sitePageSize.coerceAtLeast(1)
    val logicalOffset = (safePage.toLong() - 1L) * safeLimit.toLong()
    val zeroBasedSitePage = logicalOffset / safeSitePageSize.toLong()
    if (zeroBasedSitePage >= Int.MAX_VALUE.toLong()) return null

    return PhysicalPageWindow(
        page = (zeroBasedSitePage + 1L).toInt(),
        skip = (logicalOffset % safeSitePageSize.toLong()).toInt(),
    )
}

/** Returns null instead of wrapping Int.MAX_VALUE to a negative page. */
internal fun nextPhysicalPage(page: Int): Int? =
    if (page >= Int.MAX_VALUE) null else page + 1

/** Overflow-safe local list paging for providers that must materialize a cycle first. */
internal fun <T> List<T>.logicalPageSlice(page: Int, limit: Int): List<T> {
    val safePage = page.coerceAtLeast(1)
    val safeLimit = limit.coerceAtLeast(1)
    val offset = (safePage.toLong() - 1L) * safeLimit.toLong()
    if (offset >= size.toLong() || offset > Int.MAX_VALUE.toLong()) return emptyList()
    return drop(offset.toInt()).take(safeLimit)
}
