package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderFont { SANS, SERIF }
enum class ReaderAppearance { APP, LIGHT, DARK, SEPIA }

data class ReaderSettings(
    val fontSize: Int = 18,
    val font: ReaderFont = ReaderFont.SERIF,
    val lineSpacing: Float = 1.5f,
    val wideMargins: Boolean = true,
    val appearance: ReaderAppearance = ReaderAppearance.APP,
    val followNarration: Boolean = false,
    val compressSavedNarration: Boolean = false,
    val savedNarrationBitrateKbps: Int = DEFAULT_NARRATION_BITRATE_KBPS,
)

/** Global reader preferences; text IDs and both bookmarks remain independent. */
@Singleton
class ReaderSettingsStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("abred_reader_settings", Context.MODE_PRIVATE))

    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    fun setReadingAppearance(value: ReaderSettings) = update {
        copy(fontSize = value.fontSize.coerceIn(12, 32), font = value.font,
            lineSpacing = if (value.lineSpacing.isFinite()) value.lineSpacing.coerceIn(1.2f, 2f) else 1.5f,
            wideMargins = value.wideMargins, appearance = value.appearance)
    }
    fun setFontSize(value: Int) = update { copy(fontSize = value.coerceIn(12, 32)) }
    fun setFont(value: ReaderFont) = update { copy(font = value) }
    fun setLineSpacing(value: Float) = update { copy(lineSpacing = if (value.isFinite()) value.coerceIn(1.2f, 2f) else 1.5f) }
    fun setWideMargins(value: Boolean) = update { copy(wideMargins = value) }
    fun setAppearance(value: ReaderAppearance) = update { copy(appearance = value) }
    fun setFollowNarration(value: Boolean) = update { copy(followNarration = value) }
    fun setCompressSavedNarration(value: Boolean) = update { copy(compressSavedNarration = value) }
    fun setSavedNarrationBitrateKbps(value: Int) = update { copy(savedNarrationBitrateKbps = sanitizeNarrationBitrate(value)) }
    fun reset() = update { ReaderSettings() }

    private fun update(change: ReaderSettings.() -> ReaderSettings) {
        val next = mutableState.value.change()
        prefs.edit().putInt("font_size", next.fontSize).putString("font", next.font.name)
            .putFloat("line_spacing", next.lineSpacing).putBoolean("wide_margins", next.wideMargins)
            .putString("appearance", next.appearance.name)
            .putBoolean("follow_narration", next.followNarration)
            .putBoolean("compress_saved_narration", next.compressSavedNarration)
            .putInt("saved_narration_bitrate_kbps", next.savedNarrationBitrateKbps).apply()
        mutableState.value = next
    }

    private fun read(): ReaderSettings {
        val spacing = prefs.getFloat("line_spacing", 1.5f)
        return ReaderSettings(
            fontSize = prefs.getInt("font_size", 18).coerceIn(12, 32),
            font = runCatching { ReaderFont.valueOf(prefs.getString("font", "SERIF").orEmpty()) }.getOrDefault(ReaderFont.SERIF),
            lineSpacing = if (spacing.isFinite()) spacing.coerceIn(1.2f, 2f) else 1.5f,
            wideMargins = prefs.getBoolean("wide_margins", true),
            followNarration = prefs.getBoolean("follow_narration", false),
            compressSavedNarration = prefs.getBoolean("compress_saved_narration", false),
            savedNarrationBitrateKbps = sanitizeNarrationBitrate(prefs.getInt("saved_narration_bitrate_kbps", DEFAULT_NARRATION_BITRATE_KBPS)),
            appearance = runCatching { ReaderAppearance.valueOf(prefs.getString("appearance", "APP").orEmpty()) }.getOrDefault(ReaderAppearance.APP),
        )
    }
}
