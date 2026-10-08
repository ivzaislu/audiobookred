package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.data.player.PlaybackResumeStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    private fun clearLegacyResume(bookId: String) {
        clearLegacyResumeEditor(bookId)?.apply()
    }

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
