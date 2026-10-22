package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextReadingBlocksTest {
    @Test fun synthesisBoundariesDoNotBreakParagraphsOrLoseText() {
        val fragments = listOf(
            fragment("a", "c0", "Абзац разрезан "), fragment("b", "c0", "для синтеза.\n\n"),
            fragment("c", "c0", "Следующий абзац."), fragment("d", "c1", "Новая глава."),
        )
        val blocks = readingBlocks(fragments)
        assertEquals(fragments.joinToString("") { it.text }, blocks.joinToString("") { it.text })
        assertEquals("Абзац разрезан для синтеза.\n\n", blocks.first().text)
        assertEquals(listOf("a", "b"), blocks.first().fragmentIds)
        assertEquals(listOf("a", "c", "d"), blocks.map { it.anchorId })
        assertEquals("c1", blocks.last().chapterId)
    }

    @Test fun veryLongParagraphIsBoundedAndAnchorRemainsRepeatable() {
        val fragments = (0 until 40).map { fragment("f$it", "c0", "А".repeat(1000)) }
        val blocks = readingBlocks(fragments)
        assertTrue(blocks.all { it.text.length <= 16_000 })
        assertEquals(40_000, blocks.sumOf { it.text.length })
        val savedAnchor = blocks[1].anchorId
        assertEquals(1, readingBlocks(fragments).indexOfFirst { savedAnchor in it.fragmentIds })
    }

    private fun fragment(id: String, chapter: String, text: String) = TextFragmentEntity(id, chapter, 0, 0, text.length, text)
}
