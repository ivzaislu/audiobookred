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
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal const val MYAUDIOBOOKS_SOURCE = "myaudiobooks"
internal const val MYAUDIOBOOKS_SOURCE_NAME = "MY-AUDIOBOOKS"
internal const val MYAUDIOBOOKS_BASE_URL = "https://my-audiobooks.com"

internal class UnavailableMyAudiobooksBook(
    val unavailableBook: BookDetailDto? = null,
    message: String = "myaudiobooks_unavailable",
) : IOException(message)

internal fun isMyAudiobooksSeriesHttp404(error: Throwable): Boolean =
    error is IOException && error.message.orEmpty().startsWith("MY-AUDIOBOOKS HTTP 404 for ")

/** Standalone parser for the current DataLife Engine markup on my-audiobooks.com. */
internal class AbredMyAudiobooksParser {
    private val http = ApiClient.createHttpClient()
        .newBuilder()
        // ApiClient carries Baza-Knig defaults for redirectto.cc because that
        // provider first introduced this CDN. MY-AUDIOBOOKS uses the same CDN
        // with its own Origin/Referer, confirmed by the supplied browser HAR.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!AbredMyAudiobooksHtmlParser.isAllowedCdnUrl(request.url.toString())) {
                chain.proceed(request)
            } else {
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Origin", MYAUDIOBOOKS_BASE_URL)
                        .header("Referer", "$MYAUDIOBOOKS_BASE_URL/")
                        .build()
                )
            }
        }
        .build()

    private data class CacheEntry(val storedAtMs: Long, val detail: BookDetailDto)
    private data class HtmlCacheEntry(val storedAtMs: Long, val html: String)

    private val detailCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > DETAIL_CACHE_MAX
    }

    private val htmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean =
            size > HTML_CACHE_MAX
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> {
        val url = AbredMyAudiobooksHtmlParser.catalogPageUrl(page)
        return AbredMyAudiobooksHtmlParser.parseCatalog(fetchCachedText(url), url)
    }

    suspend fun search(query: String): List<LiveCatalogItemDto> {
        val clean = query.trim()
        if (clean.length < 3) return emptyList()
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", clean.take(20))
            .build()
        val html = fetchText(MYAUDIOBOOKS_BASE_URL, body)
        return AbredMyAudiobooksHtmlParser.parseCatalog(html, MYAUDIOBOOKS_BASE_URL)
    }

    suspend fun genres(): List<GenreDto> {
        val fullDirectory = try {
            val html = fetchCachedText(MYAUDIOBOOKS_GENRES_URL)
            AbredMyAudiobooksGenreIndexParser.parse(html, MYAUDIOBOOKS_GENRES_URL)
        } catch (_: IOException) {
            emptyList()
        }
        if (fullDirectory.isNotEmpty()) return fullDirectory

        val html = fetchCachedText(MYAUDIOBOOKS_BASE_URL)
        return AbredMyAudiobooksHtmlParser.parseHomepageGenres(html, MYAUDIOBOOKS_BASE_URL)
    }

    fun canBrowseAuthor(id: String): Boolean =
        AbredMyAudiobooksHtmlParser.parseEntityRef(id, "author") != null

    fun canBrowseNarrator(id: String): Boolean =
        AbredMyAudiobooksHtmlParser.parseEntityRef(id, "narrator") != null

    fun canBrowseGenre(id: String): Boolean =
        AbredMyAudiobooksHtmlParser.parseEntityRef(id, "genre") != null

    suspend fun authorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredMyAudiobooksHtmlParser.parseEntityRef(id, "author")
            ?: error("Invalid MY-AUDIOBOOKS author id: $id")
        return collection("author", externalId, page, limit)
    }

    suspend fun narratorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredMyAudiobooksHtmlParser.parseEntityRef(id, "narrator")
            ?: error("Invalid MY-AUDIOBOOKS narrator id: $id")
        return collection("narrator", externalId, page, limit)
    }

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredMyAudiobooksHtmlParser.parseEntityRef(id, "genre")
            ?: error("Invalid MY-AUDIOBOOKS genre id: $id")
        return collection("genre", externalId, page, limit)
    }

    suspend fun book(bookId: String): BookDetailDto {
        cached(bookId)?.let { return it }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == MYAUDIOBOOKS_SOURCE) { "Not a MY-AUDIOBOOKS key: $bookId" }

        val externalPath = parsed.second
        val pageUrl = AbredMyAudiobooksHtmlParser.bookUrl(externalPath)
        val html = fetchText(pageUrl)
        val metadata = AbredMyAudiobooksHtmlParser.parseMetadata(html, pageUrl, bookId, externalPath)

        if (AbredMyAudiobooksHtmlParser.isUnavailablePage(html, pageUrl)) {
            throw UnavailableMyAudiobooksBook(
                unavailableBook = metadata,
                message = "MY-AUDIOBOOKS: произведение недоступно у правообладателя",
            )
        }

        val playlistUrl = AbredMyAudiobooksHtmlParser.parsePlaylistUrl(html, pageUrl)
            ?: throw IOException("MY-AUDIOBOOKS не вернул плейлист для этой книги")
        val playlist = fetchPlaylist(playlistUrl)
        val chapters = AbredMyAudiobooksHtmlParser.parsePlaylist(playlist, bookId)
        if (chapters.isEmpty()) throw IOException("MY-AUDIOBOOKS вернул пустой плейлист")

        val detail = AbredMyAudiobooksHtmlParser.attachChapters(metadata, chapters, externalPath, pageUrl)
        synchronized(detailCache) {
            detailCache[bookId] = CacheEntry(monotonicMs(), detail)
        }
        return detail
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == MYAUDIOBOOKS_SOURCE &&
            (requested.isBlank() || requested == MYAUDIOBOOKS_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) {
            "MY-AUDIOBOOKS source series is unavailable for $bookId"
        }
        val detail = book(bookId)
        val membership = detail.audioSeries.firstOrNull { it.provider == MYAUDIOBOOKS_SOURCE }
            ?: error("MY-AUDIOBOOKS book has no source series: $bookId")
        val externalId = membership.externalId.takeIf(String::isNotBlank)
            ?: error("MY-AUDIOBOOKS book has no source series id: $bookId")
        val seriesName = membership.name.ifBlank { detail.sourceSeriesName.ifBlank { detail.seriesName } }
        val rows = try {
            collection("series", externalId, page, limit)
        } catch (error: IOException) {
            // MY-AUDIOBOOKS currently exposes some series links that return 404
            // on the provider itself. Treat those as an unavailable/empty source
            // collection instead of surfacing them as an app/network failure.
            if (!isMyAudiobooksSeriesHttp404(error)) throw error
            emptyList()
        }
        val cards = rows.map { it.toBookCard(externalId, seriesName) }
        val entries = rows.mapIndexed { index, item ->
            SeriesEntryDto(
                externalWorkId = item.externalId,
                title = item.title,
                authors = item.authors,
                position = item.seriesPosition?.toDouble(),
                available = true,
                book = cards[index],
            )
        }
        return SeriesDetailDto(
            id = "source:$MYAUDIOBOOKS_SOURCE:$externalId",
            name = seriesName,
            kind = "source_series",
            provider = MYAUDIOBOOKS_SOURCE,
            booksCount = 0,
            totalCount = 0,
            books = cards,
            entries = entries,
        )
    }

    private suspend fun collection(
        kind: String,
        externalId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val logicalOffset = (safePage.toLong() - 1L) * safeLimit.toLong()
        var sitePage = 1
        var seen = 0L
        val result = ArrayList<LiveCatalogItemDto>(safeLimit)

        while (result.size < safeLimit) {
            val url = AbredMyAudiobooksHtmlParser.collectionPageUrl(kind, externalId, sitePage)
            val html = fetchCachedText(url)
            val rows = AbredMyAudiobooksHtmlParser.parseCatalog(html, url)
            rows.forEach { row ->
                if (seen >= logicalOffset && result.size < safeLimit) result += row
                seen += 1L
            }
            if (rows.isEmpty() || !AbredMyAudiobooksHtmlParser.hasNextPage(html, url)) break
            if (sitePage == Int.MAX_VALUE) break
            sitePage += 1
        }
        return result
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
            sourceCodes = listOf(MYAUDIOBOOKS_SOURCE),
            primarySource = MYAUDIOBOOKS_SOURCE,
            sourceSeriesName = effectiveSeriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = if (seriesExternalId.isBlank() || effectiveSeriesName.isBlank()) emptyList() else listOf(
                AudioSeriesBriefDto(
                    id = "source:$MYAUDIOBOOKS_SOURCE:$seriesExternalId",
                    name = effectiveSeriesName,
                    position = seriesPosition?.toDouble(),
                    provider = MYAUDIOBOOKS_SOURCE,
                    externalId = seriesExternalId,
                    sourceName = MYAUDIOBOOKS_SOURCE_NAME,
                )
            ),
        )
    }

    private suspend fun fetchCachedText(url: String): String {
        synchronized(htmlCache) {
            val row = htmlCache[url]
            if (row != null && monotonicMs() - row.storedAtMs <= HTML_CACHE_TTL_MS) return row.html
            if (row != null) htmlCache.remove(url)
        }
        val html = fetchText(url)
        synchronized(htmlCache) {
            htmlCache[url] = HtmlCacheEntry(monotonicMs(), html)
        }
        return html
    }

    private suspend fun fetchText(url: String, body: FormBody? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .header("Referer", "$MYAUDIOBOOKS_BASE_URL/")
        if (body != null) builder.post(body)
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MY-AUDIOBOOKS HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("MY-AUDIOBOOKS returned an empty response for $url")
        }
    }

    private suspend fun fetchPlaylist(url: String): String = withContext(Dispatchers.IO) {
        require(AbredMyAudiobooksHtmlParser.isAllowedCdnUrl(url)) {
            "MY-AUDIOBOOKS playlist host is not allowed"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", MYAUDIOBOOKS_BASE_URL)
            .header("Referer", "$MYAUDIOBOOKS_BASE_URL/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MY-AUDIOBOOKS playlist HTTP ${response.code}")
            response.body?.string() ?: throw IOException("MY-AUDIOBOOKS returned an empty playlist")
        }
    }

    private fun cached(bookId: String): BookDetailDto? = synchronized(detailCache) {
        val row = detailCache[bookId] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > DETAIL_CACHE_TTL_MS) {
            detailCache.remove(bookId)
            null
        } else row.detail
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DETAIL_CACHE_TTL_MS = 2 * 60 * 1000L
        const val DETAIL_CACHE_MAX = 64
        const val HTML_CACHE_TTL_MS = 2 * 60 * 1000L
        const val HTML_CACHE_MAX = 64
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/152 Safari/537.36 AbredAndroid"
    }
}
