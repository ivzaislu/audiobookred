package com.example.data.player

import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local durable book lookup used when the UI-side MediaController needs to
 * reconstruct book metadata. Media3/controller lifecycle guards remain owned by
 * AudiobookPlayerManager; this component owns Room IO and snapshot freshness.
 */
internal class PlaybackBookRecoveryRepository(
    private val cacheStore: LocalCacheStore,
    private val resumeStore: PlaybackResumeStore,
) {
    suspend fun recoverBook(bookId: String, sourceCode: String?): BookDetailDto? {
        val source = playbackSourceOrNull(sourceCode)
        val recovered = readBook(bookId, source) ?: return null
        if (recovered.id != bookId) return null
        if (!playbackSourceMatches(recovered.selectedSource, source)) return null
        return recovered
    }

    suspend fun recoverSnapshot(snapshot: PlaybackResumeStore.Snapshot): BookDetailDto? {
        val source = playbackSourceOrNull(snapshot.sourceCode)
        val recovered = readBook(snapshot.bookId, source) ?: return null
        if (recovered.id != snapshot.bookId || recovered.chapters.isEmpty()) return null
        if (!canRecoverPlaybackSnapshotSource(snapshot.sourceCode, recovered.sourceVariants.size)) return null
        if (!playbackSourceMatches(recovered.selectedSource, source)) return null

        val latest = resumeStore.latestSnapshot() ?: return null
        if (!samePlaybackSnapshotIdentity(snapshot, latest)) return null
        return recovered
    }

    private suspend fun readBook(bookId: String, sourceCode: String?): BookDetailDto? = try {
        withContext(Dispatchers.IO) {
            cacheStore.readBook(bookId, sourceCode)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

internal fun canRecoverPlaybackSnapshotSource(
    snapshotSourceCode: String,
    sourceVariantCount: Int,
): Boolean = playbackSourceOrNull(snapshotSourceCode) != null || sourceVariantCount <= 1

internal fun samePlaybackSnapshotIdentity(
    expected: PlaybackResumeStore.Snapshot,
    actual: PlaybackResumeStore.Snapshot,
): Boolean {
    val expectedSource = playbackSourceOrNull(expected.sourceCode)
    val actualSource = playbackSourceOrNull(actual.sourceCode)
    val sameSource = when {
        expectedSource == null || actualSource == null -> expectedSource == null && actualSource == null
        else -> expectedSource.equals(actualSource, ignoreCase = true)
    }
    return expected.bookId == actual.bookId &&
        sameSource &&
        expected.savedAtMs == actual.savedAtMs
}
