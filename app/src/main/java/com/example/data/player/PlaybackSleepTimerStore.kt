package com.example.data.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SleepTimerMode { OFF, MINUTES, END_OF_CHAPTER }

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val endAtMs: Long = 0L,
    val bookId: String? = null,
    val sourceCode: String? = null,
    val chapterIndex: Int = -1,
) {
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long = when (mode) {
        SleepTimerMode.MINUTES -> (endAtMs - nowMs).coerceAtLeast(0L)
        else -> 0L
    }
}

/** Small shared store so the service keeps a sleep timer even if the UI disconnects. */
class PlaybackSleepTimerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setMinutes(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 180)
        write(
            SleepTimerState(
                mode = SleepTimerMode.MINUTES,
                endAtMs = System.currentTimeMillis() + safeMinutes * 60_000L,
            )
        )
    }

    fun setEndOfChapter(bookId: String, sourceCode: String?, chapterIndex: Int) {
        if (bookId.isBlank() || chapterIndex < 0) return
        write(
            SleepTimerState(
                mode = SleepTimerMode.END_OF_CHAPTER,
                bookId = bookId,
                sourceCode = sourceCode?.takeIf { it.isNotBlank() },
                chapterIndex = chapterIndex,
            )
        )
    }

    fun clear() = write(SleepTimerState())

    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun write(value: SleepTimerState) {
        prefs.edit()
            .putString(KEY_MODE, value.mode.name)
            .putLong(KEY_END_AT, value.endAtMs)
            .putString(KEY_BOOK_ID, value.bookId)
            .putString(KEY_SOURCE_CODE, value.sourceCode)
            .putInt(KEY_CHAPTER_INDEX, value.chapterIndex)
            .apply()
        _state.value = value
    }

    private fun read(): SleepTimerState {
        val mode = prefs.getString(KEY_MODE, SleepTimerMode.OFF.name)
            ?.let { stored -> runCatching { SleepTimerMode.valueOf(stored) }.getOrDefault(SleepTimerMode.OFF) }
            ?: SleepTimerMode.OFF
        val state = SleepTimerState(
            mode = mode,
            endAtMs = prefs.getLong(KEY_END_AT, 0L),
            bookId = prefs.getString(KEY_BOOK_ID, null),
            sourceCode = prefs.getString(KEY_SOURCE_CODE, null),
            chapterIndex = prefs.getInt(KEY_CHAPTER_INDEX, -1),
        )
        return if (state.mode == SleepTimerMode.MINUTES && state.endAtMs <= 0L) SleepTimerState() else state
    }

    private companion object {
        const val PREFS_NAME = "abred_playback_sleep_timer"
        const val KEY_MODE = "mode"
        const val KEY_END_AT = "end_at_ms"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_SOURCE_CODE = "source_code"
        const val KEY_CHAPTER_INDEX = "chapter_index"
    }
}
