package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextHighlightRangeTest {
    private val fragments = listOf(
        TextFragmentEntity("a", "chapter", 0, 0, 3, "Один "),
        TextFragmentEntity("b", "chapter", 1, 3, 6, "два\n"),
    ).associateBy { it.id }
    private val block = TextReadingBlock(listOf("a", "b"), "chapter", "Один два\n")
    @Test fun highlightsOnlyActualFragmentWithinMergedParagraph() {
        assertEquals(0 until 5, textHighlightRange(block, fragments, "a"))
        assertEquals(5 until 8, textHighlightRange(block, fragments, "b"))
    }
    @Test fun noFragmentOrDifferentChapterNeverHighlightsWholeParagraph() {
        assertNull(textHighlightRange(block, fragments, null))
        assertNull(textHighlightRange(block, fragments, "next-chapter"))
    }
}
