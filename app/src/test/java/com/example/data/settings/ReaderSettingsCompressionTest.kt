package com.example.data.settings

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsCompressionTest {
    @Test fun compressionAndBitrateSurviveRecreationAndDisablingKeepsTheChosenBitrate() {
        val prefs = preferences()
        val first = ReaderSettingsStore(prefs)
        assertFalse(first.state.value.compressSavedNarration)
        assertEquals(64, first.state.value.savedNarrationBitrateKbps)
        first.setSavedNarrationBitrateKbps(128)
        first.setCompressSavedNarration(true)
        first.setFontSize(22)
        val second = ReaderSettingsStore(prefs)
        assertTrue(second.state.value.compressSavedNarration)
        assertEquals(128, second.state.value.savedNarrationBitrateKbps)
        assertEquals(22, second.state.value.fontSize)
        second.setCompressSavedNarration(false)
        val third = ReaderSettingsStore(prefs)
        assertFalse(third.state.value.compressSavedNarration)
        assertEquals(128, third.state.value.savedNarrationBitrateKbps)
        third.reset()
        assertEquals(ReaderSettings(), ReaderSettingsStore(prefs).state.value)
    }

    @Test fun readingAppearanceUpdatesPreserveFollowAndExportPreferences() {
        val prefs = preferences()
        val store = ReaderSettingsStore(prefs)
        assertEquals(18, store.state.value.fontSize)
        assertEquals(ReaderFont.SERIF, store.state.value.font)
        store.setFollowNarration(true)
        store.setCompressSavedNarration(true)
        store.setSavedNarrationBitrateKbps(128)
        store.setReadingAppearance(ReaderSettings(fontSize = 24, font = ReaderFont.SANS,
            lineSpacing = 1.8f, wideMargins = false, appearance = ReaderAppearance.SEPIA))
        val restored = ReaderSettingsStore(prefs).state.value
        assertEquals(24, restored.fontSize)
        assertEquals(ReaderFont.SANS, restored.font)
        assertEquals(1.8f, restored.lineSpacing, 0f)
        assertFalse(restored.wideMargins)
        assertEquals(ReaderAppearance.SEPIA, restored.appearance)
        assertTrue(restored.followNarration)
        assertTrue(restored.compressSavedNarration)
        assertEquals(128, restored.savedNarrationBitrateKbps)
    }

    /** SharedPreferences boundary only; the tested store is the production implementation. */
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt", "getFloat", "getBoolean", "getString" -> values[args!![0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, Any>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { editor, editMethod, editArgs ->
                        when (editMethod.name) {
                            "putInt", "putFloat", "putBoolean", "putString" -> {
                                pending[editArgs!![0] as String] = editArgs[1]
                                editor
                            }
                            "apply" -> { values.putAll(pending); null }
                            else -> error("Unexpected editor call: ${editMethod.name}")
                        }
                    }
                }
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
