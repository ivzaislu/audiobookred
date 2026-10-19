package com.example.data.diagnostics

import com.example.data.model.BookDetailDto
import com.example.data.source.StandaloneSourceRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

internal data class DiagnosticOperation(val source: String, val operation: String, val operationId: Long) {
    fun fields(): Map<String, String> = mapOf(
        "source" to source, "operation" to operation, "operationId" to operationId.toString(),
    )
}

internal object DiagnosticOperations {
    val current = ThreadLocal<DiagnosticOperation?>()
    private val ids = AtomicLong()

    suspend fun <T> trace(source: String, operation: String, block: suspend () -> T): T {
        if (!AppDiagnostics.isRecording) return block()
        val context = DiagnosticOperation(
            StandaloneSourceRegistry.normalize(source).takeIf(StandaloneSourceRegistry::isActive) ?: "other",
            operation, ids.incrementAndGet(),
        )
        return withContext(current.asContextElement(context)) {
            val started = System.nanoTime()
            AppDiagnostics.record("parser_started", context.fields())
            try {
                val result = block()
                val summary = when (result) {
                    is Collection<*> -> mapOf("resultCount" to result.size.toString())
                    is BookDetailDto -> mapOf("resultChapters" to result.chapters.size.toString())
                    else -> emptyMap()
                }
                AppDiagnostics.record("parser_complete", context.fields() + summary +
                    ("elapsedMs" to ((System.nanoTime() - started) / 1_000_000).toString()))
                result
            } catch (cancelled: CancellationException) {
                AppDiagnostics.record("parser_cancelled", context.fields())
                throw cancelled
            } catch (error: Exception) {
                AppDiagnostics.record("parser_failed", context.fields() + mapOf(
                    "kind" to DiagnosticFailures.category(error),
                    "elapsedMs" to ((System.nanoTime() - started) / 1_000_000).toString(),
                ))
                throw error
            }
        }
    }

    fun record(event: String, fields: Map<String, String>) {
        AppDiagnostics.record(event, current.get()?.fields().orEmpty() + fields)
    }
}
