package com.example.data.textbook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owned by the singleton TextLibraryRepository; small durable records, no text or WAV data. */
internal class TextLibraryActivityStore(context: Context, preferencesName: String = "abred_text_library_activity") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val records = preferences.all.values.filterIsInstance<String>().mapNotNull(::decodeTextLibraryActivity)
        .associateBy { it.bookId }.toMutableMap()
    private val mutableState = MutableStateFlow(records.toMap())
    val state = mutableState.asStateFlow()

    @Synchronized fun catalog(value: TextLibraryActivity) {
        val previous = records[value.bookId] ?: TextLibraryActivity(value.bookId)
        save(value.copy(lastActivityAtMs = previous.lastActivityAtMs, historyVisible = previous.historyVisible,
            seriesVisible = previous.seriesVisible))
    }

    @Synchronized fun restoreActivity(bookId: String, timestamp: Long) {
        val previous = records[bookId] ?: TextLibraryActivity(bookId)
        if (previous.lastActivityAtMs == 0L && timestamp > 0L) save(previous.visited(timestamp))
    }
    @Synchronized fun visit(bookId: String) = save((records[bookId] ?: TextLibraryActivity(bookId)).visited(System.currentTimeMillis()))
    @Synchronized fun hideHistory(bookId: String) { records[bookId]?.let { save(it.copy(historyVisible = false)) } }
    @Synchronized fun hideSeries(seriesKey: String) {
        val changed = records.values.filter { it.seriesKey == seriesKey }.map { it.copy(seriesVisible = false) }
        val editor = preferences.edit()
        changed.forEach { editor.putString(it.bookId, encodeTextLibraryActivity(it)) }
        check(editor.commit()) { "Не удалось скрыть цикл." }
        changed.forEach { records[it.bookId] = it }
        mutableState.value = records.toMap()
    }
    @Synchronized fun remove(bookId: String) {
        check(preferences.edit().remove(bookId).commit()) { "Не удалось удалить запись книги." }
        records.remove(bookId)
        mutableState.value = records.toMap()
    }
    private fun save(value: TextLibraryActivity) {
        check(preferences.edit().putString(value.bookId, encodeTextLibraryActivity(value)).commit()) { "Не удалось сохранить историю книги." }
        records[value.bookId] = value
        mutableState.value = records.toMap()
    }
}
