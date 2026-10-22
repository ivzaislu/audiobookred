package com.example.data.narration

import org.junit.Assert.*
import org.junit.Test

class RuVoiceTextChunksTest {
    @Test fun shortSentenceRemainsOneNaturalRequest() {
        val text = "Откуда-то донёсся голос. «Сбегаю!» — ответил герой."
        assertEquals(listOf(text), RuVoiceTextChunks.split(text))
    }

    @Test fun longBookPassageIsBoundedWithoutLosingWordsOrPunctuation() {
        val paragraph = "Он спросил, откуда-то ли слышен шум? «Сбегаю!» — сказала она. "
        val source = paragraph.repeat(20).trimEnd()
        val chunks = RuVoiceTextChunks.split(source)
        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= RuVoiceTextChunks.MAX_CHARS })
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.dropLast(1).any { it.last() in setOf('.', '?', '!', ';', '…') })
    }

    @Test fun longUnbrokenWordDoesNotLoopOrDropCharacters() {
        val source = "я".repeat(1_000)
        val parts = RuVoiceTextChunks.split(source)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= RuVoiceTextChunks.MAX_CHARS })
        assertEquals(source, parts.joinToString(""))
    }

    @Test fun supplementaryUnicodeCharacterSurvivesAtChunkBoundary() {
        val source = "ж".repeat(RuVoiceTextChunks.MAX_CHARS - 1) + "🎧" + "Продолжение.".repeat(40)
        val parts = RuVoiceTextChunks.split(source)
        assertTrue(parts.all { it.length <= RuVoiceTextChunks.MAX_CHARS })
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }

    @Test fun boundariesPreferWhitespaceBeforeBreakingWords() {
        val source = "Оченьдлинноеслово ".repeat(65).trim()
        val chunks = RuVoiceTextChunks.split(source)
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= RuVoiceTextChunks.MAX_CHARS })
        assertTrue(chunks.dropLast(1).all { it.last().isWhitespace() })
    }
}
