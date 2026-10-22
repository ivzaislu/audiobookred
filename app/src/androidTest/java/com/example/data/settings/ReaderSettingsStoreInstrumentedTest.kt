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
            first.setFollowNarration(true)
            first.setFontSize(22)
            val reopened = ReaderSettingsStore(context)
            assertTrue(reopened.state.value.followNarration)
            assertEquals(22, reopened.state.value.fontSize)
            reopened.setFollowNarration(false)
            assertFalse(ReaderSettingsStore(context).state.value.followNarration)
            assertEquals(22, ReaderSettingsStore(context).state.value.fontSize)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
