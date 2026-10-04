package com.example.data.cache

import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto

/**
 * JSON payload shape retained by the Room-backed standalone library cache.
 *
 * The old 0.3.x SharedPreferences cache reader/writer and retired `home:v1`
 * payload are no longer instantiated. LibraryCache remains because existing Room
 * payloads and backup files deserialize this stable user-owned shape.
 */
object AppCacheStore {
    data class LibraryCache(
        val favorites: List<BookCardDto> = emptyList(),
        val history: List<BookCardDto> = emptyList(),
        val series: List<MySeriesDto> = emptyList(),
        val savedAtMs: Long = 0L
    )
}
