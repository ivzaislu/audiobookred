package com.example.data.parser

import com.example.data.model.LiveCatalogItemDto

/** Pure logical-page buffering over RuTracker physical search/forum pages. */
internal enum class RuTrackerSearchMode {
    SCOPED,
    FALLBACK,
}

internal class RuTrackerSearchCatalogBuffer {
    var mode: RuTrackerSearchMode? = null

    private var nextSitePage: Int = 1
    private var terminal: Boolean = false
    private val items: MutableList<LiveCatalogItemDto> = ArrayList()
    private val seenTopicIds: MutableSet<String> = linkedSetOf()

    suspend fun page(
        page: Int,
        limit: Int,
        loadPage: suspend (sitePage: Int) -> RuTrackerSearchPage,
    ): List<LiveCatalogItemDto> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val startLong = (safePage.toLong() - 1L) * safeLimit.toLong()
        if (startLong > Int.MAX_VALUE.toLong()) return emptyList()
        val start = startLong.toInt()
        val targetSize = (startLong + safeLimit.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        while (items.size < targetSize && !terminal) {
            val sitePage = nextSitePage
            val parsedPage = loadPage(sitePage)

            parsedPage.items.forEach { item ->
                if (seenTopicIds.add(item.externalId)) {
                    items += item
                }
            }

            val followingPage = parsedPage.nextPage
            if (followingPage == null || followingPage <= sitePage) {
                terminal = true
            } else {
                nextSitePage = followingPage
            }
        }

        return if (start >= items.size) {
            emptyList()
        } else {
            items.drop(start).take(safeLimit).toList()
        }
    }
}

internal data class RuTrackerSearchPage(
    val items: List<LiveCatalogItemDto>,
    val nextPage: Int?,
)

internal data class RuTrackerForumCatalogBuffer(
    var nextSitePage: Int = 1,
    var terminal: Boolean = false,
    val items: MutableList<LiveCatalogItemDto> = ArrayList(),
    val seenTopicIds: MutableSet<String> = linkedSetOf(),
)

internal data class RuTrackerForumPage(
    val items: List<LiveCatalogItemDto>,
    val nextPage: Int?,
    val forumId: Int? = null,
)

