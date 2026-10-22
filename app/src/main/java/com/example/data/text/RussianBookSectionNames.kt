package com.example.data.text

import java.util.Locale

/** Known publishing labels, not a translator for arbitrary foreign prose. */
internal object RussianBookSectionNames {
    private val names = mapOf(
        "annotation" to "Аннотация",
        "contents" to "Содержание",
        "table of contents" to "Содержание",
        "prologue" to "Пролог",
        "epilogue" to "Эпилог",
        "preface" to "Предисловие",
        "introduction" to "Введение",
        "about the author" to "Об авторе",
    )

    fun russian(label: String): String? = names[label.trim().removeSuffix(":").trim()
        .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)]
}
