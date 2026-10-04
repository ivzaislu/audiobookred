package com.example.domain.playback

import com.example.data.model.BookDetailDto

data class PreparedPlayback(
    val book: BookDetailDto,
    val chapterIndex: Int,
    val positionMs: Long,
    val speed: Float,
    val playbackWriteEpoch: Long? = null,
)
