package com.example.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSessionTest {
    @Test fun disabledByDefaultAndExpiresAtExactlyOneHour() {
        var elapsed = 50L
        val session = DiagnosticSession { elapsed }
        assertFalse(session.isActive())
        session.start()
        elapsed += DiagnosticSession.MAX_DURATION_MS - 1
        assertTrue(session.isActive())
        elapsed++
        assertFalse(session.isActive())
    }

    @Test fun stopAndProcessRestartDoNotResumeRecording() {
        var elapsed = 10L
        val session = DiagnosticSession { elapsed }
        session.start()
        elapsed++
        session.stop()
        assertFalse(session.isActive())
        assertFalse(DiagnosticSession { elapsed }.isActive())
    }
}
