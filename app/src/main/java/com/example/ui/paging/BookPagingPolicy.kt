package com.example.ui.paging

/** Overflow-safe next-key decision shared by large book lists. */
internal fun nextBookPageKey(
    page: Int,
    limit: Int,
    total: Int,
    itemsCount: Int,
): Int? {
    if (itemsCount <= 0) return null

    val safePage = page.coerceAtLeast(1)
    val safeLimit = limit.coerceAtLeast(1)
    val reachedKnownEnd = total > 0 &&
        safePage.toLong() * safeLimit.toLong() >= total.toLong()

    if (reachedKnownEnd || safePage == Int.MAX_VALUE) return null
    return safePage + 1
}
