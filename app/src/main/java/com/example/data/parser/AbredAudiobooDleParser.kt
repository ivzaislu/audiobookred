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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Parser for the real current DataLife Engine markup used by audioboo.org. */
internal object AbredAudiobooDleParser {
    private val bookPathRegex = Regex("^/([^/?#]+/\\d+[^/?#]*\\.html)$", RegexOption.IGNORE_CASE)
    private val previewMarkers = listOf(
        "ознакомительный фрагмент",
        "фрагмент аудиокниги",
        "доступен только фрагмент",
    )
    private val rejectedCoverMarkers = listOf(
        "/templates/", "/dleimages/", "microphone", "logo", "favicon", "placeholder", "avatar", "sprite", "blank"
    )
    private val mediaExtensionRegex = Regex("(?i)\\.(?:mp3|m4a|aac|ogg|m3u8)(?:[?#].*)?$")
    private val directMediaRegex = Regex("(?i)https?://[^\\s'\"<>]+?\\.(?:mp3|m4a|aac|ogg|m3u8)(?:\\?[^\\s'\"<>]*)?")
    private val fieldMediaRegex = Regex("(?i)[\"']?(?:file|src|url)[\"']?\\s*[:=]\\s*[\"']([^\"']+\\.(?:mp3|m4a|aac|ogg|m3u8)(?:\\?[^\"']*)?)[\"']")
    private val knownMetadataLabels = listOf(
        "Год выпуска аудиокниги", "Автор", "Исполнитель", "Цикл", "Жанр", "Аудиокодек", "Битрейт",
        "Количество каналов (моно-стерео)", "Продолжительность", "Описание",
    )

    fun catalogPageUrl(page: Int): String {
        val safe = page.coerceAtLeast(1)
        return if (safe == 1) "$AUDIOBOO_BASE_URL/" else "$AUDIOBOO_BASE_URL/page/$safe/"
    }

    fun bookUrl(externalPath: String): String {
        val clean = externalPath.trim().trimStart('/')
        require(clean.isNotBlank() && !clean.contains("..")) { "Invalid Audioboo book path" }
        return "$AUDIOBOO_BASE_URL/$clean"
    }

