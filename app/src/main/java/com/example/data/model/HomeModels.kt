package com.example.data.model

data class HomeRailDto(
    val kind: String,
    val items: List<BookCardDto> = emptyList()
)
