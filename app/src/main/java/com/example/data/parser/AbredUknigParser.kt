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
        val detail = book(bookId)
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
            externalId = externalId,
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
            rating = rating,
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

internal object AbredUknigHtmlParser {
    private data class NamedLink(val externalId: String, val name: String, val url: String)
    private data class SeriesInfo(val externalId: String = "", val name: String = "", val position: Int? = null)

    private val bookIdRegex = Regex("(?:^|/)books/(\\d+)(?:/|$)")
    private val seriesIdRegex = Regex("/(?:index\\.php/)?series/(\\d+)(?:/|$)")
    private val mediaUrlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val backgroundUrlRegex = Regex("background(?:-image)?\\s*:\\s*url\\((['\"]?)(.*?)\\1\\)", RegexOption.IGNORE_CASE)
    private const val RIGHTS_BLOCKED = "прослушивание заблокировано правообладателем"
    private val previewMarkers = listOf("ознакомительный фрагмент", "фрагмент аудиокниги")
    private val rejectedCoverMarkers = listOf("/images/placeholder", "/images/ll_logo", "/images/logo", "/favicon", "sprite")

    fun catalogPageUrl(page: Int): String {
        val normalized = page.coerceAtLeast(1)
        return if (normalized == 1) "$UKNIG_BASE_URL/" else "$UKNIG_BASE_URL/?p=$normalized"
    }

    fun searchUrl(query: String): String =
        "$UKNIG_BASE_URL/?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())

    fun bookUrl(externalId: String): String = "$UKNIG_BASE_URL/books/${externalId.trim('/')}"

    fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
        require(kind in setOf("authors", "readers", "genres", "series")) { "Unsupported уКниг collection: $kind" }
        val id = externalId.trim().trim('/')
        require(id.isNotBlank() && !id.contains("..")) { "Invalid уКниг collection id" }
        val root = "$UKNIG_BASE_URL/$kind/$id"
        return if (page.coerceAtLeast(1) == 1) root else "$root?p=${page.coerceAtLeast(1)}"
    }

    fun entityRef(kind: String, externalId: String): String = "$UKNIG_SOURCE:$kind:${externalId.trim()}"

    fun parseEntityRef(value: String, expectedKind: String): String? {
        val parts = value.trim().split(':', limit = 3)
        if (parts.size != 3 || parts[0].lowercase() != UKNIG_SOURCE || parts[1].lowercase() != expectedKind) return null
        return parts[2].trim().takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val seen = linkedSetOf<String>()
        val result = mutableListOf<LiveCatalogItemDto>()
        val anchors = document.select("a.book-title[href], .book-item a[href*='/books/']")
        for (anchor in anchors) {
            val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            val externalId = bookId(href)
            if (externalId.isBlank() || !seen.add(externalId)) continue
            val card = anchor.closest(".book-item") ?: nearestBookContainer(anchor)
            val title = clean(
                card?.selectFirst("a.book-title")?.text().orEmpty().ifBlank { anchor.text() }
            )
            if (title.isBlank() || title.equals("слушать аудиокнигу", ignoreCase = true)) continue

            val authorLinks = card?.let { namedLinks(it, "author") }.orEmpty()
            val readerLinks = card?.let { namedLinks(it, "reader") }.orEmpty()
            val genreLinks = card?.let { namedLinks(it, "genre") }.orEmpty()
            val series = card?.let(::series).orEmptySeries()
            val cover = card?.let { coverFromBookContainer(it, pageUrl) }.orEmpty()
            val duration = card?.let { durationSeconds(it.text()) } ?: 0L

            result += LiveCatalogItemDto(
                key = "$UKNIG_SOURCE:$externalId",
                source = UKNIG_SOURCE,
                externalId = externalId,
                externalUrl = bookUrl(externalId),
                title = title,
                coverUrl = cover,
                durationSeconds = duration,
                authors = authorLinks.map(NamedLink::name),
                narrators = readerLinks.map(NamedLink::name),
                genres = genreLinks.map(NamedLink::name),
                seriesName = series.name,
                seriesExternalId = series.externalId,
                seriesPosition = series.position,
            )
        }
        return result
    }

    fun parseGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val seen = linkedSetOf<String>()
        val result = mutableListOf<GenreDto>()
        for (anchor in document.select("a[href*='/genres/']")) {
            val link = namedLink(anchor, "genre") ?: continue
            if (!seen.add(link.externalId)) continue
            result += GenreDto(entityRef("genre", link.externalId), link.name)
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun parseCollectionMetadata(rawHtml: String, pageUrl: String, fallbackName: String = ""): Pair<String, Int> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val heading = clean(document.selectFirst("h1")?.text().orEmpty())
        val name = heading.ifBlank { fallbackName }
        val text = clean(document.text())
        val count = Regex("([0-9][0-9\\s]*)\\s+(?:аудиокниг|книга|книги|книг)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.replace(" ", "")?.toIntOrNull() ?: 0
        return name to count
    }

    fun isPreviewPage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val folded = clean(document.text()).lowercase()
        return previewMarkers.any(folded::contains)
    }

    fun parseMetadata(rawHtml: String, bookUrl: String, bookKey: String): BookDetailDto {
        val document = Jsoup.parse(rawHtml, bookUrl)
        val pageText = clean(document.text())
        val folded = pageText.lowercase()
        if (RIGHTS_BLOCKED in folded) throw UnavailableUknigBook("uknig_rights_holder_blocked")

        val parsed = parseLiveBookKey(bookKey) ?: error("Invalid live book key: $bookKey")
        require(parsed.first == UKNIG_SOURCE) { "Not a уКниг key: $bookKey" }
        val externalId = bookId(bookUrl).ifBlank { parsed.second }
        require(externalId.isNotBlank()) { "Cannot determine уКниг book id: $bookUrl" }

        val title = clean(document.selectFirst("h1")?.text().orEmpty()).ifBlank {
            clean(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
        }
        if (title.isBlank()) throw IOException("уКниг detail title is missing")

        val ogCover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let { absoluteUrl(bookUrl, it) }?.takeIf(::isUsableCover)
        val cover = ogCover
            ?: document.selectFirst(".book-item")?.let { coverFromBookContainer(it, bookUrl) }
            ?: document.selectFirst("img.cover[data-original], img.cover[data-src], img.cover[src]")
                ?.let { coverFromImage(it, bookUrl) }?.takeIf(::isUsableCover)
            ?: ""
        val authors = namedLinks(document, "author")
        val readers = namedLinks(document, "reader")
        val genres = namedLinks(document, "genre")
        val series = series(document).orEmptySeries()
        val duration = durationSeconds(pageText)
        val description = sectionText(document, "Описание книги", "Подробная информация")
        val rating = rating(document)
        val sourceIdentity = "live:$UKNIG_SOURCE:$externalId"
        val audioSeries = if (series.name.isBlank()) emptyList() else listOf(
            AudioSeriesBriefDto(
                id = if (series.externalId.isNotBlank()) "source:$UKNIG_SOURCE:${series.externalId}" else stableId("series", series.name),
                name = series.name,
                position = series.position?.toDouble(),
                provider = UKNIG_SOURCE,
                externalId = series.externalId,
                sourceName = "уКниг",
            )
        )

        return BookDetailDto(
            id = bookKey,
            title = title,
            authors = authors.map { PersonDto(entityRef("author", it.externalId), it.name) },
            narrators = readers.map { PersonDto(entityRef("reader", it.externalId), it.name) },
            genres = genres.map { GenreDto(entityRef("genre", it.externalId), it.name) },
            coverUrl = cover,
            durationSeconds = duration,
            rating = rating,
            sourceCodes = listOf(UKNIG_SOURCE),
            primarySource = UKNIG_SOURCE,
            selectedSource = UKNIG_SOURCE,
            selectedBookSourceId = sourceIdentity,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = sourceIdentity,
                    sourceCode = UKNIG_SOURCE,
                    sourceName = "уКниг",
                    externalId = externalId,
                    externalUrl = bookUrl,
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
            chapters = emptyList(),
        )
    }

    fun parsePlaylist(rawJson: String, bookKey: String): List<ChapterDto> {
        val array = try {
            JSONArray(rawJson)
        } catch (_: Exception) {
            throw IOException("уКниг playlist is invalid")
        }
        if (array.length() == 0) throw IOException("уКниг playlist is empty")
        val chapters = mutableListOf<ChapterDto>()
        val seen = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val externalId = clean(item.opt("id")?.toString().orEmpty())
            val mediaUrl = firstMediaUrl(item.opt("file")?.toString().orEmpty())
            if (externalId.isBlank() || mediaUrl.isBlank() || !seen.add(mediaUrl)) continue
            chapters += ChapterDto(
                id = "$bookKey:chapter:$externalId",
                position = chapters.size,
                title = clean(item.opt("title")?.toString().orEmpty()).ifBlank { "Глава ${index + 1}" },
                durationSeconds = 0L,
                streamUrl = mediaUrl,
            )
        }
        if (chapters.isEmpty()) throw IOException("уКниг playlist has no playable chapters")
        return chapters
    }

    fun attachChapters(metadata: BookDetailDto, chapters: List<ChapterDto>): BookDetailDto {
        if (chapters.isEmpty()) throw IOException("уКниг playlist has no playable chapters")
        return metadata.copy(
            sourceVariants = metadata.sourceVariants.map { it.copy(chaptersCount = chapters.size) },
            chapters = chapters,
        )
    }

    private fun namedLinks(root: Element, kind: String): List<NamedLink> {
        val selector = when (kind) {
            "author" -> "a[href*='/authors/']"
            "reader" -> "a[href*='/readers/']"
            "genre" -> "a[href*='/genres/']"
            else -> return emptyList()
        }
        val result = mutableListOf<NamedLink>()
        val seen = linkedSetOf<String>()
        for (anchor in root.select(selector)) {
            val link = namedLink(anchor, kind) ?: continue
            if (seen.add(link.externalId)) result += link
        }
        return result
    }

    private fun namedLink(anchor: Element, kind: String): NamedLink? {
        val href = anchor.absUrl("href").ifBlank { absoluteUrl(UKNIG_BASE_URL + "/", anchor.attr("href")) }
        val path = runCatching { URI(href).path.orEmpty() }.getOrDefault(anchor.attr("href"))
        val prefix = when (kind) {
            "author" -> "/authors/"
            "reader" -> "/readers/"
            "genre" -> "/genres/"
            else -> return null
        }
        val index = path.indexOf(prefix)
        if (index < 0) return null
        val id = path.substring(index + prefix.length).trim('/').substringBefore('/').trim()
        val name = clean(anchor.text())
        if (id.isBlank() || name.isBlank()) return null
        return NamedLink(id, name, href)
    }

    private fun SeriesInfo?.orEmptySeries(): SeriesInfo = this ?: SeriesInfo()

    private fun nearestBookContainer(anchor: Element): Element? {
        anchor.closest(".book-item")?.let { return it }
        var node: Element? = anchor.parent()
        repeat(6) {
            val current = node ?: return null
            val text = clean(current.text()).lowercase()
            val hasMetadata = current.select("a[href*='/authors/'],a[href*='/readers/'],a[href*='/genres/']").isNotEmpty()
            if (hasMetadata || "описание книги" in text || "читает" in text) return current
            node = current.parent()
        }
        return anchor.parent()
    }

    private fun series(root: Element): SeriesInfo? {
        for (anchor in root.select("a[href]")) {
            val href = anchor.absUrl("href").ifBlank { absoluteUrl(UKNIG_BASE_URL + "/", anchor.attr("href")) }
            val match = seriesIdRegex.find(runCatching { URI(href).path.orEmpty() }.getOrDefault(href)) ?: continue
            val name = clean(anchor.text())
            if (name.isBlank()) continue
            val parentText = clean(anchor.parent()?.text().orEmpty())
            val position = Regex("\\(#\\s*(\\d+)\\)").find(parentText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?:книга|том|часть)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(parentText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return SeriesInfo(match.groupValues[1], name, position)
        }
        return null
    }

    private fun coverFromBookContainer(root: Element, pageUrl: String): String {
        root.selectFirst("img.cover[data-original]")?.let { coverFromImage(it, pageUrl).takeIf(::isUsableCover)?.let { url -> return url } }
        root.selectFirst("img.cover[data-src]")?.let { coverFromImage(it, pageUrl).takeIf(::isUsableCover)?.let { url -> return url } }
        for (node in root.select("[style*='background']")) {
            val raw = backgroundUrlRegex.find(node.attr("style"))?.groupValues?.getOrNull(2).orEmpty()
            val candidate = absoluteUrl(pageUrl, raw)
            if (isUsableCover(candidate)) return candidate
        }
        root.selectFirst("img.cover[src]")?.let { coverFromImage(it, pageUrl).takeIf(::isUsableCover)?.let { url -> return url } }
        return ""
    }

    private fun coverFromImage(image: Element, pageUrl: String): String {
        val raw = image.attr("data-original").takeIf(String::isNotBlank)
            ?: image.attr("data-src").takeIf(String::isNotBlank)
            ?: image.attr("src")
        return absoluteUrl(pageUrl, raw)
    }

    private fun isUsableCover(value: String): Boolean {
        if (value.isBlank()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".svg") || path.endsWith(".svgz")) return false
        return rejectedCoverMarkers.none(path::contains)
    }

    private fun sectionText(document: Document, startLabel: String, endLabel: String): String {
        val ordered = document.select("body *").map { it.ownText() }
        var collecting = false
        val parts = mutableListOf<String>()
        for (raw in ordered) {
            val text = clean(raw)
            if (text.isBlank()) continue
            if (!collecting) {
                if (text.equals(startLabel, ignoreCase = true)) collecting = true
                continue
            }
            if (text.equals(endLabel, ignoreCase = true)) break
            parts += text
        }
        return clean(parts.joinToString(" "))
    }

    private fun rating(document: Document): String {
        for (node in document.select("[itemprop=ratingValue],meta[itemprop=ratingValue]")) {
            val value = clean(node.attr("content").ifBlank { node.text() }).replace(',', '.')
            if (Regex("\\d+(?:\\.\\d+)?").matches(value)) return value
        }
        return ""
    }

    private fun durationSeconds(value: String): Long {
        val text = clean(value).lowercase()
        if (text.isBlank()) return 0L
        val hours = Regex("(\\d+)\\s*(?:час|часа|часов|ч\\.)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("(\\d+)\\s*(?:минута|минуты|минут|мин\\.)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val seconds = Regex("(\\d+)\\s*(?:секунда|секунды|секунд|сек\\.)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return hours * 3600L + minutes * 60L + seconds
    }

    private fun firstMediaUrl(value: String): String {
        for (match in mediaUrlRegex.findAll(value)) {
            val candidate = match.value.trimEnd('.', ',', ';', ')')
            val uri = runCatching { URI(candidate) }.getOrNull() ?: continue
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme in setOf("http", "https") && (host == "uknig.com" || host.endsWith(".uknig.com"))) return candidate
        }
        return ""
    }

    private fun bookId(value: String): String {
        val path = runCatching { URI(value).path.orEmpty() }.getOrDefault(value)
        return bookIdRegex.find(path)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun absoluteUrl(base: String, raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun stableId(kind: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$UKNIG_SOURCE\u0000$kind\u0000$value".toByteArray())
        return "$UKNIG_SOURCE:$kind:" + digest.take(10).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

internal const val UKNIG_SOURCE = "uknig"
internal const val UKNIG_BASE_URL = "https://uknig.com"
internal const val UKNIG_COLLECTION_PAGE_SIZE = 18