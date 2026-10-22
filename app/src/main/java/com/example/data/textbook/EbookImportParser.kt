package com.example.data.textbook

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import com.example.data.text.RussianBookSectionNames
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class EbookCover(val bytes: ByteArray)
internal data class ImportedEbook(
    val text: ParsedTxtBook, val title: String, val author: String = "", val format: String,
    val cover: EbookCover? = null, val entry: String? = null,
)
internal class EbookSelectionRequired(val entries: List<String>) : IllegalArgumentException("Выберите книгу в архиве.")

/** Local-only readers: XML/HTML is parsed as data, never displayed as executable WebView content. */
internal object EbookImportParser {
    const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
    const val MAX_COVER_BYTES = 4 * 1024 * 1024
    private const val MAX_UNPACKED = 64L * 1024 * 1024
    private val bookExtensions = setOf("txt", "fb2", "epub")
    private val inlineWhitespace = Regex("[\\s\\u00a0]+")
    private val textBlockTags = setOf("p", "div", "section", "title", "subtitle", "li", "ul", "ol", "dt", "dd", "tr", "blockquote", "pre", "v", "stanza", "h1", "h2", "h3", "h4", "h5", "h6")

    fun parse(bytes: ByteArray, name: String, selection: String? = null): ImportedEbook {
        require(bytes.size <= MAX_SOURCE_BYTES) { "Выберите книгу или архив до 32 МБ." }
        return try { read(bytes, name, selection, 0) }
        catch (badZip: java.util.zip.ZipException) { throw IllegalArgumentException("Архив повреждён, зашифрован или использует неподдерживаемое сжатие.", badZip) }
    }

