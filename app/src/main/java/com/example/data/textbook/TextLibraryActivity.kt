package com.example.data.textbook

import org.json.JSONObject

internal fun textLibraryPlaybackBookId(sourceCode: String, bookId: String, bookSourceId: String): String? = when (sourceCode) {
    "text_narration" -> bookId.takeIf { it.matches(Regex("txt_[0-9a-f]{64}")) }
    "text_audio" -> Regex("^text_audio:(txt_[0-9a-f]{64}):").find(bookSourceId)?.groupValues?.get(1)
    else -> null
}

/** Library membership only: never a reading cursor or an audio checkpoint. */
internal data class TextLibraryActivity(
    val bookId: String,
    val sourceCode: String = "",
    val sourceName: String = "",
    val remoteBookId: String = "",
    val seriesId: String = "",
    val seriesName: String = "",
    val seriesNumber: String = "",
    val lastActivityAtMs: Long = 0,
    val historyVisible: Boolean = false,
    val seriesVisible: Boolean = false,
) {
    val seriesKey: String get() = if (sourceCode.isBlank() || seriesId.isBlank()) "" else "text:$sourceCode:$seriesId"
    fun visited(now: Long) = copy(lastActivityAtMs = maxOf(now, lastActivityAtMs), historyVisible = true, seriesVisible = true)
}

internal fun textLibraryHistory(records: Collection<TextLibraryActivity>, existingBookIds: Set<String>): List<TextLibraryActivity> =
    records.filter { it.bookId in existingBookIds && it.historyVisible && it.lastActivityAtMs > 0 }
        .sortedWith(compareByDescending<TextLibraryActivity> { it.lastActivityAtMs }.thenBy { it.bookId })

/** A cycle is promoted by actual reading/listening, but includes its unstarted downloaded members. */
internal fun textLibraryCycles(records: Collection<TextLibraryActivity>, existingBookIds: Set<String>): List<List<TextLibraryActivity>> =
    records.filter { it.bookId in existingBookIds && it.seriesKey.isNotBlank() && it.seriesName.isNotBlank() }
        .groupBy { it.seriesKey }.values
        .filter { members -> members.any { it.seriesVisible && it.lastActivityAtMs > 0 } }
        .sortedByDescending { members -> members.maxOf { it.lastActivityAtMs } }
        .map { members -> members.sortedWith(compareBy<TextLibraryActivity> {
            it.seriesNumber.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite) ?: Double.MAX_VALUE
        }.thenBy { it.bookId }) }

internal fun encodeTextLibraryActivity(value: TextLibraryActivity): String = with(value) {
    JSONObject().put("version", 1).put("bookId", bookId).put("sourceCode", sourceCode)
        .put("sourceName", sourceName).put("remoteBookId", remoteBookId).put("seriesId", seriesId)
        .put("seriesName", seriesName).put("seriesNumber", seriesNumber).put("lastActivityAtMs", lastActivityAtMs)
        .put("historyVisible", historyVisible).put("seriesVisible", seriesVisible).toString()
}

internal fun decodeTextLibraryActivity(raw: String): TextLibraryActivity? = runCatching {
    val json = JSONObject(raw)
    require(json.getInt("version") == 1)
    val id = json.getString("bookId")
    require(id.matches(Regex("txt_[0-9a-f]{64}")))
    TextLibraryActivity(id, json.optString("sourceCode"), json.optString("sourceName"), json.optString("remoteBookId"),
        json.optString("seriesId"), json.optString("seriesName"), json.optString("seriesNumber"),
        json.optLong("lastActivityAtMs").coerceAtLeast(0), json.optBoolean("historyVisible"), json.optBoolean("seriesVisible"))
}.getOrNull()
