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
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal class PreviewOnlyBazaKnigBook(
    val previewBook: BookDetailDto? = null,
    message: String = "bazaknig_preview_only",
) : IOException(message)

internal class UnavailableBazaKnigBook(message: String = "bazaknig_unavailable") : IOException(message)

/**
 * Baza-Knig standalone provider.
 *
 * Book metadata is parsed from the public HTML page. Free books expose a
 * Playerjs `file` URL pointing to a cross-origin `.pl.txt` JSON playlist.
 * Paid pages are detected before playlist loading so a short "Фрагмент" is
 * never surfaced as a complete audiobook.
 */
internal class AbredBazaKnigParser {
    private val http = ApiClient.createHttpClient()

    private data class CacheEntry(val storedAtMs: Long, val detail: BookDetailDto)
    private data class HtmlCacheEntry(val storedAtMs: Long, val html: String)
    private data class CollectionPage(
        val name: String,
        val totalCount: Int,
        val items: List<LiveCatalogItemDto>,
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > DETAIL_CACHE_MAX
    }

    private val htmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean =
            size > HTML_CACHE_MAX
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> {
        val url = AbredBazaKnigHtmlParser.catalogPageUrl(page)
        return AbredBazaKnigHtmlParser.parseCatalog(fetchCachedText(url), url)
    }

