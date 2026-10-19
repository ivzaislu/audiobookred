package com.example.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPrivacyTest {
    @Test fun credentialsAndRequestDataNeverEnterEvents() {
        val output = DiagnosticPrivacy.event("player_error", mapOf(
            "code" to "2001", "position" to "34000", "source" to "rutracker",
            "password" to "123456", "cookie" to "session=SECRET", "token" to "SECRET",
            "url" to "https://user:SECRET@example.org/file?token=SECRET",
            "controller" to "private.application", "message" to "SECRET",
        ))!!
        assertEquals("event=player_error code=2001 position=34000 source=rutracker", output)
        assertFalse(output.contains("SECRET"))
    }

    @Test fun unexpectedValuesAndEventNamesAreRejected() {
        assertNull(DiagnosticPrivacy.event("https://secret.invalid", emptyMap()))
        assertEquals("event=timeline", DiagnosticPrivacy.event("timeline", mapOf(
            "position" to "123\npassword=SECRET", "source" to "SECRET", "stage" to "SECRET",
            "reason" to "https://secret.invalid", "ready" to "yes",
        )))
    }

    @Test fun chapterTransitionAndRecoveryDetailsRemainUseful() {
        val result = DiagnosticPrivacy.event("position_discontinuity", mapOf(
            "from" to "11:120000", "to" to "0:0", "repeat" to "0", "count" to "12",
            "stage" to "source_refresh", "autoPlay" to "true",
        ))!!
        assertTrue(result.contains("from=11:120000"))
        assertTrue(result.contains("to=0:0"))
        assertTrue(result.contains("autoPlay=true"))
    }

    @Test fun crashIncludesLocationButNoMessagesOrFilenames() {
        val error = IllegalStateException("https://user:SECRET@example.org password=SECRET",
            IllegalArgumentException("cookie=SECRET"))
        error.stackTrace = arrayOf(StackTraceElement("com.example.Player", "advance", "/private/SECRET.kt", 42))
        val output = DiagnosticPrivacy.crash(error)
        assertTrue(output.contains("java.lang.IllegalStateException"))
        assertTrue(output.contains("com.example.Player.advance:42"))
        assertTrue(output.contains("java.lang.IllegalArgumentException"))
        assertFalse(output.contains("SECRET"))
        assertFalse(output.contains("https://"))
        assertFalse(output.contains(".kt"))
    }

    @Test fun cyclicAndHugeCauseChainsAreBounded() {
        val first = RuntimeException("SECRET")
        val second = RuntimeException("SECRET")
        first.initCause(second)
        second.initCause(first)
        first.stackTrace = Array(10_000) { StackTraceElement("com.example.Player", "step", "SECRET", 1) }
        val report = DiagnosticPrivacy.crash(first)
        assertTrue(report.length <= 64 * 1024)
        assertEquals(2, Regex("exception=").findAll(report).count())
        assertFalse(report.contains("SECRET"))
    }
}
