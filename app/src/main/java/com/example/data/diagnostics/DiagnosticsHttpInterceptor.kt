package com.example.data.diagnostics

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.Response

/** Request-local tags remain inside OkHttp: no diagnostic header is sent to the server. */
internal class DiagnosticsHttpInterceptor(
    private val torrServe: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!AppDiagnostics.isRecording) return chain.proceed(chain.request())
        val started = System.nanoTime()
        val request = chain.request()
        val operation = DiagnosticOperations.current.get()
        val hostSource = DiagnosticHttpPolicy.source(request.url.host)
        val inferredSource = hostSource.takeUnless { it == "other" }
            ?: DiagnosticHttpPolicy.sourceFromOrigin(request.header("Referer") ?: request.header("Origin"))
        val source = operation?.source ?: if (torrServe) "rutracker" else inferredSource
        val route = DiagnosticHttpPolicy.route(request.url.encodedPath, torrServe)
        val fields = operation?.fields().orEmpty() + mapOf(
            "source" to source,
            "sourceEvidence" to when {
                operation != null -> "operation"
                torrServe -> "transport"
                hostSource != "other" -> "destination"
                inferredSource != "other" -> "origin"
                else -> "unknown"
            },
            "operation" to (operation?.operation ?: when (route) {
                "audio" -> "playback_stream"
                "image" -> "cover"
                "playlist" -> "playlist"
                "torrserve_api" -> "torrserve_api"
                else -> "unattributed"
            }),
            "requestId" to ids.incrementAndGet().toString(),
            "method" to request.method.takeIf { it in setOf("GET", "POST", "HEAD", "PUT", "DELETE") }.let { it ?: "OTHER" },
            "target" to DiagnosticHttpPolicy.target(request.url.host, torrServe),
            "hostKey" to DiagnosticHttpPolicy.hostKey(request.url.host),
            "resourceKey" to DiagnosticHttpPolicy.resourceKey(request.url.toString()),
            "route" to route,
            "targetSource" to hostSource,
        )
        // The tag is immutable and follows redirects without becoming a wire header.
        val tagged = request.newBuilder().tag(DiagnosticHttpTrace::class.java, DiagnosticHttpTrace(fields)).build()
        AppDiagnostics.record("http_started", fields)
        return try {
            val response = chain.proceed(tagged)
            val history = generateSequence(response.priorResponse) { it.priorResponse }.take(20).toList().asReversed()
            history.forEachIndexed { index, hop ->
                AppDiagnostics.record(if (hop.isRedirect) "http_redirect" else "http_retry_response", fields + mapOf(
                    "status" to hop.code.toString(), "hop" to index.toString(),
                    "targetSource" to DiagnosticHttpPolicy.source(hop.request.url.host),
                    "target" to DiagnosticHttpPolicy.target(hop.request.url.host, torrServe),
                    "hostKey" to DiagnosticHttpPolicy.hostKey(hop.request.url.host),
                    "targetResourceKey" to DiagnosticHttpPolicy.resourceKey(hop.request.url.toString()),
                ) + DiagnosticHttpPolicy.responseRequestPresence(hop))
            }
            AppDiagnostics.record("http_complete", fields + mapOf(
                "status" to response.code.toString(),
                "targetSource" to DiagnosticHttpPolicy.source(response.request.url.host),
                "elapsedMs" to ((System.nanoTime() - started) / 1_000_000).toString(),
                "redirects" to history.count { it.isRedirect }.toString(),
                "followups" to history.size.toString(),
                "target" to DiagnosticHttpPolicy.target(response.request.url.host, torrServe),
                "hostKey" to DiagnosticHttpPolicy.hostKey(response.request.url.host),
                "targetResourceKey" to DiagnosticHttpPolicy.resourceKey(response.request.url.toString()),
                "format" to DiagnosticHttpPolicy.contentType(response.header("Content-Type")),
                "cloudflare" to response.header("Server").equals("cloudflare", ignoreCase = true).toString(),
                "challengeHint" to response.header("cf-mitigated").equals("challenge", ignoreCase = true).toString(),
                "cached" to (response.cacheResponse != null).toString(),
            ) + DiagnosticHttpPolicy.responseRequestPresence(response) + response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?.let { mapOf("retryAfterSeconds" to it.toString()) }.orEmpty())
            response
        } catch (error: IOException) {
            AppDiagnostics.record("http_failed", fields + mapOf(
                "elapsedMs" to ((System.nanoTime() - started) / 1_000_000).toString(),
                "kind" to DiagnosticFailures.category(error),
            ))
            throw error
        }
    }

    private data class DiagnosticHttpTrace(val fields: Map<String, String>)
    private companion object { val ids = AtomicLong() }
}
