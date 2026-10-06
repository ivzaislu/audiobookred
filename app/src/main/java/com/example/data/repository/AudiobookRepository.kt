package com.example.data.repository

import com.example.data.api.ApiClient
import com.example.data.model.*
import com.example.data.parser.AndroidLiveParserLocator
import com.example.data.source.StandaloneSourceRegistry
import com.example.data.torrserve.RuTrackerTorrServePlaybackResolver
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException

private const val LIVE_DOWNLOAD_PREFIX = "live:"

/**
 * Standalone content repository for selfapk.
 *
 * Every operation in this class is backed by an on-device public-source parser.
 * User state belongs to local Room/SharedPreferences stores and is intentionally
 * absent from this boundary.
 */
class AudiobookRepository(
    private val ruTrackerTorrServeResolver: RuTrackerTorrServePlaybackResolver? = null,
) {
    private data class SearchCacheEntry(
        val storedAtMs: Long,
        val items: List<LiveCatalogItemDto>,
    )

    private val searchCache = object : LinkedHashMap<String, SearchCacheEntry>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchCacheEntry>?): Boolean =
            size > SEARCH_CACHE_MAX
    }

    private val catalogBuffers = object : LinkedHashMap<String, CatalogPageBuffer<LiveCatalogItemDto>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CatalogPageBuffer<LiveCatalogItemDto>>?
        ): Boolean = size > CATALOG_BUFFER_MAX
    }

    private val aggregateCatalogBuffers = object : LinkedHashMap<String, AggregateCatalogPageBuffer<String, LiveCatalogItemDto>>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AggregateCatalogPageBuffer<String, LiveCatalogItemDto>>?
        ): Boolean = size > AGGREGATE_CATALOG_BUFFER_MAX
    }

    suspend fun catalog(
        page: Int = 1,
        limit: Int = 30,
        genreId: String? = null,
        source: String? = null,
    ): BookListResponse {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val selectedSource = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val selectedGenre = genreId?.trim()?.takeIf { it.isNotEmpty() }

        if (selectedGenre != null) {
            val local = localParser()
            require(local.canBrowseGenre(selectedGenre)) { "Локальный жанр не поддерживается: $selectedGenre" }
            val genreSource = selectedGenre.substringBefore(':').lowercase()
            require(selectedSource == null || selectedSource == genreSource) {
                "Жанр $selectedGenre не относится к источнику $selectedSource"
            }
            return localBookList(local.genreBooks(selectedGenre, safePage, safeLimit), safePage, safeLimit)
        }

        val buffered = if (selectedSource != null) {
            catalogBuffer("source:$selectedSource").page(safePage, safeLimit) { physicalPage ->
                val items = liveCatalogForSource(selectedSource, physicalPage)
                CatalogPhysicalBatch(
                    items = items,
                    terminal = items.isEmpty(),
                )
            }
        } else {
            val local = localParser()
            val sources = local.sources.toList()
            aggregateCatalogBuffer(sources, safeLimit).page(safePage) { sourceCode, physicalPage ->
                val items = local.catalog(sourceCode, physicalPage)
                CatalogPhysicalBatch(
                    items = items,
                    terminal = items.isEmpty(),
                )
            }
        }

        return BookListResponse(
            items = buffered.items.map(LiveCatalogItemDto::toBookCard),
            page = safePage,
            limit = safeLimit,
            total = buffered.total,
        )
    }

    suspend fun search(
        query: String,
        page: Int = 1,
        limit: Int = 30,
        source: String? = null,
        excludeSource: String? = null,
        seriesName: String? = null,
    ): BookListResponse {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return BookListResponse(page = safePage, limit = safeLimit, total = 0)
        }
        val selectedSource = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val excluded = excludeSource?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // Providers with real server-side search pagination stay one request per
        // visible page instead of loading multiple pages eagerly into the cache.
        if (StandaloneSourceRegistry.usesPagedSearch(selectedSource)) {
            if (selectedSource == excluded) {
                return BookListResponse(page = safePage, limit = safeLimit, total = 0)
            }
            val pageItems = liveSearchForSource(
                query = normalizedQuery,
                source = selectedSource!!,
                page = safePage,
                limit = safeLimit,
            )
                .take(safeLimit)
            return BookListResponse(
                items = pageItems.map(LiveCatalogItemDto::toBookCard),
                page = safePage,
                limit = safeLimit,
                total = 0,
            )
        }

        val cacheKey = searchCacheKey(normalizedQuery, selectedSource, excluded, seriesName)
        val allItems = cachedSearch(cacheKey) ?: run {
            val loaded = if (selectedSource != null) {
                if (selectedSource == excluded) emptyList()
                else liveSearchForSource(normalizedQuery, selectedSource)
            } else {
                aggregateSearch(normalizedQuery, excluded, seriesName)
            }
            storeSearch(cacheKey, loaded)
            loaded
        }
        val offset = (safePage.toLong() - 1L) * safeLimit.toLong()
        val pageItems = if (offset >= allItems.size.toLong() || offset > Int.MAX_VALUE) {
            emptyList()
        } else {
            allItems.drop(offset.toInt()).take(safeLimit)
        }
        return BookListResponse(
            items = pageItems.map(LiveCatalogItemDto::toBookCard),
            page = safePage,
            limit = safeLimit,
            total = allItems.size,
        )
    }

    suspend fun searchOtherSources(
        query: String,
        excludedSource: String,
        seriesName: String? = null,
        limit: Int = 30,
    ): BookListResponse = search(
        query = query,
        page = 1,
        limit = limit,
        source = null,
        excludeSource = excludedSource,
        seriesName = seriesName,
    )

    /** Knigavuhe discovery feed for the Home screen. */
    suspend fun homeKnigavuhe(section: String, limit: Int = 12): BookListResponse {
        val safeLimit = limit.coerceAtLeast(1)
        val items = localParser().knigavuheHome(section)
        val cards = items
            .map(LiveCatalogItemDto::toBookCard)
            .distinctBy(BookCardDto::id)
            .take(safeLimit)
        return BookListResponse(
            items = cards,
            page = 1,
            limit = safeLimit,
            total = items.size,
        )
    }

    suspend fun book(id: String, source: String? = null): BookDetailDto = localBook(id, source)

    suspend fun sourceSeries(bookId: String, provider: String? = null): SeriesDetailDto {
        val local = localParser()
        require(local.canLoadSourceSeries(bookId, provider)) {
            "Локальный parser не умеет загрузить цикл для $bookId (${provider.orEmpty()})"
        }
        return local.sourceSeries(bookId, provider, page = 1, limit = 30)
    }

    suspend fun sourceSeriesPage(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        val local = localParser()
        require(local.canLoadSourceSeries(bookId, provider)) {
            "Локальный parser не умеет загрузить цикл для $bookId (${provider.orEmpty()})"
        }
        return local.sourceSeries(bookId, provider, page, limit)
    }

    suspend fun authorBooks(id: String, page: Int = 1, limit: Int = 30): BookListResponse {
        val local = localParser()
        require(local.canBrowseAuthor(id)) { "Локальный parser не умеет открывать автора: $id" }
        return localBookList(local.authorBooks(id, page, limit), page, limit)
    }

    suspend fun narratorBooks(id: String, page: Int = 1, limit: Int = 30): BookListResponse {
        val local = localParser()
        require(local.canBrowseNarrator(id)) { "Локальный parser не умеет открывать чтеца: $id" }
        return localBookList(local.narratorBooks(id, page, limit), page, limit)
    }

    suspend fun genreBooks(id: String, page: Int = 1, limit: Int = 30): BookListResponse {
        val local = localParser()
        require(local.canBrowseGenre(id)) { "Локальный parser не умеет открывать жанр: $id" }
        return localBookList(local.genreBooks(id, page, limit), page, limit)
    }

    /** Builds a download manifest entirely from the selected on-device parser result. */
    suspend fun downloadManifest(bookSourceId: String): DownloadManifestDto {
        val key = localBookKeyForDownload(bookSourceId)
        val parsedBook = localBook(key.bookKey, key.source)
        val ruTrackerResolution = if (key.source == "rutracker") {
            requireNotNull(ruTrackerTorrServeResolver) {
                "TorrServe download resolver не инициализирован"
            }.resolveForDownload(parsedBook)
        } else {
            null
        }
        val book = ruTrackerResolution?.book ?: parsedBook
        val torrServeFiles = ruTrackerResolution?.files.orEmpty()

        val files = book.chapters.sortedBy { it.position }.mapIndexed { index, chapter ->
            val torrServeFile = torrServeFiles.getOrNull(index)
            val streamUrl = if (key.source == "rutracker") {
                chapter.streamUrl.trim().takeIf(String::isNotBlank)
            } else {
                ApiClient.externalHttpUrl(chapter.streamUrl)
            }
            require(streamUrl != null) {
                "${sourceName(key.source)}: у главы нет корректного URL для скачивания: ${chapter.id}"
            }

            val chapterId = chapter.id.ifBlank { "${key.bookKey}:chapter:${index + 1}" }
            DownloadFileDto(
                fileId = chapterId,
                chapterId = chapterId,
                chapterPosition = chapter.position.coerceAtLeast(index),
                title = chapter.title.ifBlank { "Часть ${index + 1}" },
                durationSeconds = chapter.durationSeconds.coerceAtLeast(0L),
                filename = if (torrServeFile != null) {
                    torrServeDownloadFilename(index, torrServeFile)
                } else {
                    downloadFilename(index, chapter)
                },
                mediaType = torrServeFile?.let(::torrServeMediaType) ?: "audio/mpeg",
                sizeBytes = torrServeFile?.length?.takeIf { it > 0L },
                delivery = if (key.source == "rutracker") "torrserve" else "direct",
                downloadUrl = streamUrl,
            )
        }
        require(files.isNotEmpty()) { "${sourceName(key.source)} не вернул аудиофайлы для скачивания" }

        val allSizesKnown = files.all { (it.sizeBytes ?: 0L) > 0L }
        val totalSize = if (allSizesKnown) files.sumOf { it.sizeBytes ?: 0L } else null
        return DownloadManifestDto(
            manifestVersion = 1,
            manifestId = localManifestId(bookSourceId, book),
            bookId = book.id,
            bookSourceId = bookSourceId,
            sourceCode = key.source,
            sourceName = sourceName(key.source),
            title = book.title,
            coverUrl = book.coverUrl,
            durationSeconds = book.durationSeconds,
            filesCount = files.size,
            totalSizeBytes = totalSize,
            sizeComplete = allSizesKnown,
            files = files,
        )
    }

    private fun localParser() = AndroidLiveParserLocator.instanceOrNull()
        ?: error("Локальные парсеры ещё не инициализированы")

    private suspend fun liveCatalogForSource(
        source: String,
        page: Int,
    ): List<LiveCatalogItemDto> {
        val local = localParser()
        require(local.supportsSource(source)) { "Локальный parser недоступен для $source" }
        return local.catalog(source, page)
    }

    private suspend fun liveSearchForSource(
        query: String,
        source: String,
        page: Int = 1,
        limit: Int = 50,
    ): List<LiveCatalogItemDto> {
        val local = localParser()
        require(local.supportsSource(source)) { "Локальный parser недоступен для $source" }
        return local.search(source, query, page, limit)
    }

    private fun catalogBuffer(key: String): CatalogPageBuffer<LiveCatalogItemDto> =
        synchronized(catalogBuffers) {
            catalogBuffers.getOrPut(key) { CatalogPageBuffer() }
        }

    private fun aggregateCatalogBuffer(
        sources: List<String>,
        limit: Int,
    ): AggregateCatalogPageBuffer<String, LiveCatalogItemDto> {
        val key = "$limit|${sources.joinToString("|")}"
        return synchronized(aggregateCatalogBuffers) {
            aggregateCatalogBuffers.getOrPut(key) {
                AggregateCatalogPageBuffer(
                    providers = sources,
                    pageSize = limit,
                )
            }
        }
    }

    private fun searchCacheKey(
        query: String,
        source: String?,
        excludeSource: String?,
        seriesName: String?,
    ): String = buildString {
        append(source ?: "*")
        append('|').append(excludeSource.orEmpty())
        append('|').append(normalizeSearchValue(seriesName.orEmpty()))
        append('|').append(normalizeSearchValue(query))
    }

    private fun cachedSearch(key: String): List<LiveCatalogItemDto>? = synchronized(searchCache) {
        val row = searchCache[key] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > SEARCH_CACHE_TTL_MS) {
            searchCache.remove(key)
            null
        } else {
            row.items
        }
    }

    private fun storeSearch(key: String, items: List<LiveCatalogItemDto>) {
        synchronized(searchCache) {
            searchCache[key] = SearchCacheEntry(monotonicMs(), items)
        }
    }

    /** One broken provider must not erase healthy providers from an aggregate page/search. */
    private suspend fun <T> providerOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun aggregateSearch(
        query: String,
        excludeSource: String? = null,
        seriesName: String? = null,
    ): List<LiveCatalogItemDto> {
        val local = localParser()
        val sourceCodes = local.sources.filterNot { it == excludeSource }
        val rows = boundedProviderMap(
            values = sourceCodes,
            limiter = AGGREGATE_SEARCH_LIMITER,
        ) { source ->
            providerOrNull { local.search(source, query) }.orEmpty()
        }
        val interleaved = interleaveAll(rows)
        val targetSeries = normalizeSearchValue(seriesName.orEmpty())
        return if (targetSeries.isBlank()) {
            interleaved
        } else {
            interleaved.sortedBy { item ->
                val candidate = normalizeSearchValue(item.seriesName)
                when {
                    candidate == targetSeries -> 0
                    candidate.contains(targetSeries) || targetSeries.contains(candidate) -> 1
                    else -> 2
                }
            }
        }
    }

    private suspend fun localBook(id: String, source: String? = null): BookDetailDto {
        val local = localParser()
        require(local.canParseBook(id, source)) { "selfapk не может открыть локальную книгу: $id" }
        return local.book(id)
    }

    private fun localBookList(
        items: List<LiveCatalogItemDto>,
        page: Int,
        limit: Int,
    ): BookListResponse = BookListResponse(
        items = items.map(LiveCatalogItemDto::toBookCard),
        page = page,
        limit = limit,
        total = 0,
    )

    private data class LocalDownloadKey(val source: String, val externalId: String) {
        val bookKey: String get() = "$source:$externalId"
    }

    private fun localBookKeyForDownload(bookSourceId: String): LocalDownloadKey {
        require(bookSourceId.startsWith(LIVE_DOWNLOAD_PREFIX)) {
            "selfapk скачивает только локально разобранные источники: $bookSourceId"
        }
        val payload = bookSourceId.removePrefix(LIVE_DOWNLOAD_PREFIX)
        val separator = payload.indexOf(':')
        require(separator > 0 && separator < payload.lastIndex) { "Некорректный source id загрузки: $bookSourceId" }
        val source = payload.substring(0, separator).trim().lowercase()
        val externalId = payload.substring(separator + 1).trim()
        require(localParser().supportsSource(source)) { "Источник загрузки не поддерживается локально: $source" }
        require(externalId.isNotBlank()) { "Некорректный source id загрузки: $bookSourceId" }
        return LocalDownloadKey(source, externalId)
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val SEARCH_CACHE_TTL_MS = 2 * 60 * 1000L
        const val SEARCH_CACHE_MAX = 24
        const val CATALOG_BUFFER_MAX = 8
        const val AGGREGATE_CATALOG_BUFFER_MAX = 4
        const val AGGREGATE_SEARCH_CONCURRENCY = 3

        val AGGREGATE_SEARCH_LIMITER =
            AggregateSearchLimiter(AGGREGATE_SEARCH_CONCURRENCY)
    }
}
