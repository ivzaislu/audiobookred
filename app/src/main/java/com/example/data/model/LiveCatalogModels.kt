package com.example.data.model

import com.squareup.moshi.Json

/**
 * Request-time source card. Unlike [BookCardDto], this is not a persistent
 * library book and must not be used directly for favorites/progress/bookmarks.
 */
data class LiveCatalogItemDto(
    val key: String,
    val source: String,
    @Json(name = "external_id") val externalId: String,
    @Json(name = "external_url") val externalUrl: String = "",
    val title: String,
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "duration_seconds") val durationSeconds: Long = 0,
    @Json(name = "source_meta") val sourceMeta: String = "",
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    @Json(name = "series_name") val seriesName: String = "",
    @Json(name = "series_external_id") val seriesExternalId: String = "",
    @Json(name = "series_position") val seriesPosition: Int? = null,
)