    private fun read(bytes: ByteArray, name: String, selection: String?, depth: Int): ImportedEbook {
        require(depth <= 2) { "Слишком много вложенных архивов. Выберите сам файл книги." }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))) {
            val archive = unpack(bytes)
            if (archive.containsKey("META-INF/container.xml")) return epub(archive)
            val candidates = archive.keys.filter { path ->
                !path.startsWith("__MACOSX/") && !path.substringAfterLast('/').startsWith('.') &&
                    (extension(path) in bookExtensions || path.endsWith(".fb2.zip", true))
            }.sorted()
            require(candidates.isNotEmpty()) { "В архиве нет TXT, FB2 или EPUB." }
            if (selection == null && candidates.size > 1) {
                require(depth == 0) { "Во вложенном архиве несколько книг. Выберите этот архив отдельно." }
                throw EbookSelectionRequired(candidates)
            }
            val selected = selection ?: candidates.single()
            require(selected in candidates) { "Выбранная книга больше не найдена в архиве." }
            val parsed = read(archive.getValue(selected), selected.substringAfterLast('/'), null, depth + 1)
            val parent = selected.substringBeforeLast('/', "")
            val stem = selected.substringAfterLast('/').substringBeforeLast('.').removeSuffix(".fb2")
            val siblings = candidates.count { it.substringBeforeLast('/', "") == parent }
            val adjacentCover = archive.entries.firstOrNull { (path, data) ->
                path.substringBeforeLast('/', "") == parent && isRaster(data) &&
                    (path.substringAfterLast('/').substringBeforeLast('.').equals(stem, true) ||
                        (siblings == 1 && path.substringAfterLast('/').substringBeforeLast('.').lowercase() in setOf("cover", "обложка")))
            }?.value?.let(::EbookCover)
            return parsed.copy(cover = parsed.cover ?: adjacentCover, entry = selected)
        }
        require(selection == null) { "Выбранный файл больше не является архивом." }
        val prefix = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1).replace("\u0000", "")
        if (Regex("<(?:[A-Za-z0-9_-]+:)?FictionBook(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(prefix)) return fb2(bytes)
        return when (extension(name)) {
            "fb2" -> fb2(bytes)
            "epub", "zip" -> throw IllegalArgumentException("Архив повреждён или зашифрован.")
            "txt", "" -> ImportedEbook(TxtBookParser.parse(bytes), name.substringBeforeLast('.', name), format = "TXT")
            else -> throw IllegalArgumentException("Поддерживаются TXT, FB2, EPUB и ZIP с книгами.")
        }
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        fun decode(charset: Charset): Map<String, ByteArray> {
            val files = linkedMapOf<String, ByteArray>()
            var count = 0
            var total = 0L
            ZipInputStream(bytes.inputStream(), charset).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 5_000) { "В архиве слишком много файлов." }
                    val path = safePath(entry.name)
                    require(path !in files) { "В архиве повторяется путь: $path" }
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_UNPACKED && output.size().toLong() + n <= MAX_DOCUMENT_BYTES) {
                            "Архив слишком большой после распаковки (до 64 МБ, один файл — до 16 МБ)."
                        }
                        output.write(buffer, 0, n)
                    }
                    files[path] = output.toByteArray()
                    zip.closeEntry()
                }
            }
            require(files.isNotEmpty()) { "Архив пуст." }
            return files
        }
        return try { decode(Charsets.UTF_8) }
        catch (invalid: IllegalArgumentException) {
            // Retry only malformed ZIP names, never budget/path failures.
            if (invalid.message?.contains("malformed", true) == true || invalid.message?.contains("MALFORMED") == true)
                decode(Charset.forName("CP866")) else throw invalid
        }
    }

    private fun safePath(value: String): String {
        val path = value.replace('\\', '/').removeSuffix("/")
        require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && path.split('/').none { it in setOf("", ".", "..") || ':' in it || '\u0000' in it }) {
            "В архиве есть небезопасный путь." }
        return path
    }

    private fun resolve(base: String, href: String): String {
        val uri = URI(href.trim().replace(" ", "%20"))
        require(!uri.isAbsolute && uri.authority == null && !href.startsWith('/') && '\\' !in href) { "В книге есть внешний или небезопасный путь." }
        val resolved = URI("/$base").resolve(uri).normalize()
        return safePath(resolved.path.removePrefix("/"))
    }

    private fun xml(bytes: ByteArray): Element {
        require(bytes.size <= MAX_DOCUMENT_BYTES && bytes.count { it == '<'.code.toByte() } <= 200_000) { "Слишком сложный документ книги." }
        return Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
    }
    private fun Element.local() = tagName().substringAfter(':').lowercase()
    private fun Element.desc(name: String) = getAllElements().filter { it.local() == name }
    private fun Element.child(name: String) = children().firstOrNull { it.local() == name }
    private fun Element.attrLocal(name: String) = attributes().asList().firstOrNull { it.key.substringAfter(':') == name }?.value.orEmpty()

    private fun fb2(bytes: ByteArray): ImportedEbook {
        val document = xml(bytes)
        require(document.children().any { it.local() == "fictionbook" }) { "Это не книга FB2." }
        val info = document.desc("description").firstOrNull()?.child("title-info")
        val title = info?.child("book-title")?.text().orEmpty()
        val authors = info?.children()?.filter { it.local() == "author" }?.map { author ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { author.child(it)?.text()?.takeIf(String::isNotBlank) }
                .joinToString(" ").ifBlank { author.child("nickname")?.text().orEmpty() }
        }.orEmpty().filter(String::isNotBlank).joinToString(", ")
        val body = document.desc("body").firstOrNull { it.attr("name").lowercase() !in setOf("notes", "comments") }
            ?: error("В FB2 не найден основной текст.")
        val sections = mutableListOf<Pair<String, String>>()
        fun walk(container: Element, fallback: String, depth: Int = 0) {
            require(depth <= 128) { "Слишком глубокая вложенность разделов книги." }
            var parts = mutableListOf<String>()
            val heading = container.child("title")?.text()?.ifBlank { null } ?: fallback
            fun flush() { if (parts.isNotEmpty()) { sections += heading to parts.joinToString("\n\n"); parts = mutableListOf() } }
            for (node in container.children()) {
                if (node.local() == "section") { flush(); walk(node, "Глава ${sections.size + 1}", depth + 1) }
                else blockText(node).takeIf(String::isNotBlank)?.let(parts::add)
            }
            flush()
        }
        walk(body, "Начало книги")
        val coverId = info?.child("coverpage")?.desc("image")?.firstOrNull()?.attrLocal("href")?.takeIf { it.startsWith('#') }?.drop(1)
        val binary = coverId?.let { id -> document.desc("binary").firstOrNull { it.id() == id } }
        val cover = binary?.takeIf { it.attr("content-type").lowercase() in setOf("image/jpeg", "image/jpg", "image/png") }
            ?.let { decodeCover(it.wholeText()) }?.takeIf(::isRaster)?.let(::EbookCover)
        return ImportedEbook(TxtBookParser.fromSections(sections, "FB2"), title, authors, "FB2", cover)
    }

    private fun blockText(element: Element): String {
        if (element.local() in setOf("image", "binary", "script", "style", "head", "nav")) return ""
        val copy = element.clone()
        copy.getAllElements().filter { node ->
            node.local() in setOf("image", "img", "script", "style", "nav") ||
                (node.local() == "a" && node.attrLocal("type").split(' ').any { it in setOf("note", "noteref") })
        }.forEach { it.remove() }
        // XML parsing does not supply HTML layout. Preserve explicit block/br boundaries,
        // while inline markup (e.g. <em> inside a word) must remain continuous.
        val result = StringBuilder()
        fun line() {
            while (result.isNotEmpty() && result.last() == ' ') result.deleteCharAt(result.lastIndex)
            if (result.isNotEmpty() && result.last() != '\n') result.append('\n')
        }
        fun append(node: Node, depth: Int) {
            require(depth <= 128) { "Слишком глубокая вложенность текста книги." }
            when (node) {
                is TextNode -> {
                    val value = node.wholeText.replace(inlineWhitespace, " ")
                    if (value.isNotBlank() || (result.isNotEmpty() && result.last() != '\n')) result.append(value)
                }
                is Element -> {
                    val tag = node.local()
                    if (tag == "br" || tag == "empty-line") { result.append('\n'); return }
                    if (tag in textBlockTags) line()
                    node.childNodes().forEach { append(it, depth + 1) }
                    if (tag in textBlockTags) line()
                }
            }
        }
        append(copy, 0)
        return result.toString().lines().joinToString("\n") { it.trim() }.trim()
    }

    private fun Element.isEpubNavigation(): Boolean = local() == "nav" ||
        attrLocal("type").lowercase().split(Regex("\\s+")).any { it in setOf("toc", "landmarks", "page-list") } ||
        attr("role").lowercase().split(Regex("\\s+")).any { it in setOf("doc-toc", "doc-pagelist", "navigation") }

    private fun isUnmarkedContents(node: Element, base: String, spinePaths: Set<String>): Boolean {
        if (node.local() !in setOf("p", "div", "section", "ul", "ol")) return false
        // Do not repeatedly clone whole chapters or remove a wrapper that also contains prose.
        if (node.children().any { it.local() in setOf("p", "div", "section", "ul", "ol") }) return false
        val links = node.desc("a")
        if (links.size < 3 || links.map { it.attr("href") }.distinct().size < 2) return false
        if (links.any { runCatching { resolve(base, it.attr("href")) in spinePaths }.getOrDefault(false).not() }) return false
        val chapterLabel = Regex("(?iu)^(?:(?:глава|часть|раздел|книга|chapter|part)\\s+.+|пролог|эпилог|prologue|epilogue)$")
        if (links.count { chapterLabel.matches(it.text().trim()) } < 2) return false
        val remaining = node.clone()
        remaining.desc("a").forEach { it.remove() }
        return remaining.text().none(Char::isLetterOrDigit)
    }

    private fun epub(files: Map<String, ByteArray>): ImportedEbook {
        val container = xml(files.getValue("META-INF/container.xml"))
        val packagePath = resolve("", container.desc("rootfile").firstOrNull()?.attr("full-path") ?: error("В EPUB нет описания книги."))
        val opf = xml(files[packagePath] ?: error("В EPUB отсутствует OPF."))
        val metadata = opf.desc("metadata").firstOrNull() ?: error("В EPUB нет метаданных.")
        val title = metadata.desc("title").firstOrNull()?.text().orEmpty()
        val author = metadata.desc("creator").map { it.text() }.filter(String::isNotBlank).joinToString(", ")
        val manifest = opf.desc("manifest").firstOrNull()?.children()?.filter { it.local() == "item" }?.associateBy { it.id() }
            ?: error("В EPUB отсутствует список файлов.")
        val spine = opf.desc("spine").firstOrNull()?.children()?.filter { it.local() == "itemref" && it.attr("linear") != "no" }.orEmpty()
        require(spine.isNotEmpty()) { "В EPUB не задан порядок чтения." }
        val spinePaths = spine.map { reference ->
            val item = manifest[reference.attr("idref")] ?: error("В EPUB отсутствует глава из порядка чтения.")
            resolve(packagePath, item.attr("href"))
        }.toSet()
        val guideContents = opf.desc("reference").filter { it.attr("type").equals("toc", true) }.mapNotNull {
            runCatching { resolve(packagePath, it.attr("href")) to URI(it.attr("href").replace(" ", "%20")).fragment }.getOrNull()
        }
        val encrypted = files["META-INF/encryption.xml"]?.let(::xml)?.desc("cipherreference")?.mapNotNull {
            runCatching { val uri = URI(it.attr("URI")); if (uri.isAbsolute || uri.authority != null) null else safePath(uri.path.removePrefix("/")) }.getOrNull()
        }.orEmpty().toSet()
        val labels = linkedMapOf<String, String>()
        for (item in manifest.values.filter { "nav" in it.attr("properties").split(' ') || it.attr("media-type") == "application/x-dtbncx+xml" }) {
            val path = resolve(packagePath, item.attr("href"))
            val nav = files[path]?.let(::xml) ?: continue
            nav.desc("a").forEach { a -> runCatching { resolve(path, a.attr("href")) }.getOrNull()?.let { labels.putIfAbsent(it, a.text()) } }
            nav.desc("navpoint").forEach { point ->
                point.child("content")?.attr("src")?.let { href -> runCatching { resolve(path, href) }.getOrNull()?.let { labels.putIfAbsent(it, point.child("navlabel")?.text().orEmpty()) } }
            }
        }
        val sections = mutableListOf<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        for (reference in spine) {
            val item = manifest[reference.attr("idref")] ?: error("В EPUB отсутствует глава из порядка чтения.")
            val path = resolve(packagePath, item.attr("href"))
            require(path !in encrypted) { "Этот EPUB содержит зашифрованный текст. Нужна книга без DRM." }
            if (!visited.add(path)) continue
            require(item.attr("media-type") in setOf("application/xhtml+xml", "text/html")) { "В EPUB неподдерживаемый формат главы." }
            val html = xml(files[path] ?: error("В EPUB отсутствует файл главы."))
            val body = html.desc("body").firstOrNull() ?: continue
            // EPUB 2 may use a guide target instead of the EPUB 3 <nav> element.
            val targets = guideContents.filter { it.first == path }
            if (targets.any { it.second == null }) {
                val remaining = body.clone()
                remaining.getAllElements().filter { it.local() in setOf("a", "img", "svg", "script", "style") || it.isEpubNavigation() }.forEach { it.remove() }
                val rest = remaining.text().trim().removeSuffix(":").trim()
                if (rest.isBlank() || rest.equals("Содержание", true) || rest.equals("Оглавление", true) ||
                    RussianBookSectionNames.russian(rest) == "Содержание") continue
            }
            val targetIds = targets.mapNotNull { it.second }.toSet()
            body.getAllElements().filter { it.isEpubNavigation() ||
                (it.id().isNotBlank() && it.id() in targetIds) || isUnmarkedContents(it, path, spinePaths)
            }.toList().forEach { if (it === body) body.empty() else it.remove() }
            var heading = labels[path]?.takeIf(String::isNotBlank) ?: "Глава ${sections.size + 1}"
            var blocks = mutableListOf<String>()
            fun flush() { if (blocks.isNotEmpty()) { sections += (RussianBookSectionNames.russian(heading) ?: heading) to blocks.joinToString("\n\n"); blocks = mutableListOf() } }
            fun walk(node: Element, depth: Int = 0) {
                require(depth <= 128) { "Слишком глубокая вложенность разметки главы." }
                if (node.local() == "aside" && node.attrLocal("type").split(' ').any { it in setOf("footnote", "endnote") }) return
                when (node.local()) {
                    "script", "style", "nav", "head", "img", "svg" -> return
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val text = blockText(node)
                        if (text.any(Char::isLetterOrDigit)) { flush(); heading = text; blocks += text }
                        else if (text.isNotBlank()) blocks += text // Asterisms are scene breaks, not chapters.
                    }
                    "p", "li", "dt", "dd", "pre", "tr" -> blockText(node).takeIf(String::isNotBlank)?.let { blocks += it }
                    else -> {
                        if (node.children().isEmpty()) blockText(node).takeIf(String::isNotBlank)?.let { blocks += it }
                        else node.childNodes().forEach { child ->
                            if (child is Element) walk(child, depth + 1)
                            else if (child is org.jsoup.nodes.TextNode) child.text().trim().takeIf(String::isNotBlank)?.let { blocks += it }
                        }
                    }
                }
            }
            walk(body); flush()
        }
        val coverId = metadata.desc("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverItem = manifest.values.firstOrNull { "cover-image" in it.attr("properties").split(' ') } ?: manifest[coverId]
            ?: manifest.values.firstOrNull { it.id().contains("cover", true) && it.attr("media-type").startsWith("image/") }
        val guideCover = opf.desc("reference").firstOrNull { it.attr("type") == "cover" }?.attr("href")
        val coverPath = (coverItem?.attr("href") ?: guideCover)?.let { runCatching { resolve(packagePath, it) }.getOrNull() }
        val cover = coverPath?.let { path ->
            val data = files[path] ?: return@let null
            if (isRaster(data)) data else runCatching {
                val wrapper = xml(data)
                val image = wrapper.desc("img").firstOrNull()?.attr("src")
                    ?: wrapper.desc("image").firstOrNull()?.attrLocal("href")
                image?.let { files[resolve(path, it)] }?.takeIf(::isRaster)
            }.getOrNull()
        }?.let(::EbookCover)
        return ImportedEbook(TxtBookParser.fromSections(sections, "EPUB"), title, author, "EPUB", cover)
    }

    private fun decodeCover(raw: String): ByteArray? = runCatching {
        require(raw.length <= (MAX_COVER_BYTES * 2))
        val encoded = raw.filterNot(Char::isWhitespace)
        require(encoded.length % 4 == 0 && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val size = encoded.length / 4 * 3 - encoded.takeLast(2).count { it == '=' }
        require(size in 1..MAX_COVER_BYTES)
        val result = ByteArray(size)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var output = 0
        for (offset in encoded.indices step 4) {
            var bits = 0
            for (i in 0..3) bits = (bits shl 6) or if (encoded[offset + i] == '=') 0 else alphabet.indexOf(encoded[offset + i])
            for (shift in listOf(16, 8, 0)) if (output < size) result[output++] = (bits shr shift).toByte()
        }
        result
    }.getOrNull()

    private fun isRaster(data: ByteArray): Boolean = data.size in 8..MAX_COVER_BYTES &&
        ((data[0] == 0xff.toByte() && data[1] == 0xd8.toByte()) ||
            data.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)))
}