    fun collectionPageUrl(kind: String, externalId: String, page: Int): String {
        val clean = externalId.trim().trim('/')
        require(clean.isNotBlank() && !clean.contains("..")) { "Invalid Audioboo collection id" }
        val root = when (kind) {
            "author" -> "$AUDIOBOO_BASE_URL/xfsearch/avtora/$clean/"
            "narrator" -> "$AUDIOBOO_BASE_URL/tags/$clean/"
            "genre" -> "$AUDIOBOO_BASE_URL/$clean/"
            "series" -> "$AUDIOBOO_BASE_URL/xfsearch/cikl/$clean/"
            else -> error("Unknown Audioboo collection kind: $kind")
        }
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1) root else root + "page/$safePage/"
    }

    fun parseEntityRef(value: String, kind: String): String? {
        val prefix = "$AUDIOBOO_SOURCE:$kind:"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        return value.substring(prefix.length).trim().trim('/').takeIf { it.isNotBlank() && !it.contains("..") }
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val realCards = document.select("#dle-content article.card")
            .ifEmpty { document.select("article.card") }
        val roots = if (realCards.isNotEmpty()) {
            realCards
        } else {
            document.select(".book-item,.shortstory,.short-story,.news-item")
        }

        val result = mutableListOf<LiveCatalogItemDto>()
        val seen = linkedSetOf<String>()
        for (root in roots) {
            val item = parseCard(root, pageUrl, requireBookMetadata = root.hasClass("card")) ?: continue
            if (seen.add(item.externalId)) result += item
        }
        return result
    }

    fun parseGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val result = mutableListOf<GenreDto>()
        val seen = linkedSetOf<String>()
        for (anchor in document.select(".navmenu a[href], .nav-menu a[href]")) {
            val href = absoluteUrl(pageUrl, anchor.attr("href"))
            val externalId = externalIdFromHref("genre", href) ?: continue
            val name = clean(anchor.text()).replace(Regex("\\s*\\[\\d+]\\s*$"), "").trim()
            if (name.isBlank() || !seen.add(externalId.lowercase())) continue
            result += GenreDto(entityId("genre", externalId), name)
        }
        return result
    }

    fun parseBook(
        rawHtml: String,
        pageUrl: String,
        bookId: String,
        externalPath: String,
    ): BookDetailDto {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val title = firstNonBlank(
            clean(document.selectFirst("h1")?.text().orEmpty()),
            clean(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()),
            titleFromPath(externalPath),
        )
        val coverUrl = firstUsableCover(
            document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
            document.selectFirst(".page__text img[data-src]")?.attr("data-src").orEmpty(),
            document.selectFirst(".page__text img[src]")?.attr("src").orEmpty(),
            pageUrl = pageUrl,
        )

        val authorNode = labelNode(document, "Автор")
        val narratorNode = labelNode(document, "Исполнитель", "Читает", "Чтец")
        val genreNode = labelNode(document, "Жанр")
        val seriesNode = labelNode(document, "Цикл", "Серия")

        val authors = peopleFromLabel(authorNode, "author", pageUrl)
            .ifEmpty { splitNames(metaValue(document, "Автор")).map { PersonDto("", it) } }
        val narrators = peopleFromLabel(narratorNode, "narrator", pageUrl)
            .ifEmpty { splitNames(metaValue(document, "Исполнитель")).map { PersonDto("", it) } }
        val genres = genresFromLabel(genreNode, pageUrl)
            .ifEmpty { splitNames(metaValue(document, "Жанр")).map { GenreDto("", it) } }

        val seriesAnchor = seriesNode?.selectFirst("a[href]")
        val seriesName = firstNonBlank(
            clean(seriesAnchor?.text().orEmpty()),
            metaValue(document, "Цикл"),
        )
        val seriesExternalId = seriesAnchor
            ?.let { absoluteUrl(pageUrl, it.attr("href")) }
            ?.let { externalIdFromHref("series", it) }
            .orEmpty()
        val seriesPosition = inferSeriesPosition(title, seriesName)
        val audioSeries = if (seriesExternalId.isBlank() || seriesName.isBlank()) {
            emptyList()
        } else {
            listOf(
                AudioSeriesBriefDto(
                    id = "source:$AUDIOBOO_SOURCE:$seriesExternalId",
                    name = seriesName,
                    position = seriesPosition?.toDouble(),
                    provider = AUDIOBOO_SOURCE,
                    externalId = seriesExternalId,
                    sourceName = "Audioboo",
                )
            )
        }

        val description = description(document)
        val duration = durationSeconds(
            firstNonBlank(
                textAfterLabel(labelNode(document, "Продолжительность", "Время"), "Продолжительность", "Время"),
                metaValue(document, "Продолжительность"),
                document.selectFirst("meta[name=description]")?.attr("content").orEmpty(),
            )
        )

        val metadata = BookDetailDto(
            id = bookId,
            title = title,
            authors = authors,
            narrators = narrators,
            genres = genres,
            coverUrl = coverUrl,
            durationSeconds = duration,
            sourceCodes = listOf(AUDIOBOO_SOURCE),
            primarySource = AUDIOBOO_SOURCE,
            selectedSource = AUDIOBOO_SOURCE,
            selectedBookSourceId = "live:$AUDIOBOO_SOURCE:$externalPath",
            description = description,
            seriesName = seriesName,
            seriesPosition = seriesPosition,
            sourceSeriesName = seriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = audioSeries,
        )

        val bodyText = clean(document.text()).lowercase()
        if (previewMarkers.any(bodyText::contains)) throw PreviewOnlyAudiobooBook(metadata)

        val chapters = mediaChapters(document, pageUrl, bookId)
        if (chapters.isEmpty()) throw IOException("Audioboo не вернул аудиофайлы для этой книги")
        val totalDuration = if (duration > 0) duration else chapters.sumOf { it.durationSeconds }
        val variant = SourceVariantDto(
            bookSourceId = "live:$AUDIOBOO_SOURCE:$externalPath",
            sourceCode = AUDIOBOO_SOURCE,
            sourceName = "Audioboo",
            externalId = externalPath,
            externalUrl = pageUrl,
            chaptersCount = chapters.size,
            durationSeconds = totalDuration,
            seriesName = seriesName,
            seriesPosition = seriesPosition,
        )
        return metadata.copy(
            durationSeconds = totalDuration,
            sourceVariants = listOf(variant),
            chapters = chapters,
        )
    }

    fun parseEmbeddedSeries(
        rawHtml: String,
        pageUrl: String,
        seriesName: String,
        seriesExternalId: String,
    ): List<LiveCatalogItemDto> {
        if (seriesName.isBlank() || seriesExternalId.isBlank()) return emptyList()
        val document = Jsoup.parse(rawHtml, pageUrl)
        val root = document.selectFirst("#somids") ?: return emptyList()
        val result = mutableListOf<LiveCatalogItemDto>()
        val seen = linkedSetOf<String>()

        for (anchor in root.select("a[href]")) {
            val href = absoluteUrl(pageUrl, anchor.attr("href"))
            val path = bookPath(href) ?: continue
            if (!seen.add(path)) continue
            val cell = anchor.closest("td") ?: anchor.parent() ?: anchor
            val title = cell.select("a[href]")
                .mapNotNull { candidate ->
                    val candidateHref = absoluteUrl(pageUrl, candidate.attr("href"))
                    if (bookPath(candidateHref) != path) null else clean(candidate.text()).takeIf(String::isNotBlank)
                }
                .maxByOrNull { it.length }
                ?: titleFromPath(path)
            val cover = coverFrom(cell, pageUrl)
            val narrators = cell.select("a[href*=/tags/]")
                .map { clean(it.text()) }
                .filter(String::isNotBlank)
                .distinctBy(String::lowercase)
            result += LiveCatalogItemDto(
                key = "$AUDIOBOO_SOURCE:$path",
                source = AUDIOBOO_SOURCE,
                externalId = path,
                externalUrl = href,
                title = title,
                coverUrl = cover,
                narrators = narrators,
                seriesName = seriesName,
                seriesExternalId = seriesExternalId,
                seriesPosition = inferSeriesPosition(title, seriesName),
            )
        }
        return orderSeries(result)
    }

    internal fun orderSeries(items: List<LiveCatalogItemDto>): List<LiveCatalogItemDto> =
        items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<LiveCatalogItemDto>> { it.value.seriesPosition ?: Int.MAX_VALUE }
                    .thenBy { it.index }
            )
            .map { it.value }

    fun parseSeriesCount(rawHtml: String, pageUrl: String): Int {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val node = labelNode(document, "Цикл", "Серия") ?: return 0
        return Regex("\\[(\\d+)]").find(clean(node.text()))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun parseCard(root: Element, pageUrl: String, requireBookMetadata: Boolean): LiveCatalogItemDto? {
        if (requireBookMetadata) {
            val hasMetadata = listOf("Автор", "Исполнитель", "Жанр", "Время")
                .any { labelNode(root, it) != null }
            if (!hasMetadata) return null
        }

        val titleAnchor = root.selectFirst(".card__title a[href], h2.card__title a[href], h2 a[href], .book-title[href]")
            ?: root.select("a[href]").firstOrNull { bookPath(absoluteUrl(pageUrl, it.attr("href"))) != null }
            ?: return null
        val href = absoluteUrl(pageUrl, titleAnchor.attr("href"))
        val path = bookPath(href) ?: return null
        val title = firstNonBlank(
            clean(titleAnchor.text()),
            clean(root.selectFirst("img[alt]")?.attr("alt").orEmpty()),
            titleFromPath(path),
        )
        if (title.isBlank()) return null

        val authorNode = labelNode(root, "Автор")
        val narratorNode = labelNode(root, "Исполнитель", "Читает", "Чтец")
        val genreNode = labelNode(root, "Жанр")
        val seriesNode = labelNode(root, "Цикл", "Серия")
        val seriesAnchor = seriesNode?.selectFirst("a[href]")
        val seriesName = firstNonBlank(
            clean(seriesAnchor?.text().orEmpty()),
            textAfterLabel(seriesNode, "Цикл", "Серия").replace(Regex("\\s*\\[\\d+]\\s*$"), "").trim(),
        )
        val seriesExternalId = seriesAnchor
            ?.let { absoluteUrl(pageUrl, it.attr("href")) }
            ?.let { externalIdFromHref("series", it) }
            .orEmpty()

        return LiveCatalogItemDto(
            key = "$AUDIOBOO_SOURCE:$path",
            source = AUDIOBOO_SOURCE,
            externalId = path,
            externalUrl = href,
            title = title,
            coverUrl = coverFrom(root, pageUrl),
            durationSeconds = durationSeconds(textAfterLabel(labelNode(root, "Время", "Продолжительность"), "Время", "Продолжительность")),
            authors = namesFromLabel(authorNode),
            narrators = namesFromLabel(narratorNode),
            genres = namesFromLabel(genreNode),
            seriesName = seriesName,
            seriesExternalId = seriesExternalId,
            seriesPosition = inferSeriesPosition(title, seriesName),
        )
    }

    private fun peopleFromLabel(node: Element?, kind: String, pageUrl: String): List<PersonDto> {
        if (node == null) return emptyList()
        val result = mutableListOf<PersonDto>()
        val seen = linkedSetOf<String>()
        for (anchor in node.select("a[href]")) {
            val name = clean(anchor.text())
            if (name.isBlank() || !seen.add(name.lowercase())) continue
            val href = absoluteUrl(pageUrl, anchor.attr("href"))
            val externalId = externalIdFromHref(kind, href)
            result += PersonDto(externalId?.let { entityId(kind, it) }.orEmpty(), name)
        }
        return result
    }

    private fun genresFromLabel(node: Element?, pageUrl: String): List<GenreDto> {
        if (node == null) return emptyList()
        val result = mutableListOf<GenreDto>()
        val seen = linkedSetOf<String>()
        for (anchor in node.select("a[href]")) {
            val name = clean(anchor.text())
            if (name.isBlank() || !seen.add(name.lowercase())) continue
            val href = absoluteUrl(pageUrl, anchor.attr("href"))
            val externalId = externalIdFromHref("genre", href)
            result += GenreDto(externalId?.let { entityId("genre", it) }.orEmpty(), name)
        }
        return result
    }

    private fun namesFromLabel(node: Element?): List<String> = node
        ?.select("a[href]")
        ?.map { clean(it.text()) }
        ?.filter(String::isNotBlank)
        ?.distinctBy(String::lowercase)
        .orEmpty()

    private fun labelNode(root: Element, vararg labels: String): Element? {
        val candidates = root.select(".card__list li, .pmovie__header-list li")
            .ifEmpty { root.select("li") }
        return candidates.firstOrNull { node ->
            val text = clean(node.text()).lowercase()
            labels.any { label -> text.contains(label.lowercase() + ":") }
        }
    }

    private fun textAfterLabel(node: Element?, vararg labels: String): String {
        if (node == null) return ""
        val text = clean(node.text())
        val lower = text.lowercase()
        for (label in labels) {
            val marker = label.lowercase() + ":"
            val index = lower.indexOf(marker)
            if (index >= 0) return text.substring(index + marker.length).trim()
        }
        return ""
    }

    private fun metaValue(document: Document, label: String): String {
        val content = firstNonBlank(
            document.selectFirst("meta[name=description]")?.attr("content").orEmpty(),
            document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
        )
        if (content.isBlank()) return ""
        val lower = content.lowercase()
        val marker = label.lowercase() + ":"
        val start = lower.indexOf(marker)
        if (start < 0) return ""
        val valueStart = start + marker.length
        val end = knownMetadataLabels
            .asSequence()
            .filterNot { it.equals(label, ignoreCase = true) }
            .map { next -> lower.indexOf(next.lowercase() + ":", valueStart) }
            .filter { it >= 0 }
            .minOrNull()
            ?: content.length
        return clean(content.substring(valueStart, end))
    }

    private fun description(document: Document): String {
        val full = document.selectFirst(".page__text.full-text, .page__text, .full-text")
        if (full != null) {
            val whole = runCatching { full.wholeText() }.getOrDefault(full.text())
            val marker = "Описание:"
            val index = whole.indexOf(marker, ignoreCase = true)
            if (index >= 0) {
                val value = clean(whole.substring(index + marker.length))
                if (value.isNotBlank()) return value
            }
        }
        return metaValue(document, "Описание")
    }

    private fun inferSeriesPosition(title: String, seriesName: String): Int? {
        if (title.isBlank() || seriesName.isBlank()) return null
        val index = title.indexOf(seriesName, ignoreCase = true)
        if (index < 0) return null
        val tail = title.substring(index + seriesName.length)
        return Regex("^\\s*(\\d{1,3})(?=\\D|$)")
            .find(tail)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun coverFrom(root: Element, pageUrl: String): String {
        val selectors = listOf(
            ".card__img img[data-original]", ".card__img img[data-src]", ".card__img img[src]",
            ".fig1 img[data-src]", ".fig1 img[src]", "img[data-original]", "img[data-src]", "img[src]",
        )
        for (selector in selectors) {
            for (img in root.select(selector)) {
                val raw = img.attr("data-original").takeIf(String::isNotBlank)
                    ?: img.attr("data-src").takeIf(String::isNotBlank)
                    ?: img.attr("src")
                val url = absoluteUrl(pageUrl, raw)
                if (isUsableCover(url)) return url
            }
        }
        return ""
    }

    private fun firstUsableCover(vararg values: String, pageUrl: String): String {
        for (value in values) {
            val url = absoluteUrl(pageUrl, value)
            if (isUsableCover(url)) return url
        }
        return ""
    }

    private fun isUsableCover(value: String): Boolean {
        if (value.isBlank()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".svg") || path.endsWith(".svgz")) return false
        return rejectedCoverMarkers.none(path::contains)
    }

    private fun externalIdFromHref(kind: String, href: String): String? {
        val uri = runCatching { URI(href) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host != "audioboo.org" && host != "www.audioboo.org") return null
        val rawPath = uri.rawPath.orEmpty()
        val result = when (kind) {
            "author" -> rawPath.substringAfter("/xfsearch/avtora/", "").trim('/')
            "narrator" -> rawPath.substringAfter("/tags/", "").trim('/')
            "series" -> rawPath.substringAfter("/xfsearch/cikl/", "").trim('/')
            "genre" -> {
                val clean = rawPath.trim('/')
                clean.takeIf {
                    it.isNotBlank() && '/' !in it && it.lowercase() !in NON_GENRE_ROOTS
                }.orEmpty()
            }
            else -> ""
        }
        return result.takeIf(String::isNotBlank)
    }

    private fun entityId(kind: String, externalId: String): String = "$AUDIOBOO_SOURCE:$kind:$externalId"

    private fun mediaChapters(document: Document, pageUrl: String, bookId: String): List<ChapterDto> {
        val candidates = linkedMapOf<String, String>()
        for (node in document.select("audio[src],audio source[src],source[src]")) {
            val url = normalizeMediaUrl(pageUrl, node.attr("src"))
            if (isMediaUrl(url)) candidates.putIfAbsent(url, clean(node.attr("title")))
        }
        for (anchor in document.select("a[href]")) {
            val url = normalizeMediaUrl(pageUrl, anchor.attr("href"))
            if (isMediaUrl(url)) candidates.putIfAbsent(url, clean(anchor.text()))
        }
        for (script in document.select("script")) {
            val text = script.data().ifBlank { script.html() }
            for (match in fieldMediaRegex.findAll(text)) {
                val url = normalizeMediaUrl(pageUrl, match.groupValues[1])
                if (isMediaUrl(url)) candidates.putIfAbsent(url, "")
            }
            for (match in directMediaRegex.findAll(text)) {
                val url = normalizeMediaUrl(pageUrl, match.value)
                if (isMediaUrl(url)) candidates.putIfAbsent(url, "")
            }
        }
        return candidates.entries.mapIndexed { index, row ->
            ChapterDto(
                id = "$bookId:chapter:${index + 1}",
                position = index,
                title = row.value.ifBlank { "Часть ${index + 1}" },
                durationSeconds = 0,
                streamUrl = row.key,
            )
        }
    }

    private fun normalizeMediaUrl(base: String, raw: String): String {
        val decoded = raw.trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
            // Older Audioboo/Archive.org playlists often keep literal spaces in
            // filenames (for example "00  Очаг.mp3"). java.net.URI rejects such
            // URLs, so encode the spaces before validation/resolution.
            .replace(" ", "%20")
        return absoluteUrl(base, decoded)
    }

    private fun isMediaUrl(value: String): Boolean {
        if (value.isBlank() || !mediaExtensionRegex.containsMatchIn(value)) return false
        return ApiClient.externalHttpUrl(value) != null
    }

    private fun bookPath(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host != "audioboo.org" && host != "www.audioboo.org") return null
        val match = bookPathRegex.find(uri.path.orEmpty()) ?: return null
        return match.groupValues[1]
    }

    private fun titleFromPath(path: String): String {
        val slug = path.substringAfter('/').substringAfter('-', "").substringBeforeLast(".html")
        return slug.replace('-', ' ').replace('_', ' ').trim()
    }

    private fun splitNames(value: String): List<String> = value
        .split(',', ';')
        .map(::clean)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)

    private fun durationSeconds(value: String): Long {
        val text = clean(value).lowercase()
        Regex("(?<!\\d)(\\d{1,3}):(\\d{2}):(\\d{2})(?!\\d)").find(text)?.let { match ->
            val h = match.groupValues[1].toLongOrNull() ?: 0
            val m = match.groupValues[2].toLongOrNull() ?: 0
            val s = match.groupValues[3].toLongOrNull() ?: 0
            return h * 3600 + m * 60 + s
        }
        val hours = Regex("(\\d+)\\s*(?:ч(?:ас(?:а|ов)?)?\\.?)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:мин(?:ут(?:а|ы)?)?\\.?)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
        return hours * 3600 + minutes * 60
    }

    private fun absoluteUrl(base: String, raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun clean(value: String): String = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private val NON_GENRE_ROOTS = setOf(
        "tags", "xfsearch", "page", "user", "users", "templates", "uploads", "engine", "index.php", "anonsi", "addnews.html"
    )
}
