package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextContentsEditsTest {
    private val chapter = TextChapterEntity("book:c0", "book", 0, "Глава 1", 0, 9, false)
    private val fragment = TextFragmentEntity("book:c0:f0", chapter.id, 0, 0, 9, "А😀 текст")

    @Test fun renamingDoesNotChangeChapterOrFragmentIdentityOrText() {
        val edits = TextContentsEdits().rename(chapter.id, "  Новое название  ").validate(listOf(chapter), listOf(fragment))
        assertEquals("Новое название", readingContents(listOf(chapter), edits, listOf(fragment)).single().title)
        assertEquals("Глава 1", chapter.title)
        assertEquals("А😀 текст", fragment.text)
        assertEquals(chapter.id, readingContents(listOf(chapter), edits, listOf(fragment)).single().chapterId)
        assertEquals("Глава 1", readingContents(listOf(chapter), edits.rename(chapter.id, ""), listOf(fragment)).single().title)
    }

    @Test fun markersAreSortedByTextPositionAndRemainInsideTheSameOriginalChapter() {
        val edits = TextContentsEdits().putMarker(fragment.id, 5, "Позже").putMarker(fragment.id, 3, "Раньше")
        edits.validate(listOf(chapter), listOf(fragment))
        val entries = readingContents(listOf(chapter), edits, listOf(fragment))
        assertEquals(listOf("Глава 1", "Раньше", "Позже"), entries.map { it.title })
        assertEquals(listOf(chapter.id, chapter.id, chapter.id), entries.map { it.chapterId })
        assertEquals(listOf(0, 3, 5), entries.map { it.offset })
        assertEquals(3, entries.map { it.key }.distinct().size)
    }

    @Test fun editingAnExistingMarkerReplacesItsTitleWithoutCreatingADuplicate() {
        val edits = TextContentsEdits().putMarker(fragment.id, 3, "Метка").putMarker(fragment.id, 3, "Новое имя")
        assertEquals(1, edits.markers.size)
        assertEquals("Новое имя", edits.markers.single().title)
        assertTrue(edits.removeMarker(fragment.id, 3).markers.isEmpty())
    }

    @Test fun automaticAudioChunksStillCollapseUnlessExplicitlyRenamed() {
        val automatic = chapter.copy(automatic = true)
        assertTrue(readingContents(listOf(automatic)).single().wholeBook)
        val edits = TextContentsEdits().rename(automatic.id, "Моя глава")
        assertEquals("Моя глава", readingContents(listOf(automatic), edits).single().title)
        assertTrue(readingContents(listOf(automatic), edits.rename(automatic.id, "")).single().wholeBook)
    }

    @Test fun foreignChapterAndFragmentAnchorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TextContentsEdits().rename("other:c0", "Название").validate(listOf(chapter), listOf(fragment))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextContentsEdits().putMarker("other:f0", 0, "Метка").validate(listOf(chapter), listOf(fragment))
        }
    }

    @Test fun outOfBoundsAndMidSurrogateAnchorsAreRejected() {
        for (offset in listOf(-1, 2, 9, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            TextContentsEdits().putMarker(fragment.id, offset, "Метка").validate(listOf(chapter), listOf(fragment))
        }
        TextContentsEdits().putMarker(fragment.id, 1, "Начало эмодзи").validate(listOf(chapter), listOf(fragment))
    }

    @Test fun invalidTitlesAndDuplicateArchiveAnchorsAreRejected() {
        for (title in listOf("", " ", "Строка\nстрока", "x".repeat(201))) assertThrows(IllegalArgumentException::class.java) {
            TextContentsEdits().putMarker(fragment.id, 0, title)
        }
        val marker = TextContentsMarker(fragment.id, 3, "Метка")
        assertThrows(IllegalArgumentException::class.java) {
            TextContentsEdits(markers = listOf(marker, marker)).validate(listOf(chapter), listOf(fragment))
        }
    }
}
