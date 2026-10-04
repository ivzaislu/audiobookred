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
        val detail = book(bookId)
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
            rating = rating,
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

internal object AbredBazaKnigHtmlParser {
    private data class EntityLink(
        val externalId: String,
        val name: String,
    )

    private data class SeriesInfo(
        val externalId: String = "",
        val name: String = "",
        val position: Int? = null,
    )

    private val bookHrefRegex = Regex("""(?:^|/)audio-([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val entityHrefRegex = mapOf(
        "author" to Regex("""(?:^|/)avtor-([^/?#]+)""", RegexOption.IGNORE_CASE),
        "narrator" to Regex("""(?:^|/)ispolnitel-([^/?#]+)""", RegexOption.IGNORE_CASE),
        "genre" to Regex("""(?:^|/)genre-([^/?#]+)""", RegexOption.IGNORE_CASE),
        "series" to Regex("""(?:^|/)series-([^/?#]+)""", RegexOption.IGNORE_CASE),
    )
    private val playerPlaylistRegex = Regex(
        """\bfile\s*:\s*["'](https://[^"'\\\s]+\.pl\.txt(?:\?[^"'\\\s]*)?)["']""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val clockDurationRegex = Regex("""\b(\d{1,3}):([0-5]?\d):([0-5]?\d)\b""")
    private val hourRegex = Regex("""(\d+)\s*ч(?:ас(?:а|ов)?)?\.?""", RegexOption.IGNORE_CASE)
    private val minuteRegex = Regex("""(\d+)\s*м(?:ин(?:ут(?:а|ы)?)?)?\.?""", RegexOption.IGNORE_CASE)
    private val seriesPositionRegex = Regex("""\((\d+)\)""")
    private val countRegex = Regex(
        """\b(\d+)\s+(?:аудиокниг\w*|книг\w*|сер(?:ий|ии|ия))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val safeExternalTokenRegex = Regex("""^[\p{L}\p{N}][\p{L}\p{N}._~-]*$""")

    fun catalogPageUrl(page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) "$BAZAKNIG_BASE_URL/" else "$BAZAKNIG_BASE_URL/?page=$safePage"
    }

    fun searchUrl(query: String): String =
        "$BAZAKNIG_BASE_URL/search?text=" +
            URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())

    fun bookUrl(externalId: String): String =
        "$BAZAKNIG_BASE_URL/audio-${sanitizeExternalToken(externalId)}"

    fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
        val prefix = when (kind) {
            "author" -> "avtor"
            "narrator" -> "ispolnitel"
            "genre" -> "genre"
            "series" -> "series"
            else -> error("Unsupported Baza-Knig collection: $kind")
        }
        val root = "$BAZAKNIG_BASE_URL/$prefix-${sanitizeExternalToken(externalId)}"
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) root else "$root?page=$safePage"
    }

    fun entityRef(kind: String, externalId: String): String =
        "$BAZAKNIG_SOURCE:$kind:${sanitizeExternalToken(externalId)}"

    fun parseEntityRef(value: String, expectedKind: String): String? {
        val parts = value.trim().split(':', limit = 3)
        if (parts.size != 3 ||
            parts[0].lowercase() != BAZAKNIG_SOURCE ||
            parts[1].lowercase() != expectedKind
        ) {
            return null
        }
        return parts[2].trim().takeIf(::isSafeExternalToken)
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val roots = document.select("article.abook-item")
        if (roots.isEmpty()) return emptyList()

        val seen = linkedSetOf<String>()
        val result = mutableListOf<LiveCatalogItemDto>()
        roots.forEach { root ->
            parseCard(root, pageUrl)?.let { item ->
                if (seen.add(item.externalId)) result += item
            }
        }
        return result
    }

    fun hasNextCatalogPage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return document.selectFirst("link[rel=next][href], .pagination li.next:not(.disabled) a[href]") != null
    }

    fun parseSearch(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        if (document.selectFirst("article.abook-item") != null) {
            // A rich search page already contains the Biblio shop marker on each
            // paid card. Returning the filtered rich result directly also keeps an
            // all-paid result empty instead of re-adding it through the lightweight
            // anchor fallback below.
            return parseCatalog(rawHtml, pageUrl)
        }

        val seen = linkedSetOf<String>()
        val result = mutableListOf<LiveCatalogItemDto>()
        document.select(".b-statictop-search a[href*='/audio-'], a[href^='/audio-']").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            val externalId = parseBookExternalId(href) ?: return@forEach
            val title = clean(anchor.text())
            if (title.isBlank() || !seen.add(externalId)) return@forEach
            result += LiveCatalogItemDto(
                key = "$BAZAKNIG_SOURCE:$externalId",
                source = BAZAKNIG_SOURCE,
                externalId = externalId,
                externalUrl = href,
                title = title,
            )
        }
        return result
    }

    fun parseGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return document.select("a[href*='/genre-']")
            .mapNotNull { anchor ->
                val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
                val externalId = parseEntityExternalId(href, "genre") ?: return@mapNotNull null
                val name = clean(anchor.text()).substringBefore(" – ").trim()
                if (name.isBlank()) null else GenreDto(entityRef("genre", externalId), name)
            }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
    }

    fun parseMetadata(rawHtml: String, pageUrl: String, bookId: String): BookDetailDto {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid Baza-Knig book id: $bookId")
        require(parsed.first == BAZAKNIG_SOURCE) { "Not a Baza-Knig key: $bookId" }

        val title = clean(document.selectFirst(".book_title_name")?.text().orEmpty())
            .ifBlank {
                clean(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
                    .substringBefore(" аудиокнига")
                    .trim()
            }
        if (title.isBlank()) throw UnavailableBazaKnigBook("Baza-Knig не вернул название книги")

        val authors = entityLinks(document, "author")
            .map { PersonDto(entityRef("author", it.externalId), it.name) }
        val narrators = entityLinks(document, "narrator")
            .map { PersonDto(entityRef("narrator", it.externalId), it.name) }
        val genres = entityLinks(document, "genre")
            .map { GenreDto(entityRef("genre", it.externalId), it.name) }

        val cover = absoluteUrl(
            pageUrl,
            document.selectFirst("meta[property=og:image]")?.attr("content")
                .orEmpty()
                .ifBlank { document.selectFirst(".book_cover img")?.attr("src").orEmpty() }
        )
        val duration = parseDurationSeconds(
            document.selectFirst(".book_blue_block")?.text().orEmpty()
        )
        val description = parseDescription(document)
        val series = parseSeries(document)
        val sourceIdentity = "live:$BAZAKNIG_SOURCE:${parsed.second}"

        val audioSeries = if (series.externalId.isBlank() || series.name.isBlank()) {
            emptyList()
        } else {
            listOf(
                AudioSeriesBriefDto(
                    id = "source:$BAZAKNIG_SOURCE:${series.externalId}",
                    name = series.name,
                    position = series.position?.toDouble(),
                    provider = BAZAKNIG_SOURCE,
                    externalId = series.externalId,
                    sourceName = BAZAKNIG_SOURCE_NAME,
                )
            )
        }

        return BookDetailDto(
            id = bookId,
            title = title,
            authors = authors,
            narrators = narrators,
            genres = genres,
            coverUrl = cover,
            durationSeconds = duration,
            sourceCodes = listOf(BAZAKNIG_SOURCE),
            primarySource = BAZAKNIG_SOURCE,
            selectedSource = BAZAKNIG_SOURCE,
            selectedBookSourceId = sourceIdentity,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = sourceIdentity,
                    sourceCode = BAZAKNIG_SOURCE,
                    sourceName = BAZAKNIG_SOURCE_NAME,
                    externalId = parsed.second,
                    externalUrl = pageUrl,
                    chaptersCount = 0,
                    durationSeconds = duration,
                    seriesName = series.name,
                    seriesPosition = series.position,
                )
            ),
            description = description,
            seriesName = series.name,
            seriesPosition = series.position,
            sourceSeriesName = series.name,
            sourceSeriesPosition = series.position,
            audioSeries = audioSeries,
        )
    }

    fun isPreviewPage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val chapterTitles = document.select(".chapter__default--title")
            .map { clean(it.text()).lowercase() }
        val hasFragment = chapterTitles.any { it == "фрагмент" || it.contains("ознакомитель") }
        val hasBuyButton = document.selectFirst(".shop--button-buy") != null
        val oneFilePlayer = document.selectFirst(".player--buttons-onefile") != null
        val shopId = document.selectFirst("article[data-shopid]")
            ?.attr("data-shopid")
            ?.trim()
            .orEmpty()
        return hasBuyButton || (hasFragment && oneFilePlayer) ||
            (shopId.isNotBlank() && hasFragment)
    }

    fun parsePlaylistUrl(rawHtml: String, pageUrl: String): String? {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val scriptText = document.select("script")
            .asSequence()
            .map { it.data() }
            .firstNotNullOfOrNull { script ->
                playerPlaylistRegex.find(script)?.groupValues?.getOrNull(1)
            }
            ?: playerPlaylistRegex.find(rawHtml)?.groupValues?.getOrNull(1)
            ?: return null
        return scriptText.takeIf(::isAllowedMediaHost)
    }

    fun parsePlaylist(rawJson: String, bookId: String): List<ChapterDto> {
        val array = try {
            JSONArray(rawJson)
        } catch (_: Exception) {
            throw UnavailableBazaKnigBook("Baza-Knig вернул некорректный плейлист")
        }
        if (array.length() == 0) {
            throw UnavailableBazaKnigBook("Baza-Knig вернул пустой плейлист")
        }

        val result = mutableListOf<ChapterDto>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val streamUrl = item.optString("file").trim()
            if (!isAllowedMediaHost(streamUrl)) continue
            val title = clean(item.optString("title")).ifBlank { "Часть ${index + 1}" }
            result += ChapterDto(
                id = "$bookId:chapter:${index + 1}",
                position = index,
                title = title,
                durationSeconds = 0,
                streamUrl = streamUrl,
            )
        }
        if (result.isEmpty()) {
            throw UnavailableBazaKnigBook("Baza-Knig не вернул корректные аудиофайлы")
        }
        return result
    }

    fun attachChapters(metadata: BookDetailDto, chapters: List<ChapterDto>): BookDetailDto =
        metadata.copy(
            chapters = chapters,
            sourceVariants = metadata.sourceVariants.map { variant ->
                if (variant.sourceCode == BAZAKNIG_SOURCE) {
                    variant.copy(chaptersCount = chapters.size)
                } else {
                    variant
                }
            },
        )

    fun parseCollectionMetadata(rawHtml: String, pageUrl: String): Pair<String, Int> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val heading = clean(document.selectFirst("h1")?.text().orEmpty())
        val name = heading
            .substringAfter('"', "")
            .substringBeforeLast('"', "")
            .removeSuffix(" слушать онлайн")
            .trim()
            .ifBlank {
                heading
                    .removePrefix("Аудиокниги автора")
                    .removePrefix("Аудиокниги исполнителя")
                    .removePrefix("Аудиокниги серии")
                    .removePrefix("Аудиокниги: жанр")
                    .trim(' ', '"')
            }
        val countText = document.selectFirst("#content-full")?.text().orEmpty()
            .ifBlank { document.body()?.text().orEmpty() }
        val total = countRegex.findAll(countText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?: 0
        return name to total
    }

    internal fun parseBookExternalId(value: String): String? =
        bookHrefRegex.find(value)?.groupValues?.getOrNull(1)?.takeIf(::isSafeExternalToken)

    internal fun parseEntityExternalId(value: String, kind: String): String? =
        entityHrefRegex[kind]
            ?.find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(::isSafeExternalToken)

    internal fun parseDurationSeconds(value: String): Long {
        clockDurationRegex.find(value)?.let { match ->
            val h = match.groupValues[1].toLongOrNull() ?: 0
            val m = match.groupValues[2].toLongOrNull() ?: 0
            val s = match.groupValues[3].toLongOrNull() ?: 0
            return h * 3600L + m * 60L + s
        }

        val normalized = clean(value)
        val hours = hourRegex.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val minutes = minuteRegex.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return if (hours > 0 || minutes > 0) hours * 3600L + minutes * 60L else 0L
    }

    internal fun isAllowedMediaHost(value: String): Boolean = try {
        val uri = URI(value.trim())
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "redirectto.cc" || host.endsWith(".redirectto.cc"))
    } catch (_: Exception) {
        false
    }

    private fun parseCard(root: Element, pageUrl: String): LiveCatalogItemDto? {
        // Baza-Knig marks shop/preview-only cards in list pages with this icon.
        // Filter them before opening detail pages; isPreviewPage() remains the
        // second-line guard if the site ever omits the marker on a paid card.
        if (root.selectFirst(".biblio-icon-small") != null) return null

        val titleAnchor = root.selectFirst("a.book-title[href*='/audio-']")
            ?: root.selectFirst("a.image-abook[href*='/audio-']")
            ?: return null
        val href = titleAnchor.absUrl("href").ifBlank {
            absoluteUrl(pageUrl, titleAnchor.attr("href"))
        }
        val externalId = parseBookExternalId(href) ?: return null
        val title = clean(
            root.selectFirst("a.book-title")?.text().orEmpty()
                .ifBlank { titleAnchor.attr("title").removePrefix("Слушать аудиокнигу ").removeSuffix(" онлайн") }
        )
        if (title.isBlank()) return null

        val authors = root.select("a[href*='/avtor-']")
            .map { clean(it.text()) }
            .filter(String::isNotBlank)
            .distinct()
        val narrators = root.select("a[rel=performer], a[href*='/ispolnitel-']")
            .map { clean(it.text()) }
            .filter(String::isNotBlank)
            .distinct()
        val genres = root.select(".abook-genre a[href*='/genre-']")
            .map { clean(it.text()) }
            .filter(String::isNotBlank)
            .distinct()
        val seriesAnchor = root.selectFirst("a[rel=series], a[href*='/series-']")
        val seriesHref = seriesAnchor?.absUrl("href").orEmpty().ifBlank {
            seriesAnchor?.attr("href")?.let { absoluteUrl(pageUrl, it) }.orEmpty()
        }
        val seriesExternalId = parseEntityExternalId(seriesHref, "series").orEmpty()
        val seriesName = clean(seriesAnchor?.text().orEmpty())
        val seriesPosition = seriesAnchor?.parent()?.text()
            ?.let(seriesPositionRegex::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val duration = root.select(".a-info-item")
            .asSequence()
            .map { parseDurationSeconds(it.text()) }
            .firstOrNull { it > 0L }
            ?: 0L
        val cover = absoluteUrl(
            pageUrl,
            root.selectFirst("img.b-showshort__cover_image, a.image-abook img")
                ?.attr("src")
                .orEmpty()
        )

        return LiveCatalogItemDto(
            key = "$BAZAKNIG_SOURCE:$externalId",
            source = BAZAKNIG_SOURCE,
            externalId = externalId,
            externalUrl = href,
            title = title,
            coverUrl = cover,
            durationSeconds = duration,
            authors = authors,
            narrators = narrators,
            genres = genres,
            seriesName = seriesName,
            seriesExternalId = seriesExternalId,
            seriesPosition = seriesPosition,
        )
    }

    private fun entityLinks(document: Document, kind: String): List<EntityLink> {
        val selector = when (kind) {
            "author" -> ".book_title_elem a[href*='/avtor-']"
            "narrator" -> ".book_title_elem a[href*='/ispolnitel-']"
            "genre" -> ".book_genre_pretitle a[href*='/genre-']"
            else -> return emptyList()
        }
        return document.select(selector)
            .mapNotNull { anchor ->
                val href = anchor.absUrl("href").ifBlank {
                    absoluteUrl(document.baseUri(), anchor.attr("href"))
                }
                val externalId = parseEntityExternalId(href, kind) ?: return@mapNotNull null
                val name = clean(anchor.text())
                if (name.isBlank()) null else EntityLink(externalId, name)
            }
            .distinctBy { it.externalId }
    }

    private fun parseSeries(document: Document): SeriesInfo {
        val node = document.selectFirst(".book_series") ?: return SeriesInfo()
        val anchor = node.selectFirst("a[href*='/series-']") ?: return SeriesInfo()
        val href = anchor.absUrl("href").ifBlank {
            absoluteUrl(document.baseUri(), anchor.attr("href"))
        }
        val externalId = parseEntityExternalId(href, "series").orEmpty()
        val name = clean(anchor.text())
        val position = seriesPositionRegex.find(node.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return SeriesInfo(externalId, name, position)
    }

    private fun parseDescription(document: Document): String {
        val node = document.selectFirst(".book_description")?.clone() ?: return ""
        node.select("script,style,.ya-share2,.book_series").remove()
        return clean(node.text())
    }

    private fun sanitizeExternalToken(value: String): String {
        val clean = value.trim().trim('/')
        require(isSafeExternalToken(clean)) { "Invalid Baza-Knig external id" }
        return clean
    }

    private fun isSafeExternalToken(value: String): Boolean =
        value.isNotBlank() &&
            !value.contains("..") &&
            safeExternalTokenRegex.matches(value)

    private fun clean(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

    private fun absoluteUrl(baseUrl: String, raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return ""
        return try {
            URI(baseUrl).resolve(clean).toString()
        } catch (_: Exception) {
            clean
        }
    }
}

internal const val BAZAKNIG_SOURCE = "bazaknig"
internal const val BAZAKNIG_SOURCE_NAME = "Baza-Knig"
internal const val BAZAKNIG_BASE_URL = "https://baza-knig.info"
