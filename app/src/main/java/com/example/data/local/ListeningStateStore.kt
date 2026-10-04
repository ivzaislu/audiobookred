package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.data.player.PlaybackResumeStore
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val PROGRESS_HISTORY_REPAIR_MARKER_KEY = "maintenance:progress-history-reconciled:v1"

/**
 * Owns destructive cleanup of per-book listening state.
 *
 * Removing a book from standalone history means forgetting its playback
 * progress too. Favorites, bookmarks and downloaded files remain independent
 * references and continue to retain the book metadata when present.
 */
class ListeningStateStore(
    context: Context,
    private val resumeStore: PlaybackResumeStore,
) {
    private val appContext = context.applicationContext
    private val database = AbredDatabase.get(appContext)
    private val payloads = database.cachedPayloads()
    private val catalog = database.localCatalog()
    private val normalizedLibrary = database.normalizedLibrary()

    suspend fun forgetBookProgress(bookId: String) {
        if (bookId.isBlank()) return
        forgetRoomProgress(bookId)
        resumeStore.clear(bookId)
        clearLegacyResume(bookId)
    }

    private suspend fun forgetRoomProgress(bookId: String) {
        database.withTransaction {
            forgetRoomProgressWithinTransaction(bookId)
        }
    }

    private suspend fun forgetRoomProgressWithinTransaction(bookId: String) {
        payloads.allWithPrefix(progressPrefix(bookId)).forEach { row ->
            payloads.delete(row.cacheKey)
        }

        val retention = catalog.retention(bookId)
        if (retention?.progressRef == true) {
            catalog.putRetention(
                retention.copy(
                    progressRef = false,
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }

        catalog.pruneUnreferencedBooks()
        catalog.pruneEmptyRetention()
    }

    /**
     * One-time standalone repair for installs where historical progress refs
     * survived after the corresponding normalized history entries were removed.
     *
     * The normalized-library migration marker is the safety gate: if v7 -> v8
     * normalization has not completed, an empty normalized history is not yet an
     * authoritative signal and no destructive cleanup may run.
     */
    suspend fun reconcileProgressToHistoryOnce(): Boolean =
        database.withTransaction {
            if (payloads.get(PROGRESS_HISTORY_REPAIR_MARKER_KEY) != null) {
                return@withTransaction false
            }
            if (payloads.get(NORMALIZED_LIBRARY_MIGRATION_MARKER_KEY) == null) {
                return@withTransaction false
            }

            val historyBookIds = normalizedLibrary.history()
                .mapTo(linkedSetOf(), LibraryHistoryEntity::bookId)
            val staleBookIds = mutableListOf<String>()
            for (book in catalog.allBooks()) {
                val retention = catalog.retention(book.bookId) ?: continue
                if (retention.progressRef && book.bookId !in historyBookIds) {
                    staleBookIds += book.bookId
                }
            }

            // Keep discovery + destructive Room cleanup + completion marker in
            // one transaction. Current history mutations use the same database,
            // so a book cannot be concurrently re-added between the authoritative
            // history read and the progress deletion.
            staleBookIds.forEach { bookId ->
                if (!resumeStore.clearCommitted(bookId) || !clearLegacyResumeCommitted(bookId)) {
                    throw IOException("Failed to durably clear playback resume for $bookId")
                }
                forgetRoomProgressWithinTransaction(bookId)
            }

            payloads.put(
                CachedPayloadEntity(
                    cacheKey = PROGRESS_HISTORY_REPAIR_MARKER_KEY,
                    payloadJson = "true",
                    savedAtMs = System.currentTimeMillis(),
                )
            )
            true
        }

    private fun clearLegacyResume(bookId: String) {
        clearLegacyResumeEditor(bookId)?.apply()
    }

    private fun clearLegacyResumeCommitted(bookId: String): Boolean =
        clearLegacyResumeEditor(bookId)?.commit() ?: true

    private fun clearLegacyResumeEditor(bookId: String) =
        appContext.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            .takeIf { prefs -> prefs.getString(LEGACY_BOOK_ID, null) == bookId }
            ?.edit()
            ?.remove(LEGACY_BOOK_ID)
            ?.remove("chapter_id")
            ?.remove("chapter_index")
            ?.remove("position_ms")
            ?.remove("speed")
            ?.remove("saved_at_ms")

    private fun progressPrefix(bookId: String): String =
        "progress:${URLEncoder.encode(bookId, StandardCharsets.UTF_8.toString())}:"

    private companion object {
        const val RESUME_PREFS = "audiobookred_playback_resume"
        const val LEGACY_BOOK_ID = "book_id"
    }
}
