package com.example.data.narration

import org.json.JSONArray
import org.json.JSONObject

/** PCM boundaries are exact; positions inside one fragment are interpolated, not word timings. */
internal data class TextAudioSyncEntry(val fragmentId: String, val startPcmBytes: Long, val endPcmBytes: Long)

internal data class TextAudioSyncMap(
    val manifestId: String,
    val chapterId: String,
    val sampleRate: Int,
    val entries: List<TextAudioSyncEntry>,
    val bookId: String = "",
    val sourceSha256: String = "",
) {
    fun validate(committedBytes: Long, fragmentIds: List<String>? = null): TextAudioSyncMap {
        require(manifestId.isNotBlank() && chapterId.isNotBlank() && sampleRate in 8_000..96_000)
        require(committedBytes in 0..ChapterWavWriter.MAX_DATA && committedBytes % 2L == 0L)
        var end = 0L
        val seen = mutableSetOf<String>()
        entries.forEach {
            require(it.fragmentId.isNotBlank() && seen.add(it.fragmentId))
            require(it.startPcmBytes == end && it.endPcmBytes > end && it.endPcmBytes % 2L == 0L)
            end = it.endPcmBytes
        }
        require(end == committedBytes)
        if (fragmentIds != null) require(entries.map { it.fragmentId } == fragmentIds)
        return this
    }

    fun textAt(positionMs: Long): Pair<String, Double>? {
        val target = positionMs.coerceAtLeast(0L).toDouble() * sampleRate * 2 / 1000
        if (entries.isEmpty()) return null
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (target < entries[middle].endPcmBytes) high = middle else low = middle + 1
        }
        val entry = entries[low.coerceAtMost(entries.lastIndex)]
        return entry.fragmentId to ((target - entry.startPcmBytes) / (entry.endPcmBytes - entry.startPcmBytes)).coerceIn(0.0, 1.0)
    }

    fun timeAt(fragmentId: String, fraction: Double): Long? {
        val entry = entries.firstOrNull { it.fragmentId == fragmentId } ?: return null
        val bytes = entry.startPcmBytes + (entry.endPcmBytes - entry.startPcmBytes) * fraction.coerceIn(0.0, 1.0)
        return (bytes * 1000 / (sampleRate * 2L)).toLong()
    }

    private fun milliseconds(bytes: Long) = bytes * 1000L / (sampleRate * 2L)

    fun json(): JSONObject = JSONObject().put("version", if (bookId.isNotBlank()) 2 else 1)
        .put("bookId", bookId).put("sourceSha256", sourceSha256).put("manifestId", manifestId)
        .put("chapterId", chapterId).put("sampleRate", sampleRate).put("entries", JSONArray().also { rows ->
            entries.forEach { rows.put(JSONObject().put("fragmentId", it.fragmentId)
                .put("startPcmBytes", it.startPcmBytes).put("endPcmBytes", it.endPcmBytes)
                .put("startMs", milliseconds(it.startPcmBytes)).put("endMs", milliseconds(it.endPcmBytes))) }
        })

    companion object {
        fun parse(json: JSONObject): TextAudioSyncMap {
            val version = json.getInt("version")
            require(version in 1..2)
            if (version == 2) require(json.getString("bookId").isNotBlank() && json.getString("sourceSha256").matches(Regex("[0-9a-f]{64}")))
            val rows = json.getJSONArray("entries")
            val map = TextAudioSyncMap(json.getString("manifestId"), json.getString("chapterId"), json.getInt("sampleRate"),
                (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
                    TextAudioSyncEntry(it.getString("fragmentId"), it.getLong("startPcmBytes"), it.getLong("endPcmBytes"))
                } }, if (version == 2) json.getString("bookId") else "", if (version == 2) json.getString("sourceSha256") else "")
            require(map.sampleRate in 8_000..96_000)
            if (version == 2) map.entries.forEachIndexed { index, entry ->
                val row = rows.getJSONObject(index)
                require(row.getLong("startMs") == map.milliseconds(entry.startPcmBytes) &&
                    row.getLong("endMs") == map.milliseconds(entry.endPcmBytes))
            }
            return map
        }
    }
}
