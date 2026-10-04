package com.example.data.parser

import android.content.Context
import com.example.data.api.ApiClient
import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesDetailDto
import com.example.data.model.SeriesEntryDto
import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Abred's on-device Audiopolka provider. */
internal class AbredAplParser(context: Context) {
    private val config = AbredAplConfig.load(context)
    private val http = ApiClient.createHttpClient()

    private data class DetailCacheEntry(val storedAtMs: Long, val detail: BookDetailDto)
    private data class HtmlCacheEntry(val storedAtMs: Long, val html: String)
    private data class CollectionPage(
        val name: String,
        val totalCount: Int,
        val items: List<LiveCatalogItemDto>,
    )

    private val detailCache = object : LinkedHashMap<String, DetailCacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DetailCacheEntry>?): Boolean =
            size > DETAIL_CACHE_MAX
    }
    private val collectionHtmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean =
            size > COLLECTION_CACHE_MAX
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> {
        val url = AbredAplHtmlParser.catalogPageUrl(config.baseUrl, page, config)
        return AbredAplHtmlParser.parseCatalog(fetchText(url), url, config)
    }

    suspend fun search(query: String): List<LiveCatalogItemDto> {
        val url = AbredAplHtmlParser.searchUrl(config.baseUrl, query, config)
        return AbredAplHtmlParser.parseCatalog(fetchText(url), url, config)
    }

    suspend fun genres(): List<GenreDto> {
        val url = config.baseUrl.trimEnd('/') + "/"
        return AbredAplHtmlParser.parseGenres(fetchCollectionText(url), url)
    }

    fun canBrowseAuthor(authorId: String): Boolean =
        AplParserSupport.parseEntityRef(authorId, "author") != null

    fun canBrowseNarrator(narratorId: String): Boolean =
        AplParserSupport.parseEntityRef(narratorId, "voice") != null

    fun canBrowseGenre(genreId: String): Boolean =
        AplParserSupport.parseEntityRef(genreId, "genre") != null

    suspend fun authorBooks(authorId: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AplParserSupport.parseEntityRef(authorId, "author")
            ?: error("Invalid Audiopolka author id: $authorId")
        return collection("author", externalId, page, limit).items
    }

    suspend fun narratorBooks(narratorId: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AplParserSupport.parseEntityRef(narratorId, "voice")
            ?: error("Invalid Audiopolka narrator id: $narratorId")
        return collection("voice", externalId, page, limit).items
    }

    suspend fun genreBooks(genreId: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AplParserSupport.parseEntityRef(genreId, "genre")
            ?: error("Invalid Audiopolka genre id: $genreId")
        return collection("genre", externalId, page, limit).items
    }

    suspend fun book(bookId: String): BookDetailDto {
        cachedDetail(bookId)?.let { return it }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == AUDIOPOLKA_SOURCE)
        val url = AbredAplHtmlParser.bookUrl(config.baseUrl, parsed.second)
        val detail = AbredAplHtmlParser.parseBook(fetchText(url), url, bookId, config)
        synchronized(detailCache) { detailCache[bookId] = DetailCacheEntry(monotonicMs(), detail) }
        return detail
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == AUDIOPOLKA_SOURCE && (requested.isBlank() || requested == AUDIOPOLKA_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) { "Audiopolka source series is unavailable for $bookId" }
        val detail = book(bookId)
        val membership = detail.audioSeries.firstOrNull { it.provider == AUDIOPOLKA_SOURCE }
        val externalId = membership?.externalId
            ?.takeIf(String::isNotBlank)
            ?: error("Audiopolka book has no source series id: $bookId")

        val collection = collection("series", externalId, page, limit)
        val seriesName = collection.name.ifBlank {
            membership.name.ifBlank { detail.sourceSeriesName.ifBlank { detail.seriesName } }
        }
        val cards = collection.items.map { it.toBookCard(seriesExternalId = externalId, seriesName = seriesName) }
        val entries = collection.items.mapIndexed { index, item ->
            val card = cards[index]
            SeriesEntryDto(
                externalWorkId = item.externalId,
                title = item.title,
                authors = item.authors,
                position = item.seriesPosition?.toDouble(),
                available = true,
                book = card,
            )
        }
        val total = collection.totalCount.coerceAtLeast(cards.size)
        return SeriesDetailDto(
            id = "source:$AUDIOPOLKA_SOURCE:$externalId",
            name = seriesName,
            kind = "source_series",
            provider = AUDIOPOLKA_SOURCE,
            externalId = externalId,
            booksCount = total,
            totalCount = total,
            books = cards,
            entries = entries,
        )
    }

    private suspend fun collection(
        kind: String,
        externalId: String,
        page: Int,
        limit: Int,
    ): CollectionPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val sitePageSize = config.listPageSize.coerceAtLeast(1)
        val window = physicalPageWindow(safePage, safeLimit, sitePageSize)
            ?: return CollectionPage(name = "", totalCount = 0, items = emptyList())
        var sitePage = window.page
        var skip = window.skip
        val result = ArrayList<LiveCatalogItemDto>(minOf(safeLimit, sitePageSize))
        var collectionName = ""
        var totalCount = 0

        while (result.size < safeLimit) {
            val url = AbredAplHtmlParser.collectionPageUrl(config.baseUrl, kind, externalId, sitePage)
            val html = fetchCollectionText(url)
            val metadata = AbredAplHtmlParser.parseCollectionMetadata(html, url, collectionName)
            if (collectionName.isBlank()) collectionName = metadata.name
            if (metadata.totalCount > totalCount) totalCount = metadata.totalCount
            val rows = AbredAplHtmlParser.parseCollectionCatalog(html, url, config)
            if (rows.isEmpty()) break

            val available = rows.drop(skip)
            if (available.isNotEmpty()) {
                result += available.take(safeLimit - result.size)
            }
            if (rows.size < sitePageSize) break
            sitePage = nextPhysicalPage(sitePage) ?: break
            skip = 0
        }
        return CollectionPage(
            name = collectionName,
            totalCount = totalCount,
            items = result,
        )
    }

    private fun LiveCatalogItemDto.toBookCard(
        seriesExternalId: String,
        seriesName: String,
    ): BookCardDto = BookCardDto(
        id = key,
        title = title,
        authors = authors.map { PersonDto(id = "", name = it) },
        narrators = narrators.map { PersonDto(id = "", name = it) },
        genres = genres.map { GenreDto(id = "", name = it) },
        coverUrl = coverUrl,
        durationSeconds = durationSeconds,
        rating = rating,
        sourceCodes = listOf(AUDIOPOLKA_SOURCE),
        primarySource = AUDIOPOLKA_SOURCE,
        sourceSeriesName = this.seriesName.ifBlank { seriesName },
        sourceSeriesPosition = seriesPosition,
        audioSeries = listOf(
            AudioSeriesBriefDto(
                id = "source:$AUDIOPOLKA_SOURCE:$seriesExternalId",
                name = this.seriesName.ifBlank { seriesName },
                position = seriesPosition?.toDouble(),
                provider = AUDIOPOLKA_SOURCE,
                externalId = seriesExternalId,
                sourceName = "Audiopolka",
            )
        ),
    )

    private fun cachedDetail(bookId: String): BookDetailDto? = synchronized(detailCache) {
        val row = detailCache[bookId] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > DETAIL_CACHE_TTL_MS) {
            detailCache.remove(bookId)
            null
        } else row.detail
    }

    private suspend fun fetchCollectionText(url: String): String {
        synchronized(collectionHtmlCache) {
            val cached = collectionHtmlCache[url]
            if (cached != null && monotonicMs() - cached.storedAtMs <= COLLECTION_CACHE_TTL_MS) {
                return cached.html
            }
            if (cached != null) collectionHtmlCache.remove(url)
        }
        val html = fetchText(url)
        synchronized(collectionHtmlCache) {
            collectionHtmlCache[url] = HtmlCacheEntry(monotonicMs(), html)
        }
        return html
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .header("Referer", config.referer)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Audiopolka HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Audiopolka returned an empty response for $url")
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DETAIL_CACHE_TTL_MS = 2 * 60 * 1000L
        const val DETAIL_CACHE_MAX = 96
        const val COLLECTION_CACHE_TTL_MS = 2 * 60 * 1000L
        const val COLLECTION_CACHE_MAX = 96
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/136 Safari/537.36"
    }
}
