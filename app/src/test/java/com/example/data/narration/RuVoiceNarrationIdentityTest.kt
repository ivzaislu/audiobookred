package com.example.data.narration

import org.junit.Assert.*
import org.junit.Test

class RuVoiceNarrationIdentityTest {
    @Test fun rawTextIsForwardedUnchangedWithoutEmbeddedSpeechCorrections() {
        val text = "Батарея села. Откуда-то, почему-то, сбегаю, ексель-моксель."
        val spec = NarrationSpec.create(text, "xenia-ru", 10100L)
        assertEquals(text, spec.source)
        assertEquals("xenia-ru", spec.voiceName)
        assertEquals(10100L, spec.engineVersion)
    }

    @Test fun cacheIsSeparatedByVoiceEngineVersionAndRuVoiceSettings() {
        val text = "Слушаем только RuVoice."
        val xenia = NarrationSpec.create(text, "xenia-ru", 10100L)
        val aidar = NarrationSpec.create(text, "aidar-ru", 10100L)
        val upgrade = NarrationSpec.create(text, "xenia-ru", 10101L)
        val settings = NarrationSpec.create(text, "xenia-ru", 10100L, 1L)
        assertEquals(xenia.key, NarrationSpec.create(text, "xenia-ru", 10100L).key)
        assertNotEquals(xenia.key, aidar.key)
        assertNotEquals(xenia.key, upgrade.key)
        assertNotEquals(xenia.key, settings.key)
    }

    @Test fun inputValidationDoesNotChangeRussianStressOrPunctuation() {
        val original = "«Откуда-то пришёл?» — спросил он."
        assertEquals(original, NarrationInput.prepare(original))
        assertThrows(IllegalArgumentException::class.java) { NarrationInput.prepare(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            NarrationInput.prepare("а".repeat(NarrationInput.MAX_TEXT_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { NarrationInput.prepare("тест\u0000") }
    }

    @Test fun embeddedAcousticEngineIsAbsentFromRuVoiceBuild() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.example.data.narration.AndroidSileroPreviewEngine")
        }
    }
}
