package com.example.data.textbook

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Parser offsets use UTF-16 characters in normalizedText, never original-file byte offsets. */
internal data class ParsedTextFragment(val ordinal: Int, val startOffset: Int, val endOffset: Int, val text: String)
internal data class ParsedTextChapter(
    val ordinal: Int, val title: String, val startOffset: Int, val endOffset: Int,
    val automatic: Boolean, val fragments: List<ParsedTextFragment>,
)
internal data class ParsedTxtBook(val normalizedText: String, val encoding: String, val chapters: List<ParsedTextChapter>)

internal object TxtBookParser {
    const val VERSION = 1
    const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    const val MAX_CHARACTERS = 4_000_000
    const val MAX_FRAGMENT_CHARACTERS = 1_000
    private const val AUTOMATIC_CHAPTER_CHARACTERS = 16_000
    private const val ORDINAL_WORDS =
        "первая|вторая|третья|четвёртая|четвертая|пятая|шестая|седьмая|восьмая|девятая|десятая|" +
        "одиннадцатая|двенадцатая|тринадцатая|четырнадцатая|пятнадцатая|шестнадцатая|" +
        "семнадцатая|восемнадцатая|девятнадцатая|двадцатая|тридцатая|сороковая|пятидесятая|" +
        "шестидесятая|семидесятая|восьмидесятая|девяностая|сотая"
    private val heading = Regex(
        "^(?:(?:глава|часть|книга|раздел)\\s+(?:[0-9]+|[ivxlcdm]+|" +
            "(?:(?:двадцать|тридцать|сорок|пятьдесят|шестьдесят|семьдесят|восемьдесят|девяносто)\\s+)?" +
            "(?:$ORDINAL_WORDS))(?:[ .:–—-].*)?|(?:пролог|эпилог|вступление|заключение)(?:[ .:–—-].*)?)$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(bytes: ByteArray): ParsedTxtBook {
        require(bytes.size <= MAX_SOURCE_BYTES) { "Для первого этапа выберите TXT до 8 МБ." }
        val (decoded, encoding) = decode(bytes)
        val text = decoded.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        require(text.isNotBlank()) { "TXT не содержит текста." }
        require(text.length <= MAX_CHARACTERS) { "В книге больше 4 миллионов символов." }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "Файл содержит двоичные данные или неподдерживаемые управляющие символы. Выберите обычный TXT."
        }
        require(text.any { it.isLetterOrDigit() }) { "В файле не найден текст книги." }
        val headings = Regex("(?m)^[^\\n]+").findAll(text).filter {
            val line = it.value.trim()
            line.length <= 120 && heading.matches(line)
        }.map { it.range.first to it.value.trim() }.take(4_001).toList()
        require(headings.size <= 4_000) { "В книге слишком много глав." }
        val chapters = if (headings.isEmpty()) automaticChapters(text) else {
            val starts = buildList {
                if (headings.first().first > 0 && text.substring(0, headings.first().first).isNotBlank()) {
                    add(0 to "Начало книги")
                }
                addAll(headings)
            }
            starts.mapIndexed { index, (start, title) ->
                chapter(text, index, title, start, starts.getOrNull(index + 1)?.first ?: text.length, false)
            }
        }
        return ParsedTxtBook(text, encoding, chapters)
    }

    /** Structured formats provide their own chapter boundaries, independent of TXT heuristics. */
    fun fromSections(sections: List<Pair<String, String>>, encoding: String): ParsedTxtBook {
        val cleaned = sections.map { (title, body) -> title.take(240) to body.replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n').trim() }
            .filter { (_, body) -> body.any { it.isLetterOrDigit() } }
        require(cleaned.isNotEmpty()) { "В книге не найден текст для чтения." }
        require(cleaned.size <= 4_000) { "В книге слишком много глав." }
        require(cleaned.sumOf { it.second.length.toLong() + 2 } <= MAX_CHARACTERS) { "В книге больше 4 миллионов символов." }
        val text = cleaned.joinToString("\n\n") { it.second }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "В тексте есть неподдерживаемые управляющие символы." }
        var offset = 0
        val chapters = cleaned.mapIndexed { index, (title, body) ->
            val end = offset + body.length + if (index == cleaned.lastIndex) 0 else 2
            chapter(text, index, title.ifBlank { "Глава ${index + 1}" }, offset, end, false).also { offset = end }
        }
        return ParsedTxtBook(text, encoding, chapters)
    }

    private fun decode(bytes: ByteArray): Pair<String, String> {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        val charset = when {
            starts(0xff, 0xfe, 0, 0) || starts(0, 0, 0xfe, 0xff) ->
                throw IllegalArgumentException("Пересохраните UTF-32 книгу в UTF-8 или UTF-16.")
            starts(0xff, 0xfe) -> StandardCharsets.UTF_16LE
            starts(0xfe, 0xff) -> StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }
        fun decodeStrict(encoding: java.nio.charset.Charset): String = encoding.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return try {
            decodeStrict(charset) to charset.name()
        } catch (error: java.nio.charset.CharacterCodingException) {
            require(charset == StandardCharsets.UTF_8 && !starts(0xef, 0xbb, 0xbf)) {
                "Повреждённая кодировка TXT. Пересохраните книгу в UTF-8."
            }
            val windows = java.nio.charset.Charset.forName("windows-1251")
            try {
                decodeStrict(windows) to windows.name()
            } catch (invalid: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Не удалось прочитать кодировку TXT. Пересохраните книгу в UTF-8.", invalid)
            }
        }
    }

    private fun automaticChapters(text: String): List<ParsedTextChapter> = buildList {
        var start = 0
        while (start < text.length) {
            val end = boundary(text, start, text.length, AUTOMATIC_CHAPTER_CHARACTERS)
            add(chapter(text, size, "Часть ${size + 1}", start, end, true))
            start = end
        }
    }

    private fun chapter(text: String, ordinal: Int, title: String, start: Int, end: Int, automatic: Boolean): ParsedTextChapter {
        val fragments = buildList {
            var offset = start
            while (offset < end) {
                val next = boundary(text, offset, end, MAX_FRAGMENT_CHARACTERS)
                add(ParsedTextFragment(size, offset, next, text.substring(offset, next)))
                offset = next
            }
        }
        return ParsedTextChapter(ordinal, title, start, end, automatic, fragments)
    }

    private fun boundary(text: String, start: Int, end: Int, limit: Int): Int {
        if (end - start <= limit) return end
        var cut = start + limit
        val minimum = start + limit / 2
        val paragraph = text.lastIndexOf('\n', cut - 1)
        val space = text.lastIndexOf(' ', cut - 1)
        cut = when {
            paragraph >= minimum -> paragraph + 1
            space >= minimum -> space + 1
            else -> cut
        }
        if (text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) cut--
        return cut
    }
}
