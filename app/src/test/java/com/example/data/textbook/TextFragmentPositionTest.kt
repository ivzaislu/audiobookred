package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextFragmentPositionTest {
    private val first = TextFragmentEntity("book:c1:f0", "book:c1", 0, 0, 4, "Один")
    private val second = TextFragmentEntity("book:c1:f1", "book:c1", 1, 4, 10, " второй")
    private val fragments = listOf(first, second).associateBy { it.id }
    private val block = TextReadingBlock(listOf(first.id, second.id), "book:c1", first.text + second.text)

    @Test fun selectedOffsetAddressesActualFragmentInsideMergedParagraph() {
        assertEquals(TextFragmentPosition(first.id, 0.0), textPositionAtOffset(block, fragments, 0))
        assertEquals(TextFragmentPosition(second.id, 0.0), textPositionAtOffset(block, fragments, first.text.length))
        assertEquals(TextFragmentPosition(second.id, 3.0 / second.text.length),
            textPositionAtOffset(block, fragments, first.text.length + 3))
    }

    @Test fun offsetIsClampedAndCannotJumpToDifferentChapterOrBookmark() {
        assertEquals(TextFragmentPosition(first.id, 0.0), textPositionAtOffset(block, fragments, -5))
        assertEquals(TextFragmentPosition(second.id, 1.0), textPositionAtOffset(block, fragments, 999))
        assertNull(textPositionAtOffset(block, emptyMap(), 0))
    }
    @Test fun selectionStartsAtEarlierBoundaryEvenWhenHandlesAreReversed() {
        val expected = TextFragmentPosition(second.id, 1.0 / second.text.length)
        assertEquals(expected, textSelectionPosition(block, fragments, 5, 9))
        assertEquals(expected, textSelectionPosition(block, fragments, 9, 5))
        assertEquals(TextFragmentPosition(first.id, 0.0), textSelectionPosition(block, fragments, 0, 8))
    }

    @Test fun collapsedAndClampedEmptySelectionUsesVisiblePositionInstead() {
        assertNull(textSelectionPosition(block, fragments, 4, 4))
        assertNull(textSelectionPosition(block, fragments, 900, 999))
        assertEquals(TextFragmentPosition(first.id, 0.0), textSelectionPosition(block, fragments, -5, 5))
    }

}
