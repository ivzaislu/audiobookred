package com.example.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderFont { SANS, SERIF }
enum class ReaderAppearance { APP, LIGHT, DARK, SEPIA }

data class ReaderSettings(
    val fontSize: Int = 16,
    val font: ReaderFont = ReaderFont.SANS,
    val lineSpacing: Float = 1.5f,
    val wideMargins: Boolean = false,
    val appearance: ReaderAppearance = ReaderAppearance.APP,
    val followNarration: Boolean = false,
)

/** Global reader preferences; text IDs and both bookmarks remain independent. */
@Singleton
class ReaderSettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("abred_reader_settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    fun setFontSize(value: Int) = update { copy(fontSize = value.coerceIn(12, 32)) }
    fun setFont(value: ReaderFont) = update { copy(font = value) }
    fun setLineSpacing(value: Float) = update { copy(lineSpacing = if (value.isFinite()) value.coerceIn(1.2f, 2f) else 1.5f) }
    fun setWideMargins(value: Boolean) = update { copy(wideMargins = value) }
    fun setAppearance(value: ReaderAppearance) = update { copy(appearance = value) }
    fun setFollowNarration(value: Boolean) = update { copy(followNarration = value) }
    fun reset() = update { ReaderSettings() }

    private fun update(change: ReaderSettings.() -> ReaderSettings) {
        val next = mutableState.value.change()
        prefs.edit().putInt("font_size", next.fontSize).putString("font", next.font.name)
            .putFloat("line_spacing", next.lineSpacing).putBoolean("wide_margins", next.wideMargins)
            .putString("appearance", next.appearance.name)
            .putBoolean("follow_narration", next.followNarration).apply()
        mutableState.value = next
    }

    private fun read(): ReaderSettings {
        val spacing = prefs.getFloat("line_spacing", 1.5f)
        return ReaderSettings(
            fontSize = prefs.getInt("font_size", 16).coerceIn(12, 32),
            font = runCatching { ReaderFont.valueOf(prefs.getString("font", "SANS").orEmpty()) }.getOrDefault(ReaderFont.SANS),
            lineSpacing = if (spacing.isFinite()) spacing.coerceIn(1.2f, 2f) else 1.5f,
            wideMargins = prefs.getBoolean("wide_margins", false),
            followNarration = prefs.getBoolean("follow_narration", false),
            appearance = runCatching { ReaderAppearance.valueOf(prefs.getString("appearance", "APP").orEmpty()) }.getOrDefault(ReaderAppearance.APP),
        )
    }
}
