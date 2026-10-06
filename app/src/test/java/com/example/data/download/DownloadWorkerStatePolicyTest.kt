package com.example.data.download

import com.example.data.local.DownloadBookEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWorkerStatePolicyTest {
    @Test
    fun pausedDownloadCannotBeTurnedIntoRetryOrFailureByWorkerError() {
        assertTrue(
            downloadWorkerFailureIsObsoleteOrStopped(
                book(state = "paused"),
                EXPECTED_MANIFEST,
            )
        )
    }

    @Test
    fun activeCurrentGenerationMayHandleRealFailure() {
        assertFalse(
            downloadWorkerFailureIsObsoleteOrStopped(
                book(state = "downloading"),
                EXPECTED_MANIFEST,
            )
        )
    }

    @Test
    fun progressPersistenceIsRateLimitedByElapsedTime() {
        assertFalse(shouldPersistDownloadProgress(nowElapsedMs = 500L, lastPersistElapsedMs = 0L))
        assertTrue(shouldPersistDownloadProgress(nowElapsedMs = 1_000L, lastPersistElapsedMs = 0L))
        assertTrue(shouldPersistDownloadProgress(nowElapsedMs = 2_500L, lastPersistElapsedMs = 1_000L))
        assertFalse(shouldPersistDownloadProgress(nowElapsedMs = 900L, lastPersistElapsedMs = 1_000L))
    }

    @Test
    fun staleDeletedOrTerminalGenerationIgnoresLateWorkerFailure() {
        assertTrue(downloadWorkerFailureIsObsoleteOrStopped(book(manifestId = "old"), EXPECTED_MANIFEST))
        assertTrue(downloadWorkerFailureIsObsoleteOrStopped(book(deletedAtMs = 1L), EXPECTED_MANIFEST))
        assertTrue(downloadWorkerFailureIsObsoleteOrStopped(book(state = "completed"), EXPECTED_MANIFEST))
        assertTrue(downloadWorkerFailureIsObsoleteOrStopped(book(state = "purged"), EXPECTED_MANIFEST))
        assertTrue(downloadWorkerFailureIsObsoleteOrStopped(null, EXPECTED_MANIFEST))
    }

    private fun book(
        manifestId: String = EXPECTED_MANIFEST,
        state: String = "downloading",
        deletedAtMs: Long? = null,
    ) = DownloadBookEntity(
        bookSourceId = "source:book",
        bookId = "book",
        sourceCode = "source",
        sourceName = "Source",
        title = "Book",
        manifestId = manifestId,
        state = state,
        deletedAtMs = deletedAtMs,
    )

    private companion object {
        const val EXPECTED_MANIFEST = "manifest-current"
    }
}
