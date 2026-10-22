package com.example.data.textsource

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal object FantasyWorldsHtmlParser {
    const val ORIGIN = "https://fantasy-worlds.org"
    private val idPattern = "[1-9][0-9]{0,11}"
    private val bookPath = Regex("/lib/id($idPattern)/?")

    fun requireId(id: String): String = id.also { require(it.matches(Regex(idPattern))) { "Некорректный ID книги." } }
    fun bookUrl(id: String) = "$ORIGIN/lib/id${requireId(id)}/"

    /** Canonicalize only this provider's HTTPS origin. No arbitrary web or local URLs. */
    fun safeUrl(raw: String, base: String = "$ORIGIN/"): String? = runCatching {
        val u = URI(base).resolve(raw.trim()).normalize()
        if (u.scheme != "https" || u.host?.lowercase() !in setOf("fantasy-worlds.org", "www.fantasy-worlds.org") ||
            u.userInfo != null || u.port !in setOf(-1, 443) || u.fragment != null) null
        else URI("https://fantasy-worlds.org" + u.rawPath.orEmpty() + (u.rawQuery?.let { "?$it" } ?: "")).toASCIIString()
    }.getOrNull()

    fun bookId(raw: String): String? = safeUrl(raw)?.let { bookPath.matchEntire(URI(it).path)?.groupValues?.get(1) }
    fun isPageUrl(url: String): Boolean {
        val safe = safeUrl(url) ?: return false
        val path = URI(safe).path
        return path.matches(Regex("/lib(?:/[1-9][0-9]*)?/?")) || path == "/search/" ||
            path.matches(Regex("/(?:series|author)/id$idPattern(?:/[1-9][0-9]*)?/?"))
    }
    fun isDownloadUrl(url: String, id: String) = safeUrl(url)?.let {
        URI(it).path == "/lib/id${requireId(id)}/download/" && URI(it).query == null
    } == true
    fun isFileDownloadUrl(url: String, id: String) = safeUrl(url)?.let {
        URI(it).path in listOf("", "epub", "mobi", "txt").map { format -> "/lib/id${requireId(id)}/download/$format" } && URI(it).query == null
    } == true

    fun book(html: String, url: String): RemoteTextBook {
        val id = bookId(url) ?: error("Некорректная ссылка на книгу Fantasy Worlds.")
        val doc = Jsoup.parse(html, url)
        val body = doc.select("[itemscope]").firstOrNull {
            it.attr("itemtype").endsWith("/Book") && it.select("[itemprop=name]").isNotEmpty()
        } ?: error("Карточка книги не найдена: книга недоступна или сайт изменился.")
        val observed = body.selectFirst("a[id^=download_book_]")?.id()?.removePrefix("download_book_")
        require(observed == null || observed == id) { "Сайт вернул другую книгу вместо запрошенной." }
        return parseBook(body, id, url)
    }

    fun page(html: String, url: String): RemoteTextPage {
        require(isPageUrl(url)) { "Недопустимый адрес каталога." }
        val doc = Jsoup.parse(html, url)
        val main = doc.selectFirst(".rightBlock") ?: error("Каталог не найден: сайт изменился или требует проверки браузером.")
        val title = main.selectFirst("h1, .contentTitle")?.text()?.takeIf { it.isNotBlank() } ?: "Fantasy Worlds"
        val authorId = Regex("/author/id($idPattern)(?:/[1-9][0-9]*)?/?").matchEntire(URI(requireNotNull(safeUrl(url))).path)?.groupValues?.get(1)
        val pageAuthor = authorId?.takeIf { title !in listOf("Автор", "Fantasy Worlds") }?.let {
            TextSourceLink(it, title, "$ORIGIN/author/id$it/")
        }
        val books = main.select("[itemscope]").filter { it.attr("itemtype").endsWith("/Book") }
            .mapNotNull { block ->
                val link = block.selectFirst("a[id^=download_book_]")
                val id = link?.id()?.removePrefix("download_book_")?.takeIf { it.matches(Regex(idPattern)) }
                    ?: block.select("a[href]").firstNotNullOfOrNull { bookId(it.attr("href")) }
                if (id == null || block.selectFirst("[itemprop=name]") == null) null else parseBook(block, id, url)
            }.ifEmpty {
                // Author/series use a bibliography rather than full schema.org cards.
                val bibliography = main.clone().also { it.select(".comm_body, form, script, .pagination").remove() }
                val ordered = bibliography.select("li a[href]").filter { bookId(it.attr("href")) != null }
                val links = ordered.ifEmpty { bibliography.select("a[href]").filter { bookId(it.attr("href")) != null } }
                links.mapNotNull { link ->
                    val id = bookId(link.attr("href")) ?: return@mapNotNull null
                    val title = link.text().replace(Regex("^\\s*\\d+(?:[.,]\\d+)?\\s*[-—–]\\s*"), "").trim()
                    if (title.isBlank()) return@mapNotNull null
                    val row = link.closest("li")
                    val number = row?.ownText()?.let { Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*[-—–]").find(it)?.groupValues?.get(1) }
                    val authors = row?.select("a[href]")?.mapNotNull { entity(it, "author", url) }.orEmpty()
                    val cover = bibliography.select("img[src]").firstOrNull { image ->
                        image.closest("a[href]")?.let { bookId(it.attr("href")) } == id
                    }?.let { safeUrl(it.attr("src"), url) }
                    RemoteTextBook(id, title, bookUrl(id), authors, coverUrl = cover, seriesNumber = number)
                }
            }.distinctBy { it.id }.map { book ->
                if (book.authors.isEmpty() && pageAuthor != null) book.copy(authors = listOf(pageAuthor)) else book
            }
        val next = main.selectFirst(".pagination .next-page a[href]")?.let { safeUrl(it.attr("href"), url) }
            ?.takeIf { isPageUrl(it) && it != safeUrl(url) && URI(it).path.substringBeforeLast('/') == URI(safeUrl(url)).path.substringBeforeLast('/') }
        if (books.isEmpty()) {
            val text = main.text().lowercase()
            require(listOf("ничего не найден", "не найдено", "нет результатов", "книги не найдены").any { it in text }) {
                "Книги не найдены в разметке. Сайт изменился или страница недоступна."
            }
        }
        return RemoteTextPage(title, books, next)
    }

    private fun parseBook(body: Element, id: String, base: String): RemoteTextBook {
        val title = body.selectFirst("[itemprop=name]")?.text()?.trim().orEmpty()
        require(title.isNotEmpty()) { "У книги отсутствует название." }
        val authors = body.select("[itemprop=author][href]").mapNotNull { entity(it, "author", base) }.distinctBy { it.id }
        val series = body.select("a[href]").firstNotNullOfOrNull { entity(it, "series", base) }
        val image = body.selectFirst("img[itemprop=image], img[src*=/img/preview/]")
        val download = body.selectFirst("a#download_book_$id[href]")?.let { safeUrl(it.attr("href"), base) }
            ?.takeIf { isDownloadUrl(it, id) }
        val text = body.selectFirst("[itemprop=name]")?.parent()?.text().orEmpty()
        // The supplied site's selector script appends the option value, except FB2 (empty suffix).
        val downloads = if (download == null) emptyList() else body.select("[id=select_type_$id] option[value]")
            .mapNotNull { option ->
                val code = option.attr("value")
                val extension = when (code) { "fb2" -> "fb2.zip"; "epub", "mobi", "txt" -> code; else -> return@mapNotNull null }
                RemoteTextDownload(code, option.text().trim().ifBlank { extension },
                    "$ORIGIN/lib/id$id/download/" + if (code == "fb2") "" else code, extension)
            }.distinctBy { it.code }.ifEmpty {
                listOf(RemoteTextDownload("fb2", "FB2 ZIP", download, "fb2.zip"))
            }
        return RemoteTextBook(id, title, bookUrl(id), authors,
            image?.let { safeUrl(it.attr("src"), base) }, body.selectFirst("[itemprop=description]")?.text().orEmpty(), series,
            Regex("Номер книги в серии:\\s*(\\d+(?:[.,]\\d+)?)").find(text)?.groupValues?.get(1),
            Regex("Год:\\s*(\\d{4})").find(text)?.groupValues?.get(1), download,
            downloads.map { it.code }, downloads)
    }
    private fun entity(link: Element, kind: String, base: String): TextSourceLink? {
        val url = safeUrl(link.attr("href"), base) ?: return null
        val id = Regex("/$kind/id($idPattern)/?").matchEntire(URI(url).path)?.groupValues?.get(1) ?: return null
        return TextSourceLink(id, link.text().trim(), url)
    }
}
