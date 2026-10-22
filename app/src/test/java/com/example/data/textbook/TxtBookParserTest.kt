package com.example.data.textbook

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class TxtBookParserTest {
    @Test fun headingsPreambleAndOffsetsPreserveAllText() {
        val input = "\uFEFFНазвание\r\nАвтор\r\n\r\nГлава 1. Начало\r\nПервый абзац.\r\n\r\nГлава вторая\r\nФинал."
        val parsed = TxtBookParser.parse(input.toByteArray())
        assertEquals(listOf("Начало книги", "Глава 1. Начало", "Глава вторая"), parsed.chapters.map { it.title })
        assertEquals(parsed.normalizedText, parsed.chapters.flatMap { it.fragments }.joinToString("") { it.text })
        parsed.chapters.flatMap { it.fragments }.forEach {
            assertEquals(parsed.normalizedText.substring(it.startOffset, it.endOffset), it.text)
        }
        assertTrue(parsed.chapters.none { it.automatic })
    }

    @Test fun unheadedBookHasBoundedPartsAndFragmentsWithoutLostCharacters() {
        val source = List(8_000) { "Длинная строка 😀 без оглавления. " }.joinToString("")
        val parsed = TxtBookParser.parse(source.toByteArray())
        assertTrue(parsed.chapters.size > 1)
        assertTrue(parsed.chapters.all { it.automatic })
        assertTrue(parsed.chapters.all { it.endOffset - it.startOffset <= 16_000 })
        val fragments = parsed.chapters.flatMap { it.fragments }
        assertTrue(fragments.all { it.text.length <= TxtBookParser.MAX_FRAGMENT_CHARACTERS })
        assertTrue(fragments.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
        assertEquals(parsed.normalizedText, fragments.joinToString("") { it.text })
    }

    @Test fun hardBoundaryDoesNotSplitASurrogatePair() {
        val source = "я".repeat(999) + "😀" + "я".repeat(1_001)
        val fragments = TxtBookParser.parse(source.toByteArray()).chapters.single().fragments
        assertEquals(source, fragments.joinToString("") { it.text })
        assertFalse(fragments[0].text.last().isHighSurrogate())
        assertFalse(fragments[1].text.first().isLowSurrogate())
    }

    @Test fun decodesLegacyRussianAndBomMarkedUtf16() {
        val source = "Глава 1\nЁжик читает книгу."
        val windows = TxtBookParser.parse(source.toByteArray(Charset.forName("windows-1251")))
        assertEquals(source, windows.normalizedText)
        assertEquals("windows-1251", windows.encoding.lowercase())
        listOf("UTF-16LE" to byteArrayOf(0xff.toByte(), 0xfe.toByte()), "UTF-16BE" to byteArrayOf(0xfe.toByte(), 0xff.toByte())).forEach { (encoding, bom) ->
            val parsed = TxtBookParser.parse(bom + source.toByteArray(Charset.forName(encoding)))
            assertEquals(source, parsed.normalizedText)
        }
    }

    @Test fun refusesEmptyBinaryOversizedAndMalformedUtf16() {
        listOf(byteArrayOf(), "\n \t".toByteArray(), byteArrayOf(0, 1, 2, 3), byteArrayOf(0x98.toByte()), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { TxtBookParser.parse(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { TxtBookParser.parse(ByteArray(TxtBookParser.MAX_SOURCE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { TxtBookParser.parse("Глава 1\n".repeat(4_001).toByteArray()) }
    }

    @Test fun recognizesLaterWordNumberedChaptersWithoutTreatingNormalSentencesAsHeadings() {
        val source = "Глава одиннадцатая\nКнига о птицах.\nГлава двадцать первая. Встреча\nКонец."
        assertEquals(listOf("Глава одиннадцатая", "Глава двадцать первая. Встреча"),
            TxtBookParser.parse(source.toByteArray()).chapters.map { it.title })
    }

    @Test fun parsingIsDeterministicAndOrdinalsStayContiguous() {
        val source = "Пролог\nНачало.\nГлава 1\n" + "Книга. ".repeat(300)
        val first = TxtBookParser.parse(source.toByteArray())
        assertEquals(first, TxtBookParser.parse(source.toByteArray()))
        assertEquals(first.chapters.indices.toList(), first.chapters.map { it.ordinal })
        first.chapters.forEach { assertEquals(it.fragments.indices.toList(), it.fragments.map { f -> f.ordinal }) }
    }
}
