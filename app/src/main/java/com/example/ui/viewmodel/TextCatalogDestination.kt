package com.example.ui.viewmodel

import org.json.JSONObject

internal data class TextCatalogDestination(val sourceCode: String, val url: String, val bookId: String? = null, val query: String = "") {
    val key: String get() = "$sourceCode:$url"
    fun encode(): String = JSONObject().put("source", sourceCode).put("url", url).put("book", bookId)
        .put("query", query).toString()
    companion object {
        fun decode(raw: String): TextCatalogDestination? = runCatching {
            val json = JSONObject(raw)
            TextCatalogDestination(json.getString("source"), json.getString("url"),
                json.optString("book").takeIf { it.isNotBlank() }, json.optString("query"))
        }.getOrNull()
    }
}
