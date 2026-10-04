package com.example.data.settings

import android.content.Context

/** Device-local preferred audio source for a canonical audiobook card. */
class BookSourcePreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "audiobookred_book_sources",
        Context.MODE_PRIVATE
    )

    fun get(bookId: String): String? =
        prefs.getString("source:$bookId", null)?.takeIf { it.isNotBlank() }

    fun set(bookId: String, sourceCode: String) {
        if (bookId.isBlank() || sourceCode.isBlank()) return
        prefs.edit().putString("source:$bookId", sourceCode.trim()).apply()
    }

    fun clear(bookId: String) {
        prefs.edit().remove("source:$bookId").apply()
    }

    internal fun snapshot(): Map<String, String> = prefs.all
        .mapNotNull { (key, value) ->
            val bookId = key.removePrefix(SOURCE_PREFIX)
            if (key.startsWith(SOURCE_PREFIX) && bookId.isNotBlank() && value is String && value.isNotBlank()) {
                bookId to value
            } else {
                null
            }
        }
        .toMap(linkedMapOf())

    /** Atomically replaces the complete source-preference snapshot on disk. */
    internal fun replaceAll(values: Map<String, String>): Boolean {
        val editor = prefs.edit().clear()
        values.forEach { (bookId, sourceCode) ->
            if (bookId.isNotBlank() && sourceCode.isNotBlank()) {
                editor.putString("$SOURCE_PREFIX$bookId", sourceCode.trim())
            }
        }
        return editor.commit()
    }

    private companion object {
        const val SOURCE_PREFIX = "source:"
    }
}
