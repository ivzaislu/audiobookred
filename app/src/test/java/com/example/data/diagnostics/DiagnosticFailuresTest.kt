package com.example.data.diagnostics

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFailuresTest {
    @Test fun wrappedFailurePreservesCategoryWithoutMessage() {
        assertEquals("timeout", DiagnosticFailures.category(IOException("SECRET", SocketTimeoutException("SECRET"))))
        assertEquals("invalid_data", DiagnosticFailures.category(IllegalArgumentException("SECRET")))
        assertEquals("other", DiagnosticFailures.category(Exception("SECRET")))
    }

    @Test fun cyclicCausesCannotLoopForever() {
        val a = RuntimeException("SECRET")
        val b = RuntimeException("SECRET")
        a.initCause(b)
        b.initCause(a)
        assertEquals("other", DiagnosticFailures.category(a))
    }
}
