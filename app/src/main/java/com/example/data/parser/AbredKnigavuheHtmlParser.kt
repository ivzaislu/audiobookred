package com.example.data.parser

import com.example.data.api.ApiClient
import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.model.SourceVariantDto
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal class PreviewOnlyKnigavuheBook(
    val previewBook: BookDetailDto? = null,
    message: String = "knigavuhe_preview_only",
) : IOException(message)

internal class UnavailableKnigavuheBook(message: String = "knigavuhe_unavailable") : IOException(message)

internal object AbredKnigavuheHtmlParser {
    private val bookPlayerPrefixRegex = Regex(
        "new\\s+BookPlayer\\(\\s*\\d+\\s*,\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun catalogPageUrl(page: Int): String =
        if (page.coerceAtLeast(1) == 1) "$KNIGAVUHE_BASE_URL/new/" else "$KNIGAVUHE_BASE_URL/new/?page=${page.coerceAtLeast(1)}"

    fun searchUrl(query: String): String =
        "$KNIGAVUHE_BASE_URL/search/?q=${URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())}"

    fun bookUrl(externalId: String): String = "$KNIGAVUHE_BASE_URL/book/${externalId.trim('/')}/"

    fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
        require(kind in setOf("author", "reader", "genre", "series", "serie")) { "Unsupported Knigavuhe collection: $kind" }
        val route = if (kind == "serie") "series" else kind
        val root = "$KNIGAVUHE_BASE_URL/$route/${externalId.trim('/')}/"
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) root else "$root?page=$safePage"
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val result = mutableListOf<LiveCatalogItemDto>()
        val seen = linkedSetOf<String>()
        for (card in document.select("div.bookkitem")) {
            if (card.selectFirst(".bookkitem_litres_icon") != null) continue
            val item = parseCard(card, pageUrl) ?: continue
            if (seen.add(item.externalId)) result += item
        }
        return result
    }

    fun parseGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val seen = linkedSetOf<String>()
        val result = mutableListOf<GenreDto>()
        for (anchor in document.select("a[href^='/genre/'], a[href*='knigavuhe.org/genre/']")) {
            val externalId = entityExternalId(anchor.absUrl("href").ifBlank { anchor.attr("href") }, "genre")
            val name = clean(anchor.text())
            if (externalId.isBlank() || name.isBlank() || !seen.add(externalId)) continue
            result += GenreDto(entityRef("genre", externalId), name)
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun parseMetadata(rawHtml: String, pageUrl: String, bookKey: String): BookDetailDto {
        val parsed = parseLiveBookKey(bookKey) ?: error("Invalid live book key: $bookKey")
        require(parsed.first == KNIGAVUHE_SOURCE) { "Not a Knigavuhe key: $bookKey" }
        val document = Jsoup.parse(rawHtml, pageUrl)
        if (pageUrl.contains("/paid/book/", ignoreCase = true)) throw UnavailableKnigavuheBook("knigavuhe_litres_only")

        val title = bookName(rawHtml).ifBlank {
            clean(document.selectFirst("h1")?.ownText().orEmpty()).ifBlank {
                clean(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            }
        }
        if (title.isBlank()) throw IOException("Knigavuhe detail title is missing")
        val authors = namedLinks(document.select("span.book_title_elem > span > a[href^='/author/']"), "author")
            .ifEmpty { namedLinks(document.select("h1 a[href^='/author/']"), "author") }
        val readers = namedLinks(document.select("a[href^='/reader/']"), "reader").take(8)
        val genres = namedLinks(document.select("a[href^='/genre/']"), "genre").take(12)
        val seriesLink = document.selectFirst("div.book_serie_block_title > a[href^='/series/'], div.book_serie_block_title > a[href^='/serie/']")
            ?: document.selectFirst("a[href^='/series/'], a[href^='/serie/']")
        val seriesName = clean(seriesLink?.text().orEmpty())
        val seriesExternalId = seriesLink?.let {
            seriesExternalId(it.absUrl("href").ifBlank { it.attr("href") })
        }.orEmpty()
        val seriesPosition = currentSeriesPosition(document) ?: inferSeriesPosition(title, seriesName)
        val cover = document.selectFirst("div.book_cover img")?.let { imageUrl(it, pageUrl) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { absoluteUrl(pageUrl, it) }.orEmpty()
        val description = clean(document.selectFirst("div.book_description")?.text().orEmpty())
        val duration = durationSeconds(document.text())
        val sourceIdentity = "live:$KNIGAVUHE_SOURCE:${parsed.second}"
        val audioSeries = if (seriesName.isBlank() || seriesExternalId.isBlank()) emptyList() else listOf(
            AudioSeriesBriefDto(
                id = "source:$KNIGAVUHE_SOURCE:$seriesExternalId",
                name = seriesName,
                position = seriesPosition?.toDouble(),
                provider = KNIGAVUHE_SOURCE,
                externalId = seriesExternalId,
                sourceName = "Книга в ухе",
            )
        )
        return BookDetailDto(
            id = bookKey,
            title = title,
            authors = authors.map { PersonDto(entityRef("author", it.first), it.second) },
            narrators = readers.map { PersonDto(entityRef("reader", it.first), it.second) },
            genres = genres.map { GenreDto(entityRef("genre", it.first), it.second) },
            coverUrl = cover,
            durationSeconds = duration,
            sourceCodes = listOf(KNIGAVUHE_SOURCE),
            primarySource = KNIGAVUHE_SOURCE,
            selectedSource = KNIGAVUHE_SOURCE,
            selectedBookSourceId = sourceIdentity,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = sourceIdentity,
                    sourceCode = KNIGAVUHE_SOURCE,
                    sourceName = "Книга в ухе",
                    seriesName = seriesName,
                )
            ),
            description = description,
            seriesName = seriesName,
            seriesPosition = seriesPosition,
            sourceSeriesName = seriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = audioSeries,
            chapters = emptyList(),
        )
    }

    fun parsePlaylist(rawHtml: String, pageUrl: String, bookKey: String): List<ChapterDto> {
        val prefix = bookPlayerPrefixRegex.find(rawHtml)
        if (prefix == null) {
            val reason = if (hasLitresPartner(rawHtml, pageUrl)) "knigavuhe_litres_only" else "knigavuhe_preview_only"
            throw PreviewOnlyKnigavuheBook(message = reason)
        }
        val rawArray = extractBalancedJsonArray(rawHtml, prefix.range.last + 1)
            ?: throw PreviewOnlyKnigavuheBook(message = "knigavuhe_preview_only")
        val array = runCatching { JSONArray(rawArray) }.getOrNull()
            ?: throw PreviewOnlyKnigavuheBook(message = "knigavuhe_preview_only")
        val chapters = mutableListOf<ChapterDto>()
        val seen = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val rawUrl = item.optString("url").trim()
            val mediaUrl = ApiClient.externalHttpUrl(absoluteUrl(pageUrl, rawUrl)) ?: continue
            if (isPartnerUrl(mediaUrl) || !seen.add(mediaUrl)) continue
            val duration = when (val raw = item.opt("duration")) {
                is Number -> raw.toLong()
                else -> durationValue(raw?.toString().orEmpty())
            }.coerceAtLeast(0L)
            chapters += ChapterDto(
                id = "$bookKey:chapter:${index + 1}",
                position = chapters.size,
                title = clean(item.optString("title")).ifBlank { "Часть ${chapters.size + 1}" },
                durationSeconds = duration,
                streamUrl = mediaUrl,
            )
        }
        if (chapters.isEmpty()) {
            val reason = if (hasLitresPartner(rawHtml, pageUrl)) "knigavuhe_litres_only" else "knigavuhe_preview_only"
            throw PreviewOnlyKnigavuheBook(message = reason)
        }
        return chapters
    }

    fun isPaidPage(rawHtml: String, pageUrl: String): Boolean {
        if (pageUrl.contains("/paid/book/", ignoreCase = true)) return true
        val document = Jsoup.parse(rawHtml, pageUrl)
        return document.selectFirst(".bookkitem_litres_icon") != null &&
            document.selectFirst("script:containsData(BookPlayer)") == null
    }

    fun isLikelyPreview(declaredDurationSeconds: Long, chapters: List<ChapterDto>): Boolean {
        if (declaredDurationSeconds <= 0L || chapters.isEmpty()) return false
        val parsedDuration = chapters.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        if (parsedDuration <= 0L) return false
        return parsedDuration * 100L < declaredDurationSeconds * 60L
    }

    fun parseCollectionName(rawHtml: String, pageUrl: String): String {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val heading = clean(document.selectFirst("h1")?.text().orEmpty())
        return Regex("Цикл\\s+«([^»]+)»", RegexOption.IGNORE_CASE)
            .find(heading)?.groupValues?.getOrNull(1)?.let(::clean).orEmpty()
    }

    fun parseEntityRef(value: String, expectedKind: String): String? {
        val parts = value.trim().split(':', limit = 3)
        if (parts.size != 3 || parts[0].lowercase() != KNIGAVUHE_SOURCE || parts[1].lowercase() != expectedKind) return null
        return parts[2].trim('/').takeIf(String::isNotBlank)
    }

    private fun parseCard(card: Element, pageUrl: String): LiveCatalogItemDto? {
        val link = card.selectFirst("a.bookkitem_name[href], a.bookkitem_cover[href]") ?: return null
        val externalUrl = link.absUrl("href").ifBlank { absoluteUrl(pageUrl, link.attr("href")) }
        if (externalUrl.contains("/paid/", ignoreCase = true) || isPartnerUrl(externalUrl)) return null
        val externalId = bookExternalId(externalUrl)
        if (externalId.isBlank()) return null
        val title = clean(card.selectFirst("a.bookkitem_name")?.text().orEmpty())
        if (title.isBlank()) return null
        val authors = namedLinks(card.select("span.bookkitem_author a[href^='/author/'], a[href^='/author/']"), "author")
            .map { it.second }.distinct().take(5)
        val readers = namedLinks(card.select("a[href^='/reader/']"), "reader").map { it.second }.distinct().take(8)
        val genres = namedLinks(card.select("a[href^='/genre/']"), "genre").map { it.second }.distinct().take(8)
        val seriesLink = card.selectFirst("a[href^='/series/'], a[href^='/serie/']")
        val seriesName = clean(seriesLink?.text().orEmpty())
        val seriesExternalId = seriesLink?.let {
            seriesExternalId(it.absUrl("href").ifBlank { it.attr("href") })
        }.orEmpty()
        val seriesPosition = clean(card.selectFirst("span.bookkitem_serie_index")?.text().orEmpty())
            .trim('.').toIntOrNull() ?: inferSeriesPosition(title, seriesName)
        val cover = card.selectFirst("img.bookkitem_cover_img")?.let { imageUrl(it, pageUrl) }.orEmpty()
        return LiveCatalogItemDto(
            key = "$KNIGAVUHE_SOURCE:$externalId",
            source = KNIGAVUHE_SOURCE,
            externalId = externalId,
            externalUrl = externalUrl,
            title = title.removePrefix(seriesPosition?.let { "$it. " }.orEmpty()),
            coverUrl = cover,
            durationSeconds = durationValue(card.selectFirst("span.bookkitem_meta_time")?.text().orEmpty()),
            authors = authors,
            narrators = readers,
            genres = genres,
            seriesName = seriesName,
            seriesExternalId = seriesExternalId,
            seriesPosition = seriesPosition,
        )
    }

    private fun namedLinks(elements: Iterable<Element>, kind: String): List<Pair<String, String>> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()
        for (element in elements) {
            val externalId = entityExternalId(element.absUrl("href").ifBlank { element.attr("href") }, kind)
            val name = clean(element.text())
            if (externalId.isBlank() || name.isBlank() || !seen.add(externalId)) continue
            result += externalId to name
        }
        return result
    }

    private fun entityRef(kind: String, externalId: String): String = "$KNIGAVUHE_SOURCE:$kind:${externalId.trim('/')}"

    private fun entityExternalId(url: String, kind: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val marker = "/$kind/"
        val start = path.indexOf(marker)
        if (start < 0) return ""
        return path.substring(start + marker.length).trim('/').takeIf(String::isNotBlank).orEmpty()
    }

    private fun seriesExternalId(url: String): String =
        entityExternalId(url, "series").ifBlank { entityExternalId(url, "serie") }

    private fun bookExternalId(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val marker = "/book/"
        val start = path.indexOf(marker)
        if (start < 0 || "/paid/book/" in path) return ""
        return path.substring(start + marker.length).substringBefore('/').trim()
    }

    private fun bookName(rawHtml: String): String {
        val marker = "cur.book"
        val markerStart = rawHtml.indexOf(marker)
        if (markerStart < 0) return ""
        val assignment = rawHtml.indexOf('=', markerStart + marker.length)
        if (assignment < 0) return ""
        val objectStart = rawHtml.indexOf('{', assignment + 1)
        if (objectStart < 0) return ""

        var depth = 0
        var inString = false
        var escaped = false
        var objectEnd = -1
        for (index in objectStart until rawHtml.length) {
            val char = rawHtml[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (char) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        objectEnd = index + 1
                        break
                    }
                }
            }
        }
        if (objectEnd <= objectStart) return ""
        val json = rawHtml.substring(objectStart, objectEnd)
        return runCatching { clean(JSONObject(json).optString("name")) }.getOrDefault("")
    }

    private fun extractBalancedJsonArray(raw: String, fromIndex: Int): String? {
        var index = fromIndex.coerceAtLeast(0)
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length || raw[index] != '[') return null

        val start = index
        var depth = 0
        var inString = false
        var escaped = false
        while (index < raw.length) {
            val char = raw[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (char) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '[' -> depth += 1
                    ']' -> {
                        depth -= 1
                        if (depth == 0) return raw.substring(start, index + 1)
                        if (depth < 0) return null
                    }
                }
            }
            index++
        }
        return null
    }

    private fun hasLitresPartner(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return document.select("a[href]").any { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            isPartnerUrl(href)
        }
    }

    private fun isPartnerUrl(url: String): Boolean {
        val lower = url.lowercase()
        return "/go-partner/" in lower || "/paid/book/" in lower || "litres.ru" in lower || "litres.com" in lower
    }

    private fun currentSeriesPosition(document: Document): Int? {
        return document.select("div.book_serie_block_item").firstOrNull { it.selectFirst("strong") != null }
            ?.selectFirst("span")?.text()?.let(::clean)?.trim('.')?.toIntOrNull()
    }

    private fun inferSeriesPosition(title: String, seriesName: String): Int? {
        if (seriesName.isBlank()) return null
        return Regex("(?:книга|том|часть)\\s*(?:№|#)?\\s*([0-9]{1,3})", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("^([0-9]{1,3})[.\\s]").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun imageUrl(element: Element, pageUrl: String): String {
        val raw = element.attr("data-src").takeIf(String::isNotBlank)
            ?: element.attr("data-original").takeIf(String::isNotBlank)
            ?: element.attr("src")
        return absoluteUrl(pageUrl, raw)
    }

    private fun absoluteUrl(base: String, raw: String): String {
        val value = raw.trim().replace("\\/", "/")
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun durationSeconds(text: String): Long {
        val normalized = clean(text)
        val marker = Regex(
            "(?:общее\\s+)?время звучания\\s*:\\s*((?:\\d{1,3}:)?\\d{1,2}:\\d{2})(?=\\s|$)",
            RegexOption.IGNORE_CASE,
        ).find(normalized)?.groupValues?.getOrNull(1)
        return marker?.let(::durationValue) ?: 0L
    }

    private fun durationValue(value: String): Long {
        val clean = clean(value).lowercase()
        Regex("^(\\d{1,3}):(\\d{2}):(\\d{2})$").find(clean)?.let { match ->
            return (match.groupValues[1].toLongOrNull() ?: 0L) * 3600L +
                (match.groupValues[2].toLongOrNull() ?: 0L) * 60L +
                (match.groupValues[3].toLongOrNull() ?: 0L)
        }
        Regex("^(\\d{1,3}):(\\d{2})$").find(clean)?.let { match ->
            return (match.groupValues[1].toLongOrNull() ?: 0L) * 60L +
                (match.groupValues[2].toLongOrNull() ?: 0L)
        }
        var total = 0L
        Regex("(\\d+)\\s*(?:ч|час)").find(clean)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 3600L }
        Regex("(\\d+)\\s*(?:мин|мину)").find(clean)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 60L }
        Regex("(\\d+)\\s*(?:сек|секунд)").find(clean)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it }
        return total
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}

internal const val KNIGAVUHE_SOURCE = "knigavuhe"
internal const val KNIGAVUHE_BASE_URL = "https://knigavuhe.org"
