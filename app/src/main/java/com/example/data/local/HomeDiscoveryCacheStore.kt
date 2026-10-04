package com.example.data.local

import android.content.Context
import com.example.data.model.BookListResponse
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small persistent cache for Home discovery shelves.
 *
 * Keys intentionally live under the existing `browse:` namespace, so the
 * Settings action that clears disposable catalog data also clears these rows.
 */
class HomeDiscoveryCacheStore(context: Context) {
    data class Entry(
        val response: BookListResponse,
        val savedAtMs: Long,
    )

    private val appContext = context.applicationContext

    // HomeViewModel is created during the first Compose pass. Keep Room opening
    // and reflection-based Moshi adapter construction out of that main-thread
    // constructor path; first cache access initializes both on Dispatchers.IO.
    private val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AbredDatabase.get(appContext).cachedPayloads()
    }
    private val adapter: JsonAdapter<BookListResponse> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(BookListResponse::class.java)
    }

    suspend fun read(section: String): Entry? = withContext(Dispatchers.IO) {
        val row = dao.get(cacheKey(section)) ?: return@withContext null
        val response = runCatching { adapter.fromJson(row.payloadJson) }.getOrNull()
            ?: return@withContext null
        Entry(response = response, savedAtMs = row.savedAtMs)
    }

    suspend fun write(section: String, response: BookListResponse) = withContext(Dispatchers.IO) {
        if (response.items.isEmpty()) return@withContext
        val json = runCatching { adapter.toJson(response) }.getOrNull() ?: return@withContext
        dao.put(
            CachedPayloadEntity(
                cacheKey = cacheKey(section),
                payloadJson = json,
                savedAtMs = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private fun cacheKey(section: String): String =
            "browse:home-knigavuhe:${section.trim().lowercase()}:1"
    }
}
