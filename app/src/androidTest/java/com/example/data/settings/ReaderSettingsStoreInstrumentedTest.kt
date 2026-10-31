package com.example.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSettingsStoreInstrumentedTest {
    @Test fun followPreferenceSurvivesStoreRecreationAndOtherSettingChanges() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reader-settings-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try {
            val first = ReaderSettingsStore(context)
            assertFalse(first.state.value.followNarration)
            assertFalse(first.state.value.compressSavedNarration)
            assertEquals(64, first.state.value.savedNarrationBitrateKbps)
            first.setFollowNarration(true)
            first.setCompressSavedNarration(true)
            first.setSavedNarrationBitrateKbps(128)
            first.setFontSize(22)
            val reopened = ReaderSettingsStore(context)
            assertTrue(reopened.state.value.followNarration)
            assertEquals(22, reopened.state.value.fontSize)
            assertTrue(reopened.state.value.compressSavedNarration)
            assertEquals(128, reopened.state.value.savedNarrationBitrateKbps)
            reopened.setFollowNarration(false)
            assertFalse(ReaderSettingsStore(context).state.value.followNarration)
            assertEquals(22, ReaderSettingsStore(context).state.value.fontSize)
            reopened.setCompressSavedNarration(false)
            assertFalse(ReaderSettingsStore(context).state.value.compressSavedNarration)
            assertEquals(128, ReaderSettingsStore(context).state.value.savedNarrationBitrateKbps)
            reopened.setSavedNarrationBitrateKbps(-1)
            assertEquals(64, ReaderSettingsStore(context).state.value.savedNarrationBitrateKbps)
            reopened.setCompressSavedNarration(true)
            reopened.reset()
            assertFalse(ReaderSettingsStore(context).state.value.compressSavedNarration)
            assertEquals(64, ReaderSettingsStore(context).state.value.savedNarrationBitrateKbps)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
