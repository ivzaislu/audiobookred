package com.example.data.textsource

import java.io.IOException
import com.example.data.diagnostics.AppDiagnostics
import com.example.data.diagnostics.DiagnosticFailures

/** Typed transport/content failures; presentation never depends on exception text. */
internal class TextSourceHttpException(
    val status: Int,
    val browserChallenge: Boolean = false,
) : IOException("Text source HTTP $status")

internal enum class TextSourceContentFailure { PAGE, FILE, TOO_LARGE, BROWSER_CHALLENGE }

internal class TextSourceContentException(
    val reason: TextSourceContentFailure,
    cause: Throwable? = null,
) : IllegalArgumentException("Text source response: $reason", cause)

internal enum class TextImportStorageFailure { PERMISSION, PUBLICATION, SOURCE_LINK }

internal class TextImportStorageException(
    val reason: TextImportStorageFailure,
    cause: Throwable? = null,
) : IOException(
    when (reason) {
        TextImportStorageFailure.PERMISSION -> "Нет разрешения на сохранение оригинала книги."
        TextImportStorageFailure.PUBLICATION -> "Книга добавлена в библиотеку, но сохранить оригинал в Books не удалось."
        TextImportStorageFailure.SOURCE_LINK -> "Книга добавлена, но не удалось сохранить связь с источником."
    },
    cause,
)

/** Existing privacy-filtered diagnostics: no URLs, titles, response bodies or exception text. */
internal fun recordTextSourceFailure(error: Throwable, operation: String) {
    val status = generateSequence(error) { it.cause }.take(16)
        .filterIsInstance<TextSourceHttpException>().firstOrNull()?.status
    AppDiagnostics.record("parser_failed", mapOf(
        "source" to "other",
        "operation" to operation,
        "kind" to DiagnosticFailures.category(error),
    ) + (status?.let { mapOf("status" to it.toString()) } ?: emptyMap()))
}
