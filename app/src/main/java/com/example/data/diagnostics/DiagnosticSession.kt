package com.example.data.diagnostics

/** Process-local monotonic deadline: clock changes and device reboot cannot prolong a session. */
internal class DiagnosticSession(private val clock: () -> Long) {
    private var deadline: Long? = null

    @Synchronized fun start() { deadline = clock() + MAX_DURATION_MS }
    @Synchronized fun stop() { deadline = null }
    @Synchronized fun isActive(): Boolean = deadline?.let { clock() < it } ?: false

    companion object { const val MAX_DURATION_MS = 60 * 60 * 1000L }
}
