package com.example.data.download

import com.example.data.local.ActiveDownloadGenerationRegistry
import com.example.data.local.legacySafeSegment
import com.example.data.local.obsoleteDownloadGenerationNames
import com.example.data.local.resolveDownloadExpectedBytes
import com.example.data.local.stableDownloadSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHttpPolicyTest {
    @Test
    fun resumed206MustStartAtRequestedOffset() {
        assertTrue(DownloadHttpPolicy.contentRangeStartsAt("bytes 1024-2047/4096", 1024L))
        assertTrue(DownloadHttpPolicy.contentRangeStartsAt("Bytes 1024-2047/*", 1024L))
        assertFalse(DownloadHttpPolicy.contentRangeStartsAt("bytes 0-2047/4096", 1024L))
        assertFalse(DownloadHttpPolicy.contentRangeStartsAt("invalid", 1024L))
        assertFalse(DownloadHttpPolicy.contentRangeStartsAt(null, 1024L))
    }

    @Test
    fun responseHeadersProvideExpectedFinalSizeWhenManifestDoesNot() {
        assertEquals(
            4096L,
            DownloadHttpPolicy.expectedFinalSize(
                statusCode = 206,
                contentRangeHeader = "bytes 1024-2047/4096",
                contentLength = 1024L,
                requestedOffset = 1024L,
            )
        )
        assertNull(
            DownloadHttpPolicy.expectedFinalSize(
                statusCode = 206,
                contentRangeHeader = "bytes 1024-4095/*",
                contentLength = 3072L,
                requestedOffset = 1024L,
            )
        )
        assertEquals(
            4096L,
            DownloadHttpPolicy.expectedFinalSize(
                statusCode = 200,
                contentRangeHeader = null,
                contentLength = 4096L,
                requestedOffset = 1024L,
            )
        )
        assertNull(
            DownloadHttpPolicy.expectedFinalSize(
                statusCode = 200,
                contentRangeHeader = null,
                contentLength = -1L,
                requestedOffset = 0L,
            )
        )
    }

    @Test
    fun partial206ContinuesInsideSameWorkerRun() {
        assertTrue(
            DownloadHttpPolicy.shouldContinueRangedResponse(
                statusCode = 206,
                writtenBytes = 1_048_576L,
                expectedBytes = 4_194_304L,
            )
        )
        assertFalse(
            DownloadHttpPolicy.shouldContinueRangedResponse(
                statusCode = 206,
                writtenBytes = 4_194_304L,
                expectedBytes = 4_194_304L,
            )
        )
        assertFalse(
            DownloadHttpPolicy.shouldContinueRangedResponse(
                statusCode = 200,
                writtenBytes = 1_048_576L,
                expectedBytes = 4_194_304L,
            )
        )
        assertFalse(
            DownloadHttpPolicy.shouldContinueRangedResponse(
                statusCode = 206,
                writtenBytes = 1_048_576L,
                expectedBytes = null,
            )
        )
        assertTrue(
            DownloadHttpPolicy.shouldContinueRangedResponse(
                statusCode = 206,
                writtenBytes = 1_048_576L,
                expectedBytes = null,
                continueUnknownLengthRange = true,
            )
        )
    }

    @Test
    fun redirecttoUnknownLengthUses416AsCleanEofOnlyAfterDataWasWritten() {
        assertEquals(
            2_097_152L,
            DownloadHttpPolicy.completedSizeFromRangeNotSatisfiable(
                statusCode = 416,
                requestedOffset = 2_097_152L,
                expectedBytes = null,
                rangedCdnMp3 = true,
            )
        )
        assertNull(
            DownloadHttpPolicy.completedSizeFromRangeNotSatisfiable(
                statusCode = 416,
                requestedOffset = 0L,
                expectedBytes = null,
                rangedCdnMp3 = true,
            )
        )
        assertNull(
            DownloadHttpPolicy.completedSizeFromRangeNotSatisfiable(
                statusCode = 416,
                requestedOffset = 2_097_152L,
                expectedBytes = null,
                rangedCdnMp3 = false,
            )
        )
        assertEquals(
            4096L,
            DownloadHttpPolicy.completedSizeFromRangeNotSatisfiable(
                statusCode = 416,
                requestedOffset = 4096L,
                expectedBytes = 4096L,
                rangedCdnMp3 = false,
            )
        )
        assertNull(
            DownloadHttpPolicy.completedSizeFromRangeNotSatisfiable(
                statusCode = 416,
                requestedOffset = 4095L,
                expectedBytes = 4096L,
                rangedCdnMp3 = true,
            )
        )
    }

    @Test
    fun unknownLengthLimitCheckIsOverflowSafe() {
        assertFalse(DownloadHttpPolicy.wouldExceedLimit(1024L, 1024, 4096L))
        assertTrue(DownloadHttpPolicy.wouldExceedLimit(4090L, 16, 4096L))
        assertTrue(DownloadHttpPolicy.wouldExceedLimit(Long.MAX_VALUE, 1, Long.MAX_VALUE))
    }

    @Test
    fun completedUnknownSizeBecomesLocalBaseline() {
        assertEquals(
            4096L,
            resolveDownloadExpectedBytes(
                manifestBytes = null,
                persistedBytes = null,
                previousState = "completed",
                previousDownloadedBytes = 4096L,
            )
        )
        assertNull(
            resolveDownloadExpectedBytes(
                manifestBytes = null,
                persistedBytes = null,
                previousState = "queued",
                previousDownloadedBytes = 2048L,
            )
        )
        assertNull(
            resolveDownloadExpectedBytes(
                manifestBytes = null,
                persistedBytes = null,
                previousState = "completed",
                previousDownloadedBytes = 0L,
            )
        )
    }

    @Test
    fun explicitOrPersistedExpectedSizeWinsOverRecordedProgress() {
        assertEquals(
            8192L,
            resolveDownloadExpectedBytes(
                manifestBytes = 8192L,
                persistedBytes = 4096L,
                previousState = "completed",
                previousDownloadedBytes = 2048L,
            )
        )
        assertEquals(
            4096L,
            resolveDownloadExpectedBytes(
                manifestBytes = null,
                persistedBytes = 4096L,
                previousState = "completed",
                previousDownloadedBytes = 2048L,
            )
        )
    }

    @Test
    fun stableStorageSegmentSeparatesIdsThatLegacySanitizerCollides() {
        val slash = "live:a/b"
        val colon = "live:a:b"
        assertEquals(legacySafeSegment(slash), legacySafeSegment(colon))
        assertNotEquals(stableDownloadSegment(slash), stableDownloadSegment(colon))
        assertEquals(stableDownloadSegment(slash), stableDownloadSegment(slash))
    }

    @Test
    fun stableStorageSegmentUsesFullIdBeyondLegacyTruncation() {
        val prefix = "x".repeat(120)
        val first = "$prefix-A"
        val second = "$prefix-B"
        assertEquals(legacySafeSegment(first), legacySafeSegment(second))
        assertNotEquals(stableDownloadSegment(first), stableDownloadSegment(second))
    }

    @Test
    fun generationCleanupProtectsCurrentAndStillRunningStaleWorker() {
        val current = "manifest-current"
        val activeStale = "manifest-still-running"
        val obsolete = "manifest-obsolete"
        val result = obsoleteDownloadGenerationNames(
            childDirectoryNames = listOf(
                stableDownloadSegment(current),
                stableDownloadSegment(activeStale),
                stableDownloadSegment(obsolete),
            ),
            currentManifestId = current,
            activeManifestIds = setOf(activeStale),
        )

        assertEquals(setOf(stableDownloadSegment(obsolete)), result)
    }

    @Test
    fun generationCleanupRemovesAllInactiveGenerationsWithoutCurrentRow() {
        val first = stableDownloadSegment("old-1")
        val second = stableDownloadSegment("old-2")
        assertEquals(
            setOf(first, second),
            obsoleteDownloadGenerationNames(
                childDirectoryNames = listOf(first, second),
                currentManifestId = null,
                activeManifestIds = emptySet(),
            ),
        )
    }

    @Test
    fun generationRegistryKeepsOverlappingSameManifestProtectedUntilLastWorkerFinishes() {
        val registry = ActiveDownloadGenerationRegistry()
        val source = "bazaknig:book-42"
        val manifest = "manifest-shared"

        registry.markActive(source, manifest)
        registry.markActive(source, manifest)
        registry.markInactive(source, manifest)
        assertEquals(setOf(manifest), registry.activeManifestIds(source))

        registry.markInactive(source, manifest)
        assertTrue(registry.activeManifestIds(source).isEmpty())
    }

    @Test
    fun generationRegistrySnapshotIncludesAllCurrentlyActiveManifestIds() {
        val registry = ActiveDownloadGenerationRegistry()
        val source = "bazaknig:book-43"
        registry.markActive(source, "manifest-a")
        registry.markActive(source, "manifest-b")

        val protected = registry.withActiveManifestIds(source) { it }

        assertEquals(setOf("manifest-a", "manifest-b"), protected)
    }
}
