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
                    seriesName = series.name,
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
