package com.example.data.parser

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
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Runtime wrapper for the current knigavuhe.org routing/player contract.
 *
 * Knigavuhe mixes three pagination shapes: catalog/search use query parameters,
 * author/reader/genre pages use path pagination (`/.../2/`), and cycle pages are
 * seen under both `/serie/<slug>/` and `/series/<slug>/`. Detail stays one HTTP
 * request and a cycle opened from an already loaded book reuses cached metadata.
 */
internal class AbredKnigavuheParserV2 {
    private data class CacheEntry(val storedAtMs: Long, val detail: BookDetailDto)
    private data class HtmlCacheEntry(val storedAtMs: Long, val html: String)
    private data class SeriesPageCacheEntry(
        val storedAtMs: Long,
        val url: String,
        val html: String,
    )

    private val http = ApiClient.createHttpClient()
    private val detailCache = object : LinkedHashMap<String, CacheEntry>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > DETAIL_CACHE_MAX
    }
    private val htmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean =
            size > HTML_CACHE_MAX
    }
    private val seriesPageCache = object : LinkedHashMap<String, SeriesPageCacheEntry>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeriesPageCacheEntry>?): Boolean =
            size > SERIES_PAGE_CACHE_MAX
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> {
        val url = AbredKnigavuheHtmlParser.catalogPageUrl(page)
        return AbredKnigavuheHtmlParser.parseCatalog(fetchText(url), url)
    }

    /** First-page discovery shelves used by Home. */
    suspend fun homeSection(section: String): List<LiveCatalogItemDto> {
        val url = homeSectionUrl(section)
        return AbredKnigavuheHtmlParser.parseCatalog(fetchText(url), url)
    }

    suspend fun search(query: String, page: Int = 1): List<LiveCatalogItemDto> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val url = searchPageUrl(clean, page)
        return AbredKnigavuheHtmlParser.parseCatalog(fetchText(url), url)
    }

    suspend fun genres(): List<GenreDto> {
        val url = "$KNIGAVUHE_BASE_URL/genres/"
        return AbredKnigavuheHtmlParser.parseGenres(fetchCachedText(url), url)
    }

    fun canBrowseAuthor(id: String): Boolean =
        AbredKnigavuheHtmlParser.parseEntityRef(id, "author") != null

    fun canBrowseNarrator(id: String): Boolean =
        AbredKnigavuheHtmlParser.parseEntityRef(id, "reader") != null

    fun canBrowseGenre(id: String): Boolean =
        AbredKnigavuheHtmlParser.parseEntityRef(id, "genre") != null

    suspend fun authorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        collection("author", id, page, limit)

    suspend fun narratorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        collection("reader", id, page, limit)

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        collection("genre", id, page, limit)

    suspend fun book(bookId: String): BookDetailDto {
        cached(bookId)?.let { return it }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == KNIGAVUHE_SOURCE) { "Not a Knigavuhe key: $bookId" }
        val url = AbredKnigavuheHtmlParser.bookUrl(parsed.second)
        val html = fetchText(url)
        val metadata = AbredKnigavuheHtmlParser.parseMetadata(html, url, bookId)
        if (AbredKnigavuheHtmlParser.isPaidPage(html, url)) {
            throw PreviewOnlyKnigavuheBook(metadata, "knigavuhe_litres_only")
        }
        val chapters = try {
            AbredKnigavuheHtmlParser.parsePlaylist(activePlayerHtml(html), url, bookId)
        } catch (preview: PreviewOnlyKnigavuheBook) {
            throw PreviewOnlyKnigavuheBook(metadata, preview.message ?: "knigavuhe_preview_only")
        }
        if (AbredKnigavuheHtmlParser.isLikelyPreview(metadata.durationSeconds, chapters)) {
            throw PreviewOnlyKnigavuheBook(metadata, "knigavuhe_preview_only")
        }
        val resolvedDuration = resolvedBookDuration(
            declaredDurationSeconds = metadata.durationSeconds,
            chapterDurationsSeconds = chapters.map { it.durationSeconds },
        )
        return metadata.copy(
            durationSeconds = resolvedDuration,
            sourceVariants = metadata.sourceVariants.map { variant ->
                variant.copy(
                    chaptersCount = chapters.size,
                    durationSeconds = resolvedDuration,
                )
            },
            chapters = chapters,
        ).also { detail ->
            synchronized(detailCache) { detailCache[bookId] = CacheEntry(monotonicMs(), detail) }
        }
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == KNIGAVUHE_SOURCE &&
            (requested.isBlank() || requested == KNIGAVUHE_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) { "Knigavuhe source series is unavailable for $bookId" }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid Knigavuhe book id: $bookId")
        val metadata = cached(bookId) ?: run {
            val bookUrl = AbredKnigavuheHtmlParser.bookUrl(parsed.second)
            AbredKnigavuheHtmlParser.parseMetadata(fetchText(bookUrl), bookUrl, bookId)
        }
        val membership = metadata.audioSeries.firstOrNull { it.provider == KNIGAVUHE_SOURCE }
            ?: error("Knigavuhe book has no source series id: $bookId")
        val externalId = membership.externalId.takeIf(String::isNotBlank)
            ?: error("Knigavuhe book has no source series id: $bookId")
        val (seriesUrl, seriesHtml) = fetchSeriesPage(externalId)
        val allItems = AbredKnigavuheHtmlParser.parseCatalog(seriesHtml, seriesUrl)
        val exactPositions = seriesPositions(seriesHtml, seriesUrl)
        val declaredCount = seriesDeclaredCount(seriesHtml, seriesUrl)
        val seriesName = AbredKnigavuheHtmlParser.parseCollectionName(seriesHtml, seriesUrl)
            .ifBlank { membership.name }
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val offset = (safePage.toLong() - 1L) * safeLimit.toLong()
        val pageItems = if (offset >= allItems.size.toLong() || offset > Int.MAX_VALUE) {
            emptyList()
        } else {
            allItems.drop(offset.toInt()).take(safeLimit)
        }
        val cards = pageItems.map { item ->
            item.toSeriesBookCard(externalId, seriesName, exactPositions[item.externalId])
        }
        val entries = pageItems.mapIndexed { index, item ->
            val exactPosition = exactPositions[item.externalId] ?: item.seriesPosition?.toDouble()
            SeriesEntryDto(
                externalWorkId = item.externalId,
                title = seriesEntryTitle(item.title, exactPosition),
                authors = item.authors,
                position = exactPosition,
                available = true,
                book = cards[index],
            )
        }
        val totalCount = declaredCount.coerceAtLeast(allItems.size)
        return SeriesDetailDto(
            id = "source:$KNIGAVUHE_SOURCE:$externalId",
            name = seriesName,
            kind = "source_series",
            provider = KNIGAVUHE_SOURCE,
            externalId = externalId,
            booksCount = allItems.size,
            totalCount = totalCount,
            books = cards,
            entries = entries,
        )
    }

    private suspend fun fetchSeriesPage(externalId: String): Pair<String, String> {
        val cacheKey = externalId.trim('/')
        cachedSeriesPage(cacheKey)?.let { cached ->
            return cached.url to cached.html
        }

        var lastError: IOException? = null
        for (url in seriesPageUrls(cacheKey)) {
            try {
                val html = fetchText(url)
                synchronized(seriesPageCache) {
                    seriesPageCache[cacheKey] = SeriesPageCacheEntry(monotonicMs(), url, html)
                }
                return url to html
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("Knigavuhe cycle is unavailable: $externalId")
    }

    private suspend fun collection(
        kind: String,
        id: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> {
        val slug = AbredKnigavuheHtmlParser.parseEntityRef(id, kind)
            ?: error("Invalid Knigavuhe $kind id: $id")
        val safeLimit = limit.coerceAtLeast(1)
        val window = collectionPageWindow(page, safeLimit) ?: return emptyList()
        var sitePage = window.page
        var skip = window.skip
        val result = ArrayList<LiveCatalogItemDto>(safeLimit)

        while (result.size < safeLimit) {
            val url = collectionPageUrl(kind, slug, sitePage)
            val html = fetchText(url)
            val rows = AbredKnigavuheHtmlParser.parseCatalog(html, url)
            val available = rows.drop(skip)
            if (available.isNotEmpty()) result += available.take(safeLimit - result.size)
            if (result.size >= safeLimit) break

            val nextPage = nextPhysicalPage(sitePage) ?: break
            val nextUrl = collectionPageUrl(kind, slug, nextPage)
            if (!hasPageLink(html, url, nextUrl)) break
            sitePage = nextPage
            skip = 0
        }
        return result
    }

    private fun LiveCatalogItemDto.toSeriesBookCard(
        seriesExternalId: String,
        fallbackSeriesName: String,
        exactPosition: Double?,
    ): BookCardDto {
        val resolvedSeriesName = seriesName.ifBlank { fallbackSeriesName }
        return BookCardDto(
            id = key,
            title = seriesEntryTitle(title, exactPosition),
            authors = authors.map { PersonDto("", it) },
            narrators = narrators.map { PersonDto("", it) },
            genres = genres.map { GenreDto("", it) },
            coverUrl = coverUrl,
            durationSeconds = durationSeconds,
            rating = rating,
            sourceCodes = listOf(KNIGAVUHE_SOURCE),
            primarySource = KNIGAVUHE_SOURCE,
            sourceSeriesName = resolvedSeriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:$KNIGAVUHE_SOURCE:$seriesExternalId",
                    name = resolvedSeriesName,
                    position = exactPosition ?: seriesPosition?.toDouble(),
                    provider = KNIGAVUHE_SOURCE,
                    externalId = seriesExternalId,
                    sourceName = "Книга в ухе",
                )
            ),
        )
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .header("Referer", "$KNIGAVUHE_BASE_URL/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Knigavuhe HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Knigavuhe returned an empty response for $url")
        }
    }

    private suspend fun fetchCachedText(url: String): String {
        synchronized(htmlCache) {
            val row = htmlCache[url]
            if (row != null && monotonicMs() - row.storedAtMs <= HTML_CACHE_TTL_MS) {
                return row.html
            }
            if (row != null) htmlCache.remove(url)
        }
        val html = fetchText(url)
        synchronized(htmlCache) { htmlCache[url] = HtmlCacheEntry(monotonicMs(), html) }
        return html
    }

    private fun cached(bookId: String): BookDetailDto? = synchronized(detailCache) {
        val row = detailCache[bookId] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > DETAIL_CACHE_TTL_MS) {
            detailCache.remove(bookId)
            null
        } else row.detail
    }

    private fun cachedSeriesPage(cacheKey: String): SeriesPageCacheEntry? = synchronized(seriesPageCache) {
        val row = seriesPageCache[cacheKey] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > SERIES_PAGE_CACHE_TTL_MS) {
            seriesPageCache.remove(cacheKey)
            null
        } else row
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        internal fun homeSectionUrl(section: String): String = when (section.trim().lowercase()) {
            HOME_SECTION_NEW -> "$KNIGAVUHE_BASE_URL/new/"
            HOME_SECTION_POPULAR_TODAY -> "$KNIGAVUHE_BASE_URL/popular/?w=today"
            HOME_SECTION_POPULAR_WEEK -> "$KNIGAVUHE_BASE_URL/popular/?w=week"
            HOME_SECTION_POPULAR_MONTH -> "$KNIGAVUHE_BASE_URL/popular/?w=month"
            else -> error("Unsupported Knigavuhe Home section: $section")
        }

        internal fun searchPageUrl(query: String, page: Int): String {
            val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
            val safePage = page.coerceAtLeast(1)
            return if (safePage == 1) {
                "$KNIGAVUHE_BASE_URL/search/?q=$encoded"
            } else {
                "$KNIGAVUHE_BASE_URL/search/?page=$safePage&q=$encoded"
            }
        }

        internal fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
            require(kind in setOf("author", "reader", "genre")) { "Unsupported Knigavuhe collection: $kind" }
            val root = "$KNIGAVUHE_BASE_URL/$kind/${externalId.trim('/')}/"
            val safePage = page.coerceAtLeast(1)
            return if (safePage == 1) root else "$root$safePage/"
        }

        internal fun collectionPageWindow(page: Int, limit: Int): PhysicalPageWindow? =
            physicalPageWindow(
                page = page.coerceAtLeast(1),
                limit = limit.coerceAtLeast(1),
                sitePageSize = KNIGAVUHE_COLLECTION_PAGE_SIZE,
            )

        internal fun seriesPageUrl(externalId: String): String = seriesPageUrls(externalId).first()

        internal fun seriesPageUrls(externalId: String): List<String> {
            val slug = externalId.trim('/')
            return listOf(
                "$KNIGAVUHE_BASE_URL/serie/$slug/",
                "$KNIGAVUHE_BASE_URL/series/$slug/",
            )
        }

        internal fun seriesPositions(rawHtml: String, pageUrl: String): Map<String, Double> {
            val document = Jsoup.parse(rawHtml, pageUrl)
            val result = linkedMapOf<String, Double>()
            for (card in document.select("div.bookkitem")) {
                if (card.selectFirst(".bookkitem_litres_icon") != null) continue
                val link = card.selectFirst("a.bookkitem_name[href], a.bookkitem_cover[href]") ?: continue
                val externalUrl = link.absUrl("href").ifBlank { runCatching { URI(pageUrl).resolve(link.attr("href")).toString() }.getOrDefault("") }
                val externalId = bookExternalIdForSeries(externalUrl)
                if (externalId.isBlank()) continue
                val position = card.selectFirst("span.bookkitem_serie_index")
                    ?.text()
                    ?.trim()
                    ?.trim('.')
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
                    ?: continue
                result[externalId] = position
            }
            return result
        }

        internal fun seriesDeclaredCount(rawHtml: String, pageUrl: String): Int {
            val heading = Jsoup.parse(rawHtml, pageUrl).selectFirst("h1")?.text().orEmpty()
            val raw = Regex("(\\d[\\d\\s]*)\\s+книг(?:а|и)?\\b", RegexOption.IGNORE_CASE)
                .find(heading)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .filter(Char::isDigit)
            return raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
        }

        internal fun seriesEntryTitle(title: String, position: Double?): String {
            if (position == null) return title
            val number = if (position % 1.0 == 0.0) position.toInt().toString() else position.toString()
            return title.trim()
                .removePrefix("$number. ")
                .removePrefix("$number ")
                .trim()
        }

        internal fun resolvedBookDuration(
            declaredDurationSeconds: Long,
            chapterDurationsSeconds: Iterable<Long>,
        ): Long {
            val declared = declaredDurationSeconds.coerceAtLeast(0L)
            val playlist = chapterDurationsSeconds.sumOf { it.coerceAtLeast(0L) }
            if (playlist <= 0L) return declared
            if (declared <= 0L) return playlist

            // Detail text is not authoritative when its parsed value strongly
            // disagrees with the actual BookPlayer timeline. True Crime pages,
            // for example, expose mm:ss metadata while the legacy detail parser
            // can accidentally pick an unrelated "10 минут" from page text.
            val tolerance = maxOf(5L, declared / 20L)
            return if (kotlin.math.abs(playlist - declared) > tolerance) playlist else declared
        }

        internal fun activePlayerHtml(rawHtml: String): String = rawHtml
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        private fun hasPageLink(rawHtml: String, pageUrl: String, targetUrl: String): Boolean {
            val targetPath = runCatching { URI(targetUrl).path.trimEnd('/') }.getOrNull() ?: return false
            val document = Jsoup.parse(rawHtml, pageUrl)
            return document.select("a[href]").any { anchor ->
                val href = anchor.absUrl("href").ifBlank {
                    runCatching { URI(pageUrl).resolve(anchor.attr("href")).toString() }.getOrDefault("")
                }
                runCatching { URI(href).path.trimEnd('/') == targetPath }.getOrDefault(false)
            }
        }

        private fun bookExternalIdForSeries(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            if ("/paid/book/" in path) return ""
            val marker = "/book/"
            val start = path.indexOf(marker)
            if (start < 0) return ""
            return path.substring(start + marker.length).substringBefore('/').trim()
        }

        private const val KNIGAVUHE_COLLECTION_PAGE_SIZE = 10
        private const val DETAIL_CACHE_TTL_MS = 2 * 60 * 1000L
        private const val DETAIL_CACHE_MAX = 96
        private const val HTML_CACHE_TTL_MS = 2 * 60 * 1000L
        private const val HTML_CACHE_MAX = 96
        private const val SERIES_PAGE_CACHE_TTL_MS = 2 * 60 * 1000L
        private const val SERIES_PAGE_CACHE_MAX = 48
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

        const val HOME_SECTION_NEW = "new"
        const val HOME_SECTION_POPULAR_TODAY = "popular:today"
        const val HOME_SECTION_POPULAR_WEEK = "popular:week"
        const val HOME_SECTION_POPULAR_MONTH = "popular:month"
    }
}