package com.example.data.parser

import com.example.data.api.ApiClient
import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.model.SourceVariantDto
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal const val AUDIOKNIGA_LIFE_SOURCE = "audioknigalife"
internal const val AUDIOKNIGA_LIFE_SOURCE_NAME = "Audiokniga.Life"
internal const val AUDIOKNIGA_LIFE_BASE_URL = "https://audiokniga.life"

internal object AbredAudioknigaLifeHtmlParser {
    private val bookPathRegex = Regex("^/((?:[^/?#]+/)+\\d+[^/?#]*\\.html)$", RegexOption.IGNORE_CASE)
    private val playerJsonMarkerRegex = Regex(
        """playerInit\s*\(\s*\d+\s*,.*?['"]json['"]\s*,""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val clockRegex = Regex("""\b(\d{1,3}):([0-5]?\d):([0-5]?\d)\b""")
    private val detailSeriesPositionRegex = Regex("""#\s*(\d+)\s+из\s+\d+""", RegexOption.IGNORE_CASE)
    private val cardSeriesPositionRegex = Regex("""\((\d+)\)""")

    fun catalogPageUrl(page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) AUDIOKNIGA_LIFE_BASE_URL + "/" else AUDIOKNIGA_LIFE_BASE_URL + "/page/" + safePage + "/"
    }

    fun bookUrl(externalPath: String): String {
        val clean = externalPath.trim().trimStart('/')
        require(clean.isNotBlank() && !clean.contains("..")) { "Invalid Audiokniga.Life book path" }
        return AUDIOKNIGA_LIFE_BASE_URL + "/" + clean
    }

    fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
        val clean = externalId.trim().trim('/')
        require(clean.isNotBlank() && !clean.contains("..")) { "Invalid Audiokniga.Life collection id" }
        val root = when (kind) {
            "author" -> AUDIOKNIGA_LIFE_BASE_URL + "/xfsearch/avtor/" + clean + "/"
            "narrator" -> AUDIOKNIGA_LIFE_BASE_URL + "/xfsearch/ispolnitel/" + clean + "/"
            "series" -> AUDIOKNIGA_LIFE_BASE_URL + "/xfsearch/serie/" + clean + "/"
            "genre" -> AUDIOKNIGA_LIFE_BASE_URL + "/" + clean + "/"
            else -> error("Unknown Audiokniga.Life collection kind: " + kind)
        }
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) root else root + "page/" + safePage + "/"
    }

    fun parseEntityRef(value: String, kind: String): String? {
        val prefix = AUDIOKNIGA_LIFE_SOURCE + ":" + kind + ":"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        return value.substring(prefix.length).trim().trim('/').takeIf { it.isNotBlank() && !it.contains("..") }
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val result = mutableListOf<LiveCatalogItemDto>()
        val seen = linkedSetOf<String>()
        for (root in document.select("#dle-content .short-item.bookitem")) {
            val item = parseCard(root, pageUrl) ?: continue
            if (seen.add(item.externalId)) result += item
        }
        return result
    }

    fun parseGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val result = mutableListOf<GenreDto>()
        val seen = linkedSetOf<String>()
        for (anchor in document.select(".spisok-ganrov a[href]")) {
            val name = clean(anchor.selectFirst(".genre_name")?.text().orEmpty())
            val externalId = genreExternalId(absoluteUrl(pageUrl, anchor.attr("href"))) ?: continue
            if (name.isBlank() || !seen.add(externalId.lowercase())) continue
            result += GenreDto(entityId("genre", externalId), name)
        }
        return result
    }

    fun parseMetadata(rawHtml: String, pageUrl: String, bookId: String, externalPath: String): BookDetailDto {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val title = clean(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            .ifBlank { clean(document.selectFirst("#dle-content h1, h1")?.text().orEmpty()) }
        val rows = document.select(".full-news-stats .fstat-item")
        val authorRow = statRow(rows, "автор")
        val narratorRow = statRow(rows, "читает")
        val seriesRow = statRow(rows, "серия")
        val genreRow = statRow(rows, "жанр")
        val authors = people(authorRow, "author", "avtor", pageUrl)
        val narrators = people(narratorRow, "narrator", "ispolnitel", pageUrl)
        val genres = genreRow?.select("a[href]")?.mapNotNull { anchor ->
            val name = clean(anchor.text())
            val externalId = genreExternalId(absoluteUrl(pageUrl, anchor.attr("href")))
            if (name.isBlank()) null else GenreDto(externalId?.let { entityId("genre", it) }.orEmpty(), name)
        }?.distinctBy { it.name.lowercase() }.orEmpty()
        val seriesAnchor = seriesRow?.selectFirst("a[href]")
        val seriesName = clean(seriesAnchor?.text().orEmpty())
        val seriesExternalId = seriesAnchor?.let {
            externalIdFromXfsearch(absoluteUrl(pageUrl, it.attr("href")), "serie")
        }.orEmpty()
        val seriesPosition = detailSeriesPositionRegex.find(seriesRow?.text().orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val coverUrl = ApiClient.externalHttpUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        ).orEmpty()
        val descriptionNode = document.selectFirst(".full_descr")?.clone()
        descriptionNode?.select(".full_descr_title, .short-tags")?.remove()
        val description = clean(descriptionNode?.text().orEmpty()).ifBlank {
            clean(document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty())
        }
        val audioSeries = if (seriesName.isBlank() || seriesExternalId.isBlank()) emptyList() else listOf(
            AudioSeriesBriefDto(
                id = "source:" + AUDIOKNIGA_LIFE_SOURCE + ":" + seriesExternalId,
                name = seriesName,
                position = seriesPosition?.toDouble(),
                provider = AUDIOKNIGA_LIFE_SOURCE,
                externalId = seriesExternalId,
                sourceName = AUDIOKNIGA_LIFE_SOURCE_NAME,
            )
        )
        return BookDetailDto(
            id = bookId,
            title = title,
            authors = authors,
            narrators = narrators,
            genres = genres,
            coverUrl = coverUrl,
            sourceCodes = listOf(AUDIOKNIGA_LIFE_SOURCE),
            primarySource = AUDIOKNIGA_LIFE_SOURCE,
            selectedSource = AUDIOKNIGA_LIFE_SOURCE,
            selectedBookSourceId = "live:" + AUDIOKNIGA_LIFE_SOURCE + ":" + externalPath,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = "live:" + AUDIOKNIGA_LIFE_SOURCE + ":" + externalPath,
                    sourceCode = AUDIOKNIGA_LIFE_SOURCE,
                    sourceName = AUDIOKNIGA_LIFE_SOURCE_NAME,
                    seriesName = seriesName,
                )
            ),
            description = description,
            seriesName = seriesName,
            seriesPosition = seriesPosition,
            sourceSeriesName = seriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = audioSeries,
        )
    }

    fun isPreviewOnly(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val blockedMessage = clean(document.selectFirst(".sukanah")?.text().orEmpty()).lowercase()
        if ("аудиокнига заблокирована" in blockedMessage) return true

        // Paid Audiokniga.Life pages replace the native playerInit playlist with
        // one LitRes trial item. This is an explicit source-page preview state,
        // not a missing/broken Audiokniga.Life playlist.
        val litresBlock = document.selectFirst(".llitres")
        if (litresBlock != null) {
            val text = clean(litresBlock.text()).lowercase()
            val hasTrial = litresBlock.select("a[href], script").any { node ->
                val haystack = node.attr("href") + " " + node.html()
                "litres.ru/audiotrial/" in haystack.lowercase()
            } || "ознакомительный фрагмент" in litresBlock.html().lowercase()
            val explicitlyPaid =
                "эта аудиокнига платная" in text ||
                    "купить и скачать аудиокнигу" in text
            if (hasTrial && explicitlyPaid) return true
        }
        return false
    }

    fun parseChapters(rawHtml: String, bookId: String): List<ChapterDto> {
        val json = extractPlayerJsonArray(rawHtml) ?: return emptyList()
        val rows = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<ChapterDto>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optInt("error", 0) != 0) continue
            val url = normalizeAudioUrl(row.optString("url")) ?: continue
            result += ChapterDto(
                id = bookId + ":chapter:" + (index + 1),
                position = index,
                title = clean(row.optString("title")).ifBlank { "Часть " + (index + 1) },
                durationSeconds = row.optLong("duration", 0L).coerceAtLeast(0L),
                streamUrl = url,
            )
        }
        return result
    }

    fun attachChapters(metadata: BookDetailDto, chapters: List<ChapterDto>): BookDetailDto =
        metadata.copy(
            durationSeconds = chapters.sumOf { it.durationSeconds.coerceAtLeast(0L) },
            chapters = chapters,
        )

    fun hasNextPage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return document.select(".pagination a[href]").any { clean(it.text()) == "»" }
    }

    private fun parseCard(root: Element, pageUrl: String): LiveCatalogItemDto? {
        val titleAnchor = root.selectFirst("a.bookitem_name[href], .bookitem_name a[href]") ?: return null
        val href = absoluteUrl(pageUrl, titleAnchor.attr("href"))
        val externalPath = bookPath(href) ?: return null
        val title = clean(titleAnchor.text())
        if (title.isBlank()) return null
        val author = root.selectFirst(".icon_author a[href]")
        val narrator = root.selectFirst(".icon_reader a[href]")
        val seriesBlock = root.selectFirst(".icon_serie")
        val seriesAnchor = seriesBlock?.selectFirst("a[href]")
        val seriesName = clean(seriesAnchor?.text().orEmpty())
        val seriesExternalId = seriesAnchor?.let {
            externalIdFromXfsearch(absoluteUrl(pageUrl, it.attr("href")), "serie")
        }.orEmpty()
        val seriesPosition = cardSeriesPositionRegex.find(seriesBlock?.text().orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val genres = root.select(".bookitem_genre a[href]").map { clean(it.text()) }.filter(String::isNotBlank).distinct()
        val coverUrl = root.selectFirst("img[data-src], img[src]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.let { absoluteUrl(pageUrl, it) }
            ?.let(ApiClient::externalHttpUrl)
            .orEmpty()
        return LiveCatalogItemDto(
            key = AUDIOKNIGA_LIFE_SOURCE + ":" + externalPath,
            source = AUDIOKNIGA_LIFE_SOURCE,
            externalId = externalPath,
            externalUrl = href,
            title = title,
            coverUrl = coverUrl,
            durationSeconds = durationSeconds(root.selectFirst(".icon_time")?.text().orEmpty()),
            authors = listOfNotNull(author?.text()?.let(::clean)?.takeIf(String::isNotBlank)),
            narrators = listOfNotNull(narrator?.text()?.let(::clean)?.takeIf(String::isNotBlank)),
            genres = genres,
            seriesName = seriesName,
            seriesExternalId = seriesExternalId,
            seriesPosition = seriesPosition,
        )
    }

    private fun people(root: Element?, kind: String, routeKind: String, pageUrl: String): List<PersonDto> =
        root?.select("a[href]")?.mapNotNull { anchor ->
            val name = clean(anchor.text())
            val token = externalIdFromXfsearch(absoluteUrl(pageUrl, anchor.attr("href")), routeKind)
            if (name.isBlank()) null else PersonDto(token?.let { entityId(kind, it) }.orEmpty(), name)
        }?.distinctBy { it.name.lowercase() }.orEmpty()

    private fun statRow(rows: Iterable<Element>, label: String): Element? = rows.firstOrNull { row ->
        clean(row.selectFirst(".fstat-item-title")?.text().orEmpty()).trimEnd(':').lowercase() == label
    }

    private fun extractPlayerJsonArray(rawHtml: String): String? {
        val marker = playerJsonMarkerRegex.find(rawHtml) ?: return null
        var index = marker.range.last + 1
        while (index < rawHtml.length && rawHtml[index].isWhitespace()) index += 1
        if (index >= rawHtml.length || rawHtml[index] != '[') return null

        val start = index
        var depth = 0
        var quote: Char? = null
        var escaped = false

        while (index < rawHtml.length) {
            val char = rawHtml[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    '[' -> depth += 1
                    ']' -> {
                        depth -= 1
                        if (depth == 0) return rawHtml.substring(start, index + 1)
                        if (depth < 0) return null
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun normalizeAudioUrl(raw: String): String? {
        val parsed = raw.trim().replace("\\/", "/").toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()
        if (!parsed.scheme.equals("https", true)) return null
        if (!Regex("""^lib\d+\.audiokniga\.life$""").matches(host)) return null
        if (!parsed.encodedPath.endsWith(".mp3", true)) return null
        return ApiClient.externalHttpUrl(parsed.toString())
    }

    private fun bookPath(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.host.equals("audiokniga.life", true)) null
        else bookPathRegex.matchEntire(uri.rawPath.orEmpty())?.groupValues?.getOrNull(1)
    }.getOrNull()

    private fun genreExternalId(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.host.equals("audiokniga.life", true)) return@runCatching null
        val parts = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (parts.isEmpty() || parts.first() in RESERVED_ROOTS) return@runCatching null
        parts.joinToString("/").takeIf { !it.endsWith(".html", true) }
    }.getOrNull()

    private fun externalIdFromXfsearch(url: String, kind: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.host.equals("audiokniga.life", true)) return@runCatching null
        val parts = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (parts.size == 3 && parts[0].equals("xfsearch", true) && parts[1].equals(kind, true)) parts[2] else null
    }.getOrNull()

    private fun entityId(kind: String, externalId: String): String =
        AUDIOKNIGA_LIFE_SOURCE + ":" + kind + ":" + externalId

    private fun durationSeconds(value: String): Long {
        val match = clockRegex.find(value) ?: return 0L
        val hours = match.groupValues[1].toLongOrNull() ?: return 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: return 0L
        return hours * 3600L + minutes * 60L + seconds
    }

    private fun absoluteUrl(base: String, raw: String): String = runCatching {
        URI(base).resolve(raw.trim()).toString()
    }.getOrDefault("")

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private val RESERVED_ROOTS = setOf("engine", "templates", "uploads", "xfsearch", "favorites", "page", "tags")
}
