package com.example.data.narration

import org.junit.Assert.*
import org.junit.Test

class RuVoiceExportManifestTest {
    @Test fun wavManifestRemainsCompatibleAndAacBitratesHaveIndependentGenerations() {
        val source = textAudioSourceId("txt_a", null)
        val wav = ruVoiceExportManifestId(source, "xenia-ru", 10100, 0)
        assertEquals("9520b4de3ed422553d4eee49321661c7190e0045796a1bbff91e17c5e08c3ec4", wav)
        val aac64 = ruVoiceExportManifestId(source, "xenia-ru", 10100, 0, TextAudioExportEncoding(64))
        val aac128 = ruVoiceExportManifestId(source, "xenia-ru", 10100, 0, TextAudioExportEncoding(128))
        assertEquals(3, setOf(wav, aac64, aac128).size)
        assertEquals(aac64, ruVoiceExportManifestId(source, "xenia-ru", 10100, 0, TextAudioExportEncoding(64)))
        assertThrows(IllegalArgumentException::class.java) { TextAudioExportEncoding(65) }
    }

    @Test fun exportIdentityIsPinnedToRuVoiceVoiceVersionAndConfiguration() {
        val source = textAudioSourceId("txt_a", "chapter1")
        val xenia = ruVoiceExportManifestId(source, "xenia-ru", 10100, 0)
        assertTrue(xenia.matches(Regex("[0-9a-f]{64}")))
        assertEquals(xenia, ruVoiceExportManifestId(source, "xenia-ru", 10100, 0))
        assertNotEquals(xenia, ruVoiceExportManifestId(source, "aidar-ru", 10100, 0))
        assertNotEquals(xenia, ruVoiceExportManifestId(source, "xenia-ru", 10101, 0))
        assertNotEquals(xenia, ruVoiceExportManifestId(source, "xenia-ru", 10100, 1))
        assertNotEquals(xenia, ruVoiceExportManifestId(textAudioSourceId("txt_a", null), "xenia-ru", 10100, 0))
    }

    @Test fun exportCannotUseLegacySileroOrMissingRuVoiceContract() {
        val source = textAudioSourceId("txt_a", null)
        assertThrows(IllegalArgumentException::class.java) {
            ruVoiceExportManifestId(source, "xenia-ru", 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ruVoiceExportManifestId(source, "xenia-ru", 10100, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ruVoiceExportManifestId("external:book1", "xenia-ru", 10100, 0)
        }
    }
}
