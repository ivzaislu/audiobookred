package com.example.data.parser

import com.example.data.model.BookDetailDto
import java.io.IOException

/** Preview guard shared by the current Audioboo transport and Book Detail UI. */
internal class PreviewOnlyAudiobooBook(
    val previewBook: BookDetailDto? = null,
    message: String = "audioboo_preview_only",
) : IOException(message)

internal const val AUDIOBOO_SOURCE = "audioboo"
internal const val AUDIOBOO_BASE_URL = "https://audioboo.org"
