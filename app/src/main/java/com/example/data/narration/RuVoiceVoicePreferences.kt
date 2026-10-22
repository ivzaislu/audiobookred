package com.example.data.narration

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Global voice selection for RuVoice. Advanced voice-specific rules remain in RuVoice itself. */
@Singleton
class RuVoiceVoicePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("abred_ruvoice_voice_v1", Context.MODE_PRIVATE)
    fun selected(): String = prefs.getString("voice", "") ?: ""
    /** A user-requested refresh after editing dictionaries, pauses or profiles in RuVoice. */
    fun revision(): Long = prefs.getLong("settingsRevision", 0L)
    fun refreshSettings(): Long {
        val next = (revision() + 1L).coerceAtLeast(1L)
        check(prefs.edit().putLong("settingsRevision", next).commit()) { "Не удалось обновить озвучку RuVoice." }
        return next
    }
    fun select(name: String) {
        require(name.length <= 200 && name.none { it.isISOControl() }) { "Недопустимое имя голоса." }
        check(prefs.edit().putString("voice", name).commit()) { "Не удалось сохранить голос RuVoice." }
    }
}
