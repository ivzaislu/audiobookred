package com.example.data.local

import android.content.Context
import com.example.data.model.SeriesDetailDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Page-scoped Room cache for Series Paging 3. */
class SeriesPageCacheStore(context: Context) {
    private val dao = AbredDatabase.get(context).cachedPayloads()
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(SeriesDetailDto::class.java)

    suspend fun read(seriesKey: String, page: Int): SeriesDetailDto? {
        val row = dao.get(cacheKey(seriesKey, page)) ?: return null
        return runCatching { adapter.fromJson(row.payloadJson) }.getOrNull()
    }

    suspend fun write(seriesKey: String, page: Int, value: SeriesDetailDto) {
        val json = runCatching { adapter.toJson(value) }.getOrNull() ?: return
        dao.put(
            CachedPayloadEntity(
                cacheKey = cacheKey(seriesKey, page),
                payloadJson = json,
                savedAtMs = System.currentTimeMillis(),
            )
        )
    }

    private fun cacheKey(seriesKey: String, page: Int): String =
        "series:page:${part(seriesKey)}:${page.coerceAtLeast(1)}"

    private fun part(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
