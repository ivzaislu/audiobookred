package com.example.data.diagnostics

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

internal object DiagnosticFailures {
    fun category(error: Throwable): String {
        // Bound traversal even if a custom exception supplies a cyclic cause chain.
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is CancellationException } -> "cancelled"
            causes.any { it is SocketTimeoutException } -> "timeout"
            causes.any { it is UnknownHostException } -> "dns"
            causes.any { it is SSLException } -> "tls"
            causes.any { it is ConnectException } -> "connect"
            causes.any { it.javaClass.simpleName == "AudiobooRateLimitedException" } -> "rate_limited"
            causes.any { it.javaClass.simpleName == "RuTrackerAuthenticationRequiredException" } -> "authentication"
            causes.any { it.javaClass.simpleName == "PreviewOnlyUknigBook" } -> "preview_only"
            causes.any { it.javaClass.simpleName == "UnavailableUknigBook" } -> "unavailable"
            causes.any { it.javaClass.simpleName in setOf("JSONException", "JsonDataException", "JsonEncodingException") } -> "invalid_data"
            causes.any { it is IOException } -> "io"
            causes.any { it is IllegalArgumentException } -> "invalid_data"
            causes.any { it is IllegalStateException } -> "invalid_state"
            else -> "other"
        }
    }
}
