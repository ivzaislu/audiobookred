package com.example.data.diagnostics

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiagnosticsSharingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() = runBlocking {
        AppDiagnostics.initialize(context)
        withContext(Dispatchers.IO) {
            AppDiagnostics.setRecording(false)
            AppDiagnostics.clear()
        }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.IO) {
            AppDiagnostics.setRecording(false)
            AppDiagnostics.clear()
        }
    }

    @Test fun exportedReportCanBeOpenedAndExcludesSensitiveFields() = runBlocking {
        val report = withContext(Dispatchers.IO) {
            AppDiagnostics.setRecording(true)
            AppDiagnostics.record("player_error", mapOf("code" to "2001", "password" to "SECRET"))
            AppDiagnostics.exportReport(false)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.files", report)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(text.contains("event=player_error code=2001"))
        assertFalse(text.contains("SECRET"))
    }

    @Test fun disabledRecordingAndClearDoNotLeakPendingEvents() = runBlocking {
        val report = withContext(Dispatchers.IO) {
            AppDiagnostics.record("app_pause", mapOf("position" to "9000"))
            assertFalse(AppDiagnostics.exportReport(false).readText().contains("event=app_pause"))
            AppDiagnostics.setRecording(true)
            repeat(128) { AppDiagnostics.record("app_pause", mapOf("position" to "9000")) }
            AppDiagnostics.setRecording(false)
            AppDiagnostics.clear()
            AppDiagnostics.exportReport(false)
        }
        assertFalse(report.readText().contains("event=app_pause"))
    }

    @Test fun interruptedAtomicCrashWriteIsRecoveredBeforeShowingStatus() = runBlocking {
        withContext(Dispatchers.IO) {
            val folder = File(context.noBackupFilesDir, "diagnostics")
            File(folder, "last-crash.txt.bak").writeText("exception=java.lang.IllegalStateException\n")
            AppDiagnostics.refresh()
            assertNotNull(AppDiagnostics.state.value.crashSavedAt)
            assertTrue(AppDiagnostics.exportReport(true).readText().contains("IllegalStateException"))
        }
    }

    @Test fun exportedReportsAreCountedAndCanBeClearedWithoutEvents() = runBlocking {
        withContext(Dispatchers.IO) {
            val report = AppDiagnostics.exportReport(false)
            assertEquals(0L, AppDiagnostics.state.value.logBytes)
            assertTrue(AppDiagnostics.state.value.storedBytes >= report.length())
            AppDiagnostics.clear()
            assertFalse(report.exists())
            assertEquals(0L, AppDiagnostics.state.value.storedBytes)
        }
    }

    @Test fun forbiddenResponseIsCorrelatedWithParserWithoutCredentialsOrBody() = runBlocking {
        withContext(Dispatchers.IO) { AppDiagnostics.setRecording(true) }
        val client = OkHttpClient.Builder()
            .addInterceptor(DiagnosticsHttpInterceptor())
            .addInterceptor { chain ->
                // In-memory transport: this test never opens a socket.
                assertNull(chain.request().header("X-Diagnostic-Id"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("SECRET")
                    .header("Server", "cloudflare").header("cf-mitigated", "challenge")
                    .networkResponse(Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(403).message("SECRET").build())
                    .body("<html>SECRET</html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }.build()
        try {
            DiagnosticOperations.trace("audioboo", "book") {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("https://audioboo.org/SECRET?token=SECRET")
                        .header("Cookie", "session=SECRET").header("Authorization", "Bearer SECRET").build()
                    client.newCall(request).execute().use { assertEquals(403, it.code) }
                }
                throw IOException("SECRET")
            }
        } catch (_: IOException) {
            // A parser failure is rethrown unchanged and recorded without its message.
        }
        val text = withContext(Dispatchers.IO) { AppDiagnostics.exportReport(false).readText() }
        val complete = text.lineSequence().first { it.contains("event=http_complete") && it.contains("operation=book") && it.contains("source=audioboo") }
        val failed = text.lineSequence().first { it.contains("event=parser_failed") && it.contains("operation=book") && it.contains("source=audioboo") }
        val operationId = Regex("operationId=([0-9]+)").find(complete)!!.groupValues[1]
        assertTrue(failed.contains("operationId=$operationId"))
        assertTrue(complete.contains("status=403"))
        assertTrue(complete.contains("source=audioboo"))
        assertTrue(complete.contains("operation=book"))
        assertTrue(complete.contains("challengeHint=true"))
        assertTrue(complete.contains("sentCookie=true"))
        assertTrue(complete.contains("sentAuthorization=true"))
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("audioboo.org"))
    }

    @Test fun parallelParserContextsSurviveDispatcherSwitchWithoutLeaking() = runBlocking {
        withContext(Dispatchers.IO) { AppDiagnostics.setRecording(true) }
        coroutineScope {
            listOf("audioboo", "rutracker").map { source ->
                async {
                    DiagnosticOperations.trace(source, "search") {
                        withContext(Dispatchers.Default) {
                            delay(5)
                            assertEquals(source, DiagnosticOperations.current.get()?.source)
                        }
                    }
                    assertNull(DiagnosticOperations.current.get())
                }
            }.awaitAll()
        }
        assertNull(DiagnosticOperations.current.get())
    }

    @Test fun parserCancellationIsRethrownAndContextIsRestored() = runBlocking {
        withContext(Dispatchers.IO) { AppDiagnostics.setRecording(true) }
        val original = CancellationException("SECRET")
        try {
            DiagnosticOperations.trace<Unit>("rutracker", "book") { throw original }
            fail("Cancellation was swallowed")
        } catch (cancelled: CancellationException) {
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.take(8).any { it === original })
        }
        assertNull(DiagnosticOperations.current.get())
        val text = withContext(Dispatchers.IO) { AppDiagnostics.exportReport(false).readText() }
        assertTrue(text.contains("event=parser_cancelled"))
        assertFalse(text.contains("SECRET"))
    }

    @Test fun providerCannotExposeFilesOutsideReportDirectory() {
        val outside = File(context.cacheDir, "not-a-diagnostic-report.txt")
        outside.writeText("SECRET")
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.files", outside)
            fail("Provider exposed unrelated cache")
        } catch (_: IllegalArgumentException) {
            // Expected: provider path is restricted to diagnostic_reports/.
        } finally {
            outside.delete()
        }
    }
}
