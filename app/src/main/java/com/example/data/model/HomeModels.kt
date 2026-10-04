package com.example.data.model

import com.squareup.moshi.Json

data class HomeRailDto(
    val id: String,
    val kind: String,
    val title: String,
    @Json(name = "target_type") val targetType: String,
    @Json(name = "target_id") val targetId: String,
    @Json(name = "target_name") val targetName: String = "",
    val items: List<BookCardDto> = emptyList()
)
