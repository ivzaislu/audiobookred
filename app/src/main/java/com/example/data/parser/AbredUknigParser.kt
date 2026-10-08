package com.example.data.parser

import com.example.data.api.ApiClient
import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesDetailDto
import com.example.data.model.SeriesEntryDto
import com.example.data.model.SourceVariantDto
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal class PreviewOnlyUknigBook(
    val previewBook: BookDetailDto? = null,
    message: String = "uknig_preview_only",
) : IOException(message)

internal class UnavailableUknigBook(message: String = "uknig_unavailable") : IOException(message)

/** Fast on-device уКниг provider used by Abred. */
internal class AbredUknigParser {
    private val http = ApiClient.createHttpClient()

    private data class CacheEntry(val storedAtMs: Long, val detail: BookDetailDto)
    private data class HtmlCacheEntry(val storedAtMs: Long, val html: String)
    private data class CollectionPage(
        val name: String,
        val totalCount: Int,
        val items: List<LiveCatalogItemDto>,
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > DETAIL_CACHE_MAX
    }
    private val htmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean =
            size > HTML_CACHE_MAX
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> {
        val url = AbredUknigHtmlParser.catalogPageUrl(page)
        return AbredUknigHtmlParser.parseCatalog(fetchText(url), url)
    }

    suspend fun search(query: String): List<LiveCatalogItemDto> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val url = AbredUknigHtmlParser.searchUrl(normalized)
        return AbredUknigHtmlParser.parseCatalog(fetchText(url), url)
    }

    suspend fun genres(): List<GenreDto> {
        val url = "$UKNIG_BASE_URL/genres"
        return AbredUknigHtmlParser.parseGenres(fetchCachedText(url), url)
    }

    fun canBrowseAuthor(id: String): Boolean = AbredUknigHtmlParser.parseEntityRef(id, "author") != null
    fun canBrowseNarrator(id: String): Boolean = AbredUknigHtmlParser.parseEntityRef(id, "reader") != null
    fun canBrowseGenre(id: String): Boolean = AbredUknigHtmlParser.parseEntityRef(id, "genre") != null

    suspend fun authorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val slug = AbredUknigHtmlParser.parseEntityRef(id, "author")
            ?: error("Invalid уКниг author id: $id")
        return collection("authors", slug, page, limit).items
    }

    suspend fun narratorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val slug = AbredUknigHtmlParser.parseEntityRef(id, "reader")
            ?: error("Invalid уКниг reader id: $id")
        return collection("readers", slug, page, limit).items
    }

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val slug = AbredUknigHtmlParser.parseEntityRef(id, "genre")
            ?: error("Invalid уКниг genre id: $id")
        return collection("genres", slug, page, limit).items
    }

    suspend fun book(bookId: String): BookDetailDto {
        cached(bookId)?.let { return it }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == UKNIG_SOURCE) { "Not a уКниг key: $bookId" }
        val externalId = parsed.second
        val bookUrl = AbredUknigHtmlParser.bookUrl(externalId)
        val detailHtml = fetchText(bookUrl)
        val metadata = AbredUknigHtmlParser.parseMetadata(detailHtml, bookUrl, bookId)

        if (AbredUknigHtmlParser.isPreviewPage(detailHtml, bookUrl)) {
            throw PreviewOnlyUknigBook(metadata)
        }

        val playlistText = fetchPlaylist(externalId, bookUrl)
        val chapters = AbredUknigHtmlParser.parsePlaylist(playlistText, bookId)
        val detail = AbredUknigHtmlParser.attachChapters(metadata, chapters)
        synchronized(cache) { cache[bookId] = CacheEntry(monotonicMs(), detail) }
        return detail
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == UKNIG_SOURCE && (requested.isBlank() || requested == UKNIG_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) { "уКниг source series is unavailable for $bookId" }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        val detail = cached(bookId) ?: run {
            val bookUrl = AbredUknigHtmlParser.bookUrl(parsed.second)
            AbredUknigHtmlParser.parseMetadata(fetchText(bookUrl), bookUrl, bookId)
        }
        val membership = detail.audioSeries.firstOrNull { it.provider == UKNIG_SOURCE }
        val externalId = membership?.externalId?.takeIf(String::isNotBlank)
            ?: error("уКниг book has no source series id: $bookId")
        val collection = collection("series", externalId, page, limit)
        val name = collection.name.ifBlank {
            membership.name.ifBlank { detail.sourceSeriesName.ifBlank { detail.seriesName } }
        }
        val cards = collection.items.map { it.toBookCard(externalId, name) }
        val entries = collection.items.mapIndexed { index, item ->
            SeriesEntryDto(
                externalWorkId = item.externalId,
                title = item.title,
                authors = item.authors,
                position = item.seriesPosition?.toDouble(),
                available = true,
                book = cards[index],
            )
        }
        val total = collection.totalCount.coerceAtLeast(cards.size)
        return SeriesDetailDto(
            id = "source:$UKNIG_SOURCE:$externalId",
            name = name,
            kind = "source_series",
            provider = UKNIG_SOURCE,
            booksCount = total,
            totalCount = total,
            books = cards,
            entries = entries,
        )
    }

    private suspend fun collection(kind: String, externalId: String, page: Int, limit: Int): CollectionPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val window = physicalPageWindow(safePage, safeLimit, UKNIG_COLLECTION_PAGE_SIZE)
            ?: return CollectionPage(name = "", totalCount = 0, items = emptyList())
        var sitePage = window.page
        var skip = window.skip
        val result = ArrayList<LiveCatalogItemDto>(minOf(safeLimit, UKNIG_COLLECTION_PAGE_SIZE))
        var name = ""
        var totalCount = 0

        while (result.size < safeLimit) {
            val url = AbredUknigHtmlParser.collectionPageUrl(kind, externalId, sitePage)
            val html = fetchCachedText(url)
            val metadata = AbredUknigHtmlParser.parseCollectionMetadata(html, url, name)
            if (name.isBlank()) name = metadata.first
            totalCount = maxOf(totalCount, metadata.second)
            val rows = AbredUknigHtmlParser.parseCatalog(html, url)
            if (rows.isEmpty()) break
            result += rows.drop(skip).take(safeLimit - result.size)
            if (rows.size < UKNIG_COLLECTION_PAGE_SIZE) break
            sitePage = nextPhysicalPage(sitePage) ?: break
            skip = 0
        }
        return CollectionPage(name, totalCount, result)
    }

    private fun LiveCatalogItemDto.toBookCard(seriesExternalId: String, fallbackSeriesName: String): BookCardDto =
        BookCardDto(
            id = key,
            title = title,
            authors = authors.map { PersonDto("", it) },
            narrators = narrators.map { PersonDto("", it) },
            genres = genres.map { GenreDto("", it) },
            coverUrl = coverUrl,
            durationSeconds = durationSeconds,
            sourceCodes = listOf(UKNIG_SOURCE),
            primarySource = UKNIG_SOURCE,
            sourceSeriesName = seriesName.ifBlank { fallbackSeriesName },
            sourceSeriesPosition = seriesPosition,
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:$UKNIG_SOURCE:$seriesExternalId",
                    name = seriesName.ifBlank { fallbackSeriesName },
                    position = seriesPosition?.toDouble(),
                    provider = UKNIG_SOURCE,
                    externalId = seriesExternalId,
                    sourceName = "уКниг",
                )
            ),
        )

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("уКниг HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("уКниг returned an empty response for $url")
        }
    }

    private suspend fun fetchCachedText(url: String): String {
        synchronized(htmlCache) {
            val cached = htmlCache[url]
            if (cached != null && monotonicMs() - cached.storedAtMs <= HTML_CACHE_TTL_MS) return cached.html
            if (cached != null) htmlCache.remove(url)
        }
        val html = fetchText(url)
        synchronized(htmlCache) { htmlCache[url] = HtmlCacheEntry(monotonicMs(), html) }
        return html
    }

    private suspend fun fetchPlaylist(externalId: String, bookUrl: String): String = withContext(Dispatchers.IO) {
        val url = "$UKNIG_BASE_URL/index.php/books/$externalId/playlist.txt"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .header("Referer", bookUrl)
            .build()
        http.newCall(request).execute().use { response ->
            uknigPlaylistHttpFailure(response.code, bookUrl)?.let { throw it }
            response.body?.string() ?: throw IOException("уКниг returned an empty playlist for $bookUrl")
        }
    }

    private fun cached(bookId: String): BookDetailDto? = synchronized(cache) {
        val row = cache[bookId] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > DETAIL_CACHE_TTL_MS) {
            cache.remove(bookId)
            null
        } else row.detail
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DETAIL_CACHE_TTL_MS = 2 * 60 * 1000L
        const val DETAIL_CACHE_MAX = 96
        const val HTML_CACHE_TTL_MS = 2 * 60 * 1000L
        const val HTML_CACHE_MAX = 96
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/136 Safari/537.36 AbredAndroid"
    }
}

internal fun uknigPlaylistHttpFailure(code: Int, bookUrl: String): IOException? = when {
    code in 200..299 -> null
    code == 404 || code == 410 -> UnavailableUknigBook("uknig_playlist_unavailable")
    else -> IOException("уКниг playlist HTTP $code for $bookUrl")
}

internal const val UKNIG_SOURCE = "uknig"
internal const val UKNIG_BASE_URL = "https://uknig.com"
internal const val UKNIG_COLLECTION_PAGE_SIZE = 18