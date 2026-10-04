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
            externalId = externalId,
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
            rating = rating,
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

internal object AbredMyAudiobooksHtmlParser {
    private val bookPathRegex = Regex("^/([^/?#]+/\\d+[^/?#]*\\.html)$", RegexOption.IGNORE_CASE)
    private val playerPlaylistRegex = Regex(
        """\bfile\s*:\s*["'](https://[^"'\\\s]+\.pl\.txt(?:\?[^"'\\\s]*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val clockDurationRegex = Regex("""\b(\d{1,3}):([0-5]?\d):([0-5]?\d)\b""")

    fun catalogPageUrl(page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) "$MYAUDIOBOOKS_BASE_URL/" else "$MYAUDIOBOOKS_BASE_URL/page/$safePage/"
    }

    fun bookUrl(externalPath: String): String {
        val clean = externalPath.trim().trimStart('/')
        require(clean.isNotBlank() && !clean.contains("..")) { "Invalid MY-AUDIOBOOKS book path" }
        return "$MYAUDIOBOOKS_BASE_URL/$clean"
    }

    fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
        val clean = externalId.trim().trim('/')
        require(clean.isNotBlank() && !clean.contains("..")) { "Invalid MY-AUDIOBOOKS collection id" }
        val root = when (kind) {
            "author" -> "$MYAUDIOBOOKS_BASE_URL/tags/$clean/"
            "narrator" -> "$MYAUDIOBOOKS_BASE_URL/xfsearch/chtec/$clean/"
            "genre" -> "$MYAUDIOBOOKS_BASE_URL/$clean/"
            "series" -> "$MYAUDIOBOOKS_BASE_URL/xfsearch/series/$clean/"
            else -> error("Unknown MY-AUDIOBOOKS collection kind: $kind")
        }
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) root else root + "page/$safePage/"
    }

    fun parseEntityRef(value: String, kind: String): String? {
        val prefix = "$MYAUDIOBOOKS_SOURCE:$kind:"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        val token = value.substring(prefix.length).trim().trim('/')
        return token.takeIf { it.isNotBlank() && !it.contains("..") }
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val roots = document.select("#dle-content .main-news.ajax-news, #dle-content .main-news")
        val result = mutableListOf<LiveCatalogItemDto>()
        val seen = linkedSetOf<String>()
        for (root in roots) {
            val item = parseCard(root, pageUrl) ?: continue
            if (seen.add(item.externalId)) result += item
        }
        return result
    }

    fun parseHomepageGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val result = mutableListOf<GenreDto>()
        val seen = linkedSetOf<String>()
        for (anchor in document.select(".left-menu > div > a[href]")) {
            val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            val externalId = genreExternalId(href) ?: continue
            if (!seen.add(externalId.lowercase())) continue
            val name = clean(anchor.ownText()).ifBlank { clean(anchor.text()).replace(Regex("\\d+\\s*книг.*$"), "").trim() }
            if (name.isNotBlank()) result += GenreDto(entityId("genre", externalId), name)
        }
        return result
    }

    fun parseMetadata(
        rawHtml: String,
        pageUrl: String,
        bookId: String,
        externalPath: String,
    ): BookDetailDto {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val title = clean(document.selectFirst(".full-news-title h1, h1")?.text().orEmpty())
            .ifBlank { clean(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()) }
        val authorNode = labelNode(document, "Автор")
        val narratorNode = labelNode(document, "Исполнитель")
        val seriesNode = labelNode(document, "Серия")
        val seriesAnchor = seriesNode?.selectFirst("a[href]")
        val seriesName = clean(seriesAnchor?.text().orEmpty())
            .ifBlank { textAfterLabel(seriesNode, "Серия") }
        val seriesExternalId = seriesExternalId(seriesName, seriesAnchor, pageUrl)
        val seriesPosition = inferSeriesPosition(title, seriesName)
        val authors = peopleFromLabel(authorNode, "author", pageUrl)
        val narrators = peopleFromLabel(narratorNode, "narrator", pageUrl)
        val genres = document.select(".full-news-tb .main-news-c a[href]")
            .mapNotNull { anchor ->
                val name = clean(anchor.text())
                val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
                val externalId = genreExternalId(href)
                if (name.isBlank()) null else GenreDto(externalId?.let { entityId("genre", it) }.orEmpty(), name)
            }
            .distinctBy { it.name.lowercase() }
        val coverUrl = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            .ifBlank {
                document.selectFirst(".full-news-image img[data-src]")
                    ?.let { it.absUrl("data-src").ifBlank { absoluteUrl(pageUrl, it.attr("data-src")) } }
                    .orEmpty()
            }
            .let(ApiClient::externalHttpUrl)
            .orEmpty()
        val durationSeconds = durationSeconds(textAfterLabel(labelNode(document, "Время"), "Время"))
        val descriptionNode = document.selectFirst(".full-news-text.mjjr, .full-news-text")?.clone()
        descriptionNode?.select(".age-restrictions")?.remove()
        val description = clean(descriptionNode?.text().orEmpty())
        val audioSeries = if (seriesExternalId.isBlank() || seriesName.isBlank()) emptyList() else listOf(
            AudioSeriesBriefDto(
                id = "source:$MYAUDIOBOOKS_SOURCE:$seriesExternalId",
                name = seriesName,
                position = seriesPosition?.toDouble(),
                provider = MYAUDIOBOOKS_SOURCE,
                externalId = seriesExternalId,
                sourceName = MYAUDIOBOOKS_SOURCE_NAME,
            )
        )

        return BookDetailDto(
            id = bookId,
            title = title,
            authors = authors,
            narrators = narrators,
            genres = genres,
            coverUrl = coverUrl,
            durationSeconds = durationSeconds,
            sourceCodes = listOf(MYAUDIOBOOKS_SOURCE),
            primarySource = MYAUDIOBOOKS_SOURCE,
            selectedSource = MYAUDIOBOOKS_SOURCE,
            selectedBookSourceId = "live:$MYAUDIOBOOKS_SOURCE:$externalPath",
            description = description,
            seriesName = seriesName,
            seriesPosition = seriesPosition,
            sourceSeriesName = seriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = audioSeries,
        )
    }

    fun isUnavailablePage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val access = textAfterLabel(labelNode(document, "Доступ"), "Доступ").lowercase()
        val playerText = clean(document.selectFirst(".gnth")?.text().orEmpty()).lowercase()
        return "книга заблокирована" in access ||
            "произведение удалено по требованию правообладателя" in playerText
    }

    fun parsePlaylistUrl(rawHtml: String, pageUrl: String): String? {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val scripts = document.select(".gnth script, script")
        for (script in scripts) {
            val match = playerPlaylistRegex.find(script.data()) ?: playerPlaylistRegex.find(script.html()) ?: continue
            val url = match.groupValues[1]
            if (isAllowedCdnUrl(url)) return url
        }
        return null
    }

    fun parsePlaylist(rawPlaylist: String, bookId: String): List<ChapterDto> {
        val array = try {
            JSONArray(rawPlaylist)
        } catch (error: Exception) {
            throw IOException("MY-AUDIOBOOKS returned invalid playlist JSON", error)
        }
        val result = mutableListOf<ChapterDto>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val rawUrl = row.optString("file").trim()
            val url = ApiClient.externalHttpUrl(rawUrl) ?: continue
            if (!isAllowedCdnUrl(url) || !URI(url).path.orEmpty().endsWith(".mp3", ignoreCase = true)) continue
            val title = clean(row.optString("title")).ifBlank { "Часть ${index + 1}" }
            result += ChapterDto(
                id = "$bookId:chapter:${index + 1}",
                position = index,
                title = title,
                durationSeconds = 0L,
                streamUrl = url,
            )
        }
        return result
    }

    fun attachChapters(
        metadata: BookDetailDto,
        chapters: List<ChapterDto>,
        externalPath: String,
        pageUrl: String,
    ): BookDetailDto {
        val variant = SourceVariantDto(
            bookSourceId = "live:$MYAUDIOBOOKS_SOURCE:$externalPath",
            sourceCode = MYAUDIOBOOKS_SOURCE,
            sourceName = MYAUDIOBOOKS_SOURCE_NAME,
            externalId = externalPath,
            externalUrl = pageUrl,
            chaptersCount = chapters.size,
            durationSeconds = metadata.durationSeconds,
            seriesName = metadata.sourceSeriesName,
            seriesPosition = metadata.sourceSeriesPosition,
        )
        return metadata.copy(sourceVariants = listOf(variant), chapters = chapters)
    }

    fun hasNextPage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return document.selectFirst(".nav-load a[href]")?.attr("href")?.isNotBlank() == true
    }

    fun isAllowedCdnUrl(raw: String): Boolean = try {
        val uri = URI(raw)
        val host = uri.host.orEmpty().lowercase()
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "redirectto.cc" || host.endsWith(".redirectto.cc"))
    } catch (_: Exception) {
        false
    }

    private fun parseCard(root: Element, pageUrl: String): LiveCatalogItemDto? {
        val titleAnchor = root.selectFirst(".main-news-title a[href]") ?: return null
        val href = titleAnchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, titleAnchor.attr("href")) }
        val externalPath = bookPath(href) ?: return null
        val title = clean(titleAnchor.text()).ifBlank { clean(root.selectFirst("img[alt]")?.attr("alt").orEmpty()) }
        if (title.isBlank()) return null
        val authorNode = labelNode(root, "Автор")
        val narratorNode = labelNode(root, "Исполнитель")
        val seriesNode = labelNode(root, "Серия")
        val seriesAnchor = seriesNode?.selectFirst("a[href]")
        val seriesName = clean(seriesAnchor?.text().orEmpty()).ifBlank { textAfterLabel(seriesNode, "Серия") }
        val seriesExternalId = seriesExternalId(seriesName, seriesAnchor, pageUrl)
        val genres = root.select(".main-news-c a[href]").map { clean(it.text()) }.filter(String::isNotBlank).distinct()
        val cover = root.selectFirst(".main-news-image img[data-src], img[data-src]")
            ?.let { it.absUrl("data-src").ifBlank { absoluteUrl(pageUrl, it.attr("data-src")) } }
            ?.let(ApiClient::externalHttpUrl)
            .orEmpty()
        val duration = durationSeconds(root.selectFirst(".main-news-play")?.text().orEmpty())
        return LiveCatalogItemDto(
            key = "$MYAUDIOBOOKS_SOURCE:$externalPath",
            source = MYAUDIOBOOKS_SOURCE,
            externalId = externalPath,
            externalUrl = href,
            title = title,
            coverUrl = cover,
            durationSeconds = duration,
            authors = namesFromLabel(authorNode),
            narrators = namesFromLabel(narratorNode),
            genres = genres,
            seriesName = seriesName,
            seriesExternalId = seriesExternalId,
            seriesPosition = inferSeriesPosition(title, seriesName),
        )
    }

    private fun seriesExternalId(seriesName: String, anchor: Element?, pageUrl: String): String {
        if (anchor != null) {
            val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            externalIdFromHref("series", href)?.let { return it }
        }
        val cleanName = clean(seriesName)
        return if (cleanName.isBlank()) "" else URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
    }

    private fun peopleFromLabel(node: Element?, kind: String, pageUrl: String): List<PersonDto> {
        if (node == null) return emptyList()
        val result = mutableListOf<PersonDto>()
        val seen = linkedSetOf<String>()
        for (anchor in node.select("a[href]")) {
            val name = clean(anchor.text())
            if (!isMeaningfulName(name) || !seen.add(name.lowercase())) continue
            val href = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            val externalId = externalIdFromHref(kind, href)
            result += PersonDto(externalId?.let { entityId(kind, it) }.orEmpty(), name)
        }
        return result
    }

    private fun namesFromLabel(node: Element?): List<String> = node
        ?.select("a[href]")
        ?.map { clean(it.text()) }
        ?.filter(::isMeaningfulName)
        ?.distinctBy(String::lowercase)
        .orEmpty()

    private fun labelNode(root: Element, vararg labels: String): Element? {
        val targets = labels.map { normalizeLabel(it) }.toSet()
        return root.select(".fnsc-left > div").firstOrNull { row ->
            val label = normalizeLabel(row.selectFirst("i")?.text().orEmpty())
            label in targets
        }
    }

    private fun textAfterLabel(node: Element?, vararg labels: String): String {
        if (node == null) return ""
        val copy = node.clone()
        copy.select("i").remove()
        var text = clean(copy.text())
        for (label in labels) {
            text = text.removePrefix(label).removePrefix(":").trim()
        }
        return text
    }

    private fun normalizeLabel(value: String): String = clean(value).trimEnd(':').trim().lowercase()

    private fun durationSeconds(value: String): Long {
        val match = clockDurationRegex.find(value) ?: return 0L
        val hours = match.groupValues[1].toLongOrNull() ?: return 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: return 0L
        return hours * 3600L + minutes * 60L + seconds
    }

    private fun bookPath(url: String): String? {
        return try {
            val uri = URI(url)
            if (!uri.host.equals("my-audiobooks.com", ignoreCase = true)) {
                null
            } else {
                bookPathRegex.matchEntire(uri.rawPath.orEmpty())?.groupValues?.getOrNull(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun genreExternalId(url: String): String? {
        return try {
            val uri = URI(url)
            if (!uri.host.equals("my-audiobooks.com", ignoreCase = true)) {
                null
            } else {
                val parts = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
                parts.singleOrNull()?.takeIf { it !in RESERVED_ROOTS }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun externalIdFromHref(kind: String, url: String): String? {
        return try {
            val uri = URI(url)
            if (!uri.host.equals("my-audiobooks.com", ignoreCase = true)) {
                null
            } else {
                val parts = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
                when (kind) {
                    "author" -> if (parts.size == 2 && parts[0].equals("tags", true)) parts[1] else null
                    "narrator" -> if (parts.size == 3 && parts[0].equals("xfsearch", true) && parts[1].equals("chtec", true)) parts[2] else null
                    "series" -> if (parts.size == 3 && parts[0].equals("xfsearch", true) && parts[1].equals("series", true)) parts[2] else null
                    "genre" -> genreExternalId(url)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun entityId(kind: String, externalId: String): String = "$MYAUDIOBOOKS_SOURCE:$kind:$externalId"

    private fun inferSeriesPosition(title: String, seriesName: String): Int? {
        if (seriesName.isBlank()) return null
        val workTitle = title.substringBeforeLast(" - ").trim()
        val seriesIndex = workTitle.indexOf(seriesName, ignoreCase = true)
        if (seriesIndex < 0) return null
        val suffix = workTitle.substring(seriesIndex + seriesName.length).trim().trimStart('.', ':', '-', '–', '—', '№').trim()
        val token = suffix.split(Regex("\\s+")).firstOrNull()?.trim('.', ',', ':', ';') ?: return null
        token.toIntOrNull()?.let { return it }
        return romanToInt(token)
    }

    private fun romanToInt(value: String): Int? {
        val text = value.uppercase()
        if (text.isBlank() || !text.all { it in "IVXLCDM" }) return null
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var previous = 0
        for (char in text.reversed()) {
            val current = values[char] ?: return null
            if (current < previous) total -= current else {
                total += current
                previous = current
            }
        }
        return total.takeIf { it > 0 }
    }

    private fun absoluteUrl(base: String, value: String): String = try {
        URI(base).resolve(value.trim()).toString()
    } catch (_: Exception) {
        ""
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun isMeaningfulName(value: String): Boolean = value.isNotBlank() && value != "-"

    private val RESERVED_ROOTS = setOf(
        "engine", "templates", "uploads", "xfsearch", "tags", "page", "blog", "authors.html",
        "performers.html", "series.html", "knigi-po-zhanram.html", "topbooks.html",
    )
}
