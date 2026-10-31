package com.example.data.settings

internal const val DEFAULT_NARRATION_BITRATE_KBPS = 64
internal val NARRATION_BITRATES_KBPS = listOf(32, 48, 64, 96, 128)

internal fun sanitizeNarrationBitrate(value: Int): Int =
    value.takeIf { it in NARRATION_BITRATES_KBPS } ?: DEFAULT_NARRATION_BITRATE_KBPS
