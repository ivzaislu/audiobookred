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
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

internal class PreviewOnlyAudioknigaLifeBook(
    val previewBook: BookDetailDto? = null,
    message: String = "audioknigalife_preview_only",
) : IOException(message)

internal class AbredAudioknigaLifeParser {
    private val http = ApiClient.createHttpClient()

    private data class CacheEntry(val storedAtMs: Long, val detail: BookDetailDto)
    private data class HtmlCacheEntry(val storedAtMs: Long, val html: String)

    private val detailCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 64
    }
    private val htmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean = size > 64
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> {
        val url = AbredAudioknigaLifeHtmlParser.catalogPageUrl(page)
        return AbredAudioknigaLifeHtmlParser.parseCatalog(fetchCachedText(url), url)
    }

    suspend fun search(query: String): List<LiveCatalogItemDto> {
        val clean = query.trim()
        if (clean.length < 3) return emptyList()
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", clean.take(64))
            .build()
        return AbredAudioknigaLifeHtmlParser.parseCatalog(
            fetchText(AUDIOKNIGA_LIFE_BASE_URL, body),
            AUDIOKNIGA_LIFE_BASE_URL,
        )
    }

    suspend fun genres(): List<GenreDto> {
        val html = fetchCachedText(AUDIOKNIGA_LIFE_BASE_URL)
        return AbredAudioknigaLifeHtmlParser.parseGenres(html, AUDIOKNIGA_LIFE_BASE_URL)
    }

    fun canBrowseAuthor(id: String): Boolean =
        AbredAudioknigaLifeHtmlParser.parseEntityRef(id, "author") != null

    fun canBrowseNarrator(id: String): Boolean =
        AbredAudioknigaLifeHtmlParser.parseEntityRef(id, "narrator") != null

    fun canBrowseGenre(id: String): Boolean =
        AbredAudioknigaLifeHtmlParser.parseEntityRef(id, "genre") != null

    suspend fun authorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        collection("author", requireEntity(id, "author"), page, limit)

    suspend fun narratorBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        collection("narrator", requireEntity(id, "narrator"), page, limit)

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> =
        collection("genre", requireEntity(id, "genre"), page, limit)

    suspend fun book(bookId: String): BookDetailDto {
        cached(bookId)?.let { return it }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid Audiokniga.Life book key: " + bookId)
        require(parsed.first == AUDIOKNIGA_LIFE_SOURCE) { "Not an Audiokniga.Life key: " + bookId }
        val externalPath = parsed.second
        val pageUrl = AbredAudioknigaLifeHtmlParser.bookUrl(externalPath)
        val html = fetchText(pageUrl)
        val metadata = AbredAudioknigaLifeHtmlParser.parseMetadata(html, pageUrl, bookId, externalPath)
        if (AbredAudioknigaLifeHtmlParser.isPreviewOnly(html, pageUrl)) {
            throw PreviewOnlyAudioknigaLifeBook(metadata)
        }
        val chapters = AbredAudioknigaLifeHtmlParser.parseChapters(html, bookId)
        if (chapters.isEmpty()) throw IOException("Audiokniga.Life не вернул аудиоглавы")
        val detail = AbredAudioknigaLifeHtmlParser.attachChapters(metadata, chapters)
        synchronized(detailCache) { detailCache[bookId] = CacheEntry(nowMs(), detail) }
        return detail
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == AUDIOKNIGA_LIFE_SOURCE &&
            (requested.isBlank() || requested == AUDIOKNIGA_LIFE_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) {
            "Audiokniga.Life source series is unavailable for " + bookId
        }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid Audiokniga.Life book key: " + bookId)
        val pageUrl = AbredAudioknigaLifeHtmlParser.bookUrl(parsed.second)
        val detail = cached(bookId) ?: AbredAudioknigaLifeHtmlParser.parseMetadata(
            fetchText(pageUrl),
            pageUrl,
            bookId,
            parsed.second,
        )
        val membership = detail.audioSeries.firstOrNull { it.provider == AUDIOKNIGA_LIFE_SOURCE }
            ?: error("Audiokniga.Life book has no source series: " + bookId)
        val externalId = membership.externalId.takeIf(String::isNotBlank)
            ?: error("Audiokniga.Life book has no source series id: " + bookId)
        val seriesName = membership.name.ifBlank { detail.sourceSeriesName.ifBlank { detail.seriesName } }
        val rows = collection("series", externalId, page, limit)
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
            id = "source:" + AUDIOKNIGA_LIFE_SOURCE + ":" + externalId,
            name = seriesName,
            kind = "source_series",
            provider = AUDIOKNIGA_LIFE_SOURCE,
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
        val result = ArrayList<LiveCatalogItemDto>(safeLimit)
        var sitePage = 1
        var seen = 0L
        while (result.size < safeLimit) {
            val url = AbredAudioknigaLifeHtmlParser.collectionPageUrl(kind, externalId, sitePage)
            val html = fetchCachedText(url)
            val rows = AbredAudioknigaLifeHtmlParser.parseCatalog(html, url)
            rows.forEach { row ->
                if (seen >= logicalOffset && result.size < safeLimit) result += row
                seen += 1L
            }
            if (rows.isEmpty() || !AbredAudioknigaLifeHtmlParser.hasNextPage(html, url)) break
            if (sitePage == Int.MAX_VALUE) break
            sitePage += 1
        }
        return result
    }

    private fun LiveCatalogItemDto.toBookCard(seriesExternalId: String, fallbackSeriesName: String): BookCardDto {
        val effectiveSeriesName = seriesName.ifBlank { fallbackSeriesName }
        return BookCardDto(
            id = key,
            title = title,
            authors = authors.map { PersonDto("", it) },
            narrators = narrators.map { PersonDto("", it) },
            genres = genres.map { GenreDto("", it) },
            coverUrl = coverUrl,
            durationSeconds = durationSeconds,
            sourceCodes = listOf(AUDIOKNIGA_LIFE_SOURCE),
            primarySource = AUDIOKNIGA_LIFE_SOURCE,
            sourceSeriesName = effectiveSeriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = if (effectiveSeriesName.isBlank()) emptyList() else listOf(
                AudioSeriesBriefDto(
                    id = "source:" + AUDIOKNIGA_LIFE_SOURCE + ":" + seriesExternalId,
                    name = effectiveSeriesName,
                    position = seriesPosition?.toDouble(),
                    provider = AUDIOKNIGA_LIFE_SOURCE,
                    externalId = seriesExternalId,
                    sourceName = AUDIOKNIGA_LIFE_SOURCE_NAME,
                )
            ),
        )
    }

    private fun requireEntity(id: String, kind: String): String =
        AbredAudioknigaLifeHtmlParser.parseEntityRef(id, kind)
            ?: error("Invalid Audiokniga.Life " + kind + " id: " + id)

    private suspend fun fetchCachedText(url: String): String {
        synchronized(htmlCache) {
            val row = htmlCache[url]
            if (row != null && nowMs() - row.storedAtMs <= CACHE_TTL_MS) return row.html
            if (row != null) htmlCache.remove(url)
        }
        val html = fetchText(url)
        synchronized(htmlCache) { htmlCache[url] = HtmlCacheEntry(nowMs(), html) }
        return html
    }

    private suspend fun fetchText(url: String, body: FormBody? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            .header("Referer", AUDIOKNIGA_LIFE_BASE_URL + "/")
        if (body != null) builder.post(body)
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Audiokniga.Life HTTP " + response.code + " for " + url)
            }
            response.body?.string() ?: throw IOException("Audiokniga.Life returned an empty response")
        }
    }

    private fun cached(bookId: String): BookDetailDto? = synchronized(detailCache) {
        val row = detailCache[bookId] ?: return@synchronized null
        if (nowMs() - row.storedAtMs > CACHE_TTL_MS) {
            detailCache.remove(bookId)
            null
        } else row.detail
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val CACHE_TTL_MS = 2 * 60 * 1000L
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/153 Safari/537.36 AbredAndroid"
    }
}
