package com.example.data.textbook

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EbookImportParserTest {
    @Test fun calibreSceneSeparatorsAreNotChaptersAndNestedContentsIsNotProse() {
        val body = """<div><h1>Глава 1</h1><div class="paragraph">Первая сцена.</div>
            <h5>* * *</h5><div class="paragraph">Вторая сцена.</div><h5>* * *</h5><div class="paragraph">Третья сцена.</div></div>
            <ul><li><a href="#author">Автор</a><ul><li></li><li><a href="second.xhtml#one">Пролог</a></li>
            <li><a href="second.xhtml#two">Глава 2</a></li><li><a href="second.xhtml#three">Глава 3</a></li></ul></li></ul>"""
        val book = EbookImportParser.parse(epub(firstBody = body), "calibre.epub.zip")
        assertEquals(listOf("Глава 1", "Вторая глава"), book.text.chapters.map { it.title })
        assertEquals(2, Regex("\\* \\* \\*").findAll(book.text.normalizedText).count())
        assertTrue(book.text.normalizedText.contains("Вторая сцена."))
        assertTrue(book.text.normalizedText.contains("Третья сцена."))
        assertFalse(book.text.normalizedText.contains("Пролог"))
        assertFalse(book.text.normalizedText.contains("Глава 3"))
    }

    @Test fun epubAnnotationRemainsReadableWhileUnmarkedNavigationIsExcluded() {
        val body = """<h1>Annotation</h1><p>Частный пилот мог пред<em>ставить</em> себе полёт.<br/>Вторая строка.</p>
            <p><a href="second.xhtml#author">Валентин Холмогоров</a><a href="second.xhtml#one">Глава 1</a><a href="second.xhtml#two">Глава 2</a></p>"""
        val book = EbookImportParser.parse(epub(firstBody = body), "book.epub")
        assertEquals("Аннотация", book.text.chapters.first().title)
        assertTrue(book.text.normalizedText.contains("представить себе полёт.\nВторая строка."))
        assertFalse(book.text.normalizedText.contains("Валентин Холмогоров"))
        // RuVoice receives normal imported text; the retired Silero phonetic
        // frontend must not be needed to validate EPUB annotation parsing.
        val original = book.text.chapters.first().fragments.first().text
        val narration = com.example.data.narration.NarrationInput.prepare(original)
        assertEquals(original.trim(), narration)
        assertTrue(narration.contains("Частный пилот"))
    }

    @Test fun epubSemanticAndGuideNavigationDoNotRemoveSurroundingStory() {
        val body = """<h1>Пролог</h1><div role="doc-toc"><a href="second.xhtml">Список разделов</a></div>
            <div id="toc"><p>Служебное оглавление</p></div><p>Начало повести.</p>
            <p>Смотри <a href="second.xhtml#one">Глава 1</a>, <a href="second.xhtml#two">Глава 2</a> и <a href="second.xhtml#three">Глава 3</a>.</p>"""
        val book = EbookImportParser.parse(epub(firstBody = body, guide = """<reference type="toc" href="first.xhtml#toc"/>"""), "book.epub")
        assertFalse(book.text.normalizedText.contains("Список разделов"))
        assertFalse(book.text.normalizedText.contains("Служебное оглавление"))
        assertTrue(book.text.normalizedText.contains("Начало повести."))
        assertTrue(book.text.normalizedText.contains("Смотри Глава 1, Глава 2 и Глава 3."))
    }

    @Test fun epubGuideCanIdentifySeparateLegacyContentsDocument() {
        val book = EbookImportParser.parse(epub(firstBody = """<h1>Contents</h1><p><a href="second.xhtml">Путь героя</a></p>""",
            guide = """<reference type="toc" href="first.xhtml"/>"""), "book.epub")
        assertFalse(book.text.normalizedText.contains("Contents"))
        assertFalse(book.text.normalizedText.contains("Путь героя"))
        assertTrue(book.text.normalizedText.contains("Второй текст"))
    }

    @Test fun fb2MetadataSectionsCoverAndNotes() {
        val book = EbookImportParser.parse(fb2().toByteArray(), "broken-name.fb2")
        assertEquals("Настоящее название", book.title)
        assertEquals("Иван Петров", book.author)
        assertEquals(listOf("Начало", "Вторая"), book.text.chapters.map { it.title })
        assertTrue(book.text.normalizedText.contains("Первый абзац."))
        assertFalse(book.text.normalizedText.contains("Скрытая сноска"))
        assertFalse(book.text.normalizedText.contains("999"))
        assertNotNull(book.cover)
        assertEquals(book.text.normalizedText, book.text.chapters.flatMap { it.fragments }.joinToString("") { it.text })
    }

    @Test fun epubFollowsSpineInsteadOfZipOrderAndFindsCover() {
        val book = EbookImportParser.parse(epub(), "archive.epub")
        assertEquals("Название EPUB", book.title)
        assertEquals("Автор EPUB", book.author)
        assertTrue(book.text.normalizedText.indexOf("Первый текст") < book.text.normalizedText.indexOf("Второй текст"))
        assertFalse(book.text.normalizedText.contains("Нельзя выполнять"))
        assertNotNull(book.cover)
        assertEquals("EPUB", book.format)
    }

    @Test fun archiveRequiresChoiceAndUsesAdjacentCover() {
        val archive = zip("a/book.fb2" to fb2().toByteArray(), "b/book.txt" to "Другая книга".toByteArray(), "b/cover.png" to png())
        val choice = assertThrows(EbookSelectionRequired::class.java) { EbookImportParser.parse(archive, "books.zip") }
        assertEquals(listOf("a/book.fb2", "b/book.txt"), choice.entries)
        val selected = EbookImportParser.parse(archive, "books.zip", "b/book.txt")
        assertEquals("Другая книга", selected.text.normalizedText)
        assertNotNull(selected.cover)
        assertEquals("b/book.txt", selected.entry)
    }

    @Test fun fb2ZipAndMislabeledFb2UseContent() {
        assertEquals("FB2", EbookImportParser.parse(zip("Книга.fb2" to fb2().toByteArray()), "book.fb2.zip").format)
        assertEquals("FB2", EbookImportParser.parse(fb2().toByteArray(), "book.txt").format)
        assertEquals("EPUB", EbookImportParser.parse(epub(), "book.zip").format)
    }

    @Test fun archivesCannotEscapeReadExternalResourcesOrGrowWithoutBound() {
        assertThrows(IllegalArgumentException::class.java) { EbookImportParser.parse(zip("../book.txt" to "Текст".toByteArray()), "bad.zip") }
        assertThrows(IllegalArgumentException::class.java) { EbookImportParser.parse(zip("large.txt" to ByteArray(EbookImportParser.MAX_DOCUMENT_BYTES + 1)), "bomb.zip") }
        assertThrows(IllegalArgumentException::class.java) { EbookImportParser.parse(epub("https://example.com/chapter.xhtml"), "external.epub") }
    }

    @Test fun badCoverDoesNotDiscardBookAndFragmentsStayBounded() {
        val raw = fb2().replace("<binary id=\"cover\" content-type=\"image/png\">$PNG</binary>", "")
        assertNull(EbookImportParser.parse(raw.toByteArray(), "book.fb2").cover)
        val parsed = TxtBookParser.fromSections(listOf("Глава" to "Текст книги. ".repeat(300)), "FB2")
        assertTrue(parsed.chapters.single().fragments.all { it.text.length <= TxtBookParser.MAX_FRAGMENT_CHARACTERS })
        assertEquals(parsed.normalizedText, parsed.chapters.flatMap { it.fragments }.joinToString("") { it.text })
    }

    private fun fb2() = """<?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <description><title-info><author><first-name>Иван</first-name><last-name>Петров</last-name></author>
        <book-title>Настоящее название</book-title><coverpage><image l:href="#cover"/></coverpage></title-info></description>
        <body><section><title><p>Начало</p></title><p>Первый абзац.<a type="note" l:href="#note">999</a></p></section>
        <section><title><p>Вторая</p></title><p>Второй абзац.</p></section></body>
        <body name="notes"><section id="note"><p>Скрытая сноска</p></section></body>
        <binary id="cover" content-type="image/png">$PNG</binary></FictionBook>""".trimIndent()

    private fun epub(firstHref: String = "first.xhtml",
        firstBody: String = "<h1>Первая глава</h1><p>Первый текст</p><script>Нельзя выполнять</script>",
        guide: String = "",
    ) = zip(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""".toByteArray(),
        "OPS/second.xhtml" to "<html><body><h1>Вторая глава</h1><p>Второй текст</p></body></html>".toByteArray(),
        "OPS/package.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Название EPUB</dc:title><dc:creator>Автор EPUB</dc:creator></metadata>
            <manifest><item id="second" href="second.xhtml" media-type="application/xhtml+xml"/><item id="first" href="$firstHref" media-type="application/xhtml+xml"/>
            <item id="image" href="cover.png" media-type="image/png" properties="cover-image"/></manifest>
            <spine><itemref idref="first"/><itemref idref="second"/></spine><guide>$guide</guide></package>""".toByteArray(),
        "OPS/first.xhtml" to "<html><body>$firstBody</body></html>".toByteArray(),
        "OPS/cover.png" to png(),
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        out.toByteArray()
    }
    // Test-only decoding; production supports Android 24 without java.util.Base64 (API 26).
    private fun png() = java.util.Base64.getDecoder().decode(PNG)
    companion object { private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8v8AAAAASUVORK5CYII=" }
}