    suspend fun search(query: String): List<LiveCatalogItemDto> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val url = AbredBazaKnigHtmlParser.searchUrl(clean)
        return AbredBazaKnigHtmlParser.parseSearch(fetchCachedText(url), url)
    }

    suspend fun genres(): List<GenreDto> {
        val url = "$BAZAKNIG_BASE_URL/genres"
        return AbredBazaKnigHtmlParser.parseGenres(fetchCachedText(url), url)
    }

    fun canBrowseAuthor(id: String): Boolean =
        AbredBazaKnigHtmlParser.parseEntityRef(id, "author") != null

    fun canBrowseNarrator(id: String): Boolean =
        AbredBazaKnigHtmlParser.parseEntityRef(id, "narrator") != null

    fun canBrowseGenre(id: String): Boolean =
        AbredBazaKnigHtmlParser.parseEntityRef(id, "genre") != null

    suspend fun authorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredBazaKnigHtmlParser.parseEntityRef(id, "author")
            ?: error("Invalid Baza-Knig author id: $id")
        return collection("author", externalId, page, limit).items
    }

    suspend fun narratorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredBazaKnigHtmlParser.parseEntityRef(id, "narrator")
            ?: error("Invalid Baza-Knig narrator id: $id")
        return collection("narrator", externalId, page, limit).items
    }

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredBazaKnigHtmlParser.parseEntityRef(id, "genre")
            ?: error("Invalid Baza-Knig genre id: $id")
        return collection("genre", externalId, page, limit).items
    }

    suspend fun book(bookId: String): BookDetailDto {
        cached(bookId)?.let { return it }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == BAZAKNIG_SOURCE) { "Not a Baza-Knig key: $bookId" }

        val externalId = parsed.second
        val bookUrl = AbredBazaKnigHtmlParser.bookUrl(externalId)
        val html = fetchText(bookUrl)
        val metadata = AbredBazaKnigHtmlParser.parseMetadata(html, bookUrl, bookId)

        if (AbredBazaKnigHtmlParser.isPreviewPage(html, bookUrl)) {
            throw PreviewOnlyBazaKnigBook(metadata)
        }

        val playlistUrl = AbredBazaKnigHtmlParser.parsePlaylistUrl(html, bookUrl)
            ?: throw UnavailableBazaKnigBook("Baza-Knig не вернул плейлист для этой книги")
        val playlist = fetchPlaylist(playlistUrl)
        val chapters = AbredBazaKnigHtmlParser.parsePlaylist(playlist, bookId)
        val detail = AbredBazaKnigHtmlParser.attachChapters(metadata, chapters)

        synchronized(cache) {
            cache[bookId] = CacheEntry(monotonicMs(), detail)
        }
        return detail
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == BAZAKNIG_SOURCE &&
            (requested.isBlank() || requested == BAZAKNIG_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) {
            "Baza-Knig source series is unavailable for $bookId"
        }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid Baza-Knig book id: $bookId")
        val detail = cached(bookId) ?: run {
            val bookUrl = AbredBazaKnigHtmlParser.bookUrl(parsed.second)
            AbredBazaKnigHtmlParser.parseMetadata(fetchText(bookUrl), bookUrl, bookId)
        }
        val membership = detail.audioSeries.firstOrNull { it.provider == BAZAKNIG_SOURCE }
            ?: error("Baza-Knig book has no source series: $bookId")
        val externalId = membership.externalId.takeIf(String::isNotBlank)
            ?: error("Baza-Knig book has no source series id: $bookId")

        val collection = collection("series", externalId, page, limit)
        val seriesName = collection.name.ifBlank {
            membership.name.ifBlank { detail.sourceSeriesName.ifBlank { detail.seriesName } }
        }
        val cards = collection.items.map { it.toBookCard(externalId, seriesName) }
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
            id = "source:$BAZAKNIG_SOURCE:$externalId",
            name = seriesName,
            kind = "source_series",
            provider = BAZAKNIG_SOURCE,
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
        val logicalOffset = (safePage.toLong() - 1L) * safeLimit.toLong()

        var sitePage = 1
        var availableSeen = 0L
        var name = ""
        var totalCount = 0
        val result = ArrayList<LiveCatalogItemDto>(safeLimit)

        while (result.size < safeLimit) {
            val url = AbredBazaKnigHtmlParser.collectionPageUrl(kind, externalId, sitePage)
            val html = fetchCachedText(url)
            val metadata = AbredBazaKnigHtmlParser.parseCollectionMetadata(html, url)
            if (name.isBlank()) name = metadata.first
            totalCount = maxOf(totalCount, metadata.second)

            val rows = AbredBazaKnigHtmlParser.parseCatalog(html, url)
            rows.forEach { row ->
                if (availableSeen >= logicalOffset && result.size < safeLimit) {
                    result += row
                }
                availableSeen += 1L
            }

            if (!AbredBazaKnigHtmlParser.hasNextCatalogPage(html, url)) break
            sitePage = nextPhysicalPage(sitePage) ?: break
        }

        return CollectionPage(name, totalCount.coerceAtLeast(result.size), result)
    }

    private fun LiveCatalogItemDto.toBookCard(
        seriesExternalId: String,
        fallbackSeriesName: String,
    ): BookCardDto {
        val effectiveSeriesName = seriesName.ifBlank { fallbackSeriesName }
        return BookCardDto(
            id = key,
            title = title,
            authors = authors.map { PersonDto("", it) },
            narrators = narrators.map { PersonDto("", it) },
            genres = genres.map { GenreDto("", it) },
            coverUrl = coverUrl,
            durationSeconds = durationSeconds,
            sourceCodes = listOf(BAZAKNIG_SOURCE),
            primarySource = BAZAKNIG_SOURCE,
            sourceSeriesName = effectiveSeriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = if (seriesExternalId.isBlank() || effectiveSeriesName.isBlank()) {
                emptyList()
            } else {
                listOf(
                    AudioSeriesBriefDto(
                        id = "source:$BAZAKNIG_SOURCE:$seriesExternalId",
                        name = effectiveSeriesName,
                        position = seriesPosition?.toDouble(),
                        provider = BAZAKNIG_SOURCE,
                        externalId = seriesExternalId,
                        sourceName = BAZAKNIG_SOURCE_NAME,
                    )
                )
            },
        )
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Baza-Knig HTTP ${response.code} for $url")
            response.body?.string()
                ?: throw IOException("Baza-Knig returned an empty response for $url")
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
        synchronized(htmlCache) {
            htmlCache[url] = HtmlCacheEntry(monotonicMs(), html)
        }
        return html
    }

    private suspend fun fetchPlaylist(url: String): String = withContext(Dispatchers.IO) {
        require(AbredBazaKnigHtmlParser.isAllowedMediaHost(url)) {
            "Baza-Knig playlist host is not allowed"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", BAZAKNIG_BASE_URL)
            .header("Referer", "$BAZAKNIG_BASE_URL/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Baza-Knig playlist HTTP ${response.code}")
            }
            response.body?.string()
                ?: throw IOException("Baza-Knig returned an empty playlist")
        }
    }

    private fun cached(bookId: String): BookDetailDto? = synchronized(cache) {
        val row = cache[bookId] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > DETAIL_CACHE_TTL_MS) {
            cache.remove(bookId)
            null
        } else {
            row.detail
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DETAIL_CACHE_TTL_MS = 2 * 60 * 1000L
        const val DETAIL_CACHE_MAX = 64
        const val HTML_CACHE_TTL_MS = 2 * 60 * 1000L
        const val HTML_CACHE_MAX = 64
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/136 Safari/537.36 AbredAndroid"
    }
}

internal const val BAZAKNIG_SOURCE = "bazaknig"
internal const val BAZAKNIG_SOURCE_NAME = "Baza-Knig"
internal const val BAZAKNIG_BASE_URL = "https://baza-knig.info"
